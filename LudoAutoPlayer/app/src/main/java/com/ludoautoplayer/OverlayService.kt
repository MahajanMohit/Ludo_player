package com.ludoautoplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ludoautoplayer.models.PlayerColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * OverlayService is the central coordinator for the LudoAutoPlayer bot loop.
 *
 * Bot loop (runs every ~500 ms while active):
 *  1.  Check if it is the bot's turn (activePlayer == botColor)
 *  2.  Tap the dice button if dice has not been rolled
 *  3.  Wait 2500 ms for the dice animation to finish
 *  4.  Read the dice value from the current frame
 *  5.  Get all piece positions
 *  6.  Get moveable pieces
 *  7.  If none → skip turn
 *  8.  Feed game state to StrategyEngine
 *  9.  Resolve the best piece's tap coordinate via BoardMapper
 * 10.  Execute tap via TapExecutor
 * 11.  Wait 1500 ms for move animation
 * 12.  Log status to the overlay UI
 * 13.  If dice == 6 → repeat from step 2 (bonus turn)
 * 14.  Continue to next iteration
 */
class OverlayService : Service() {

    // ==================================================================================
    // State
    // ==================================================================================

    enum class BotStatus { IDLE, ANALYSING, MOVING, WAITING_FOR_TURN }

    private var botStatus: BotStatus = BotStatus.IDLE
    private var moveCounter: Int = 0
    private var lastActionText: String = "Idle"

    // Configuration — set via Intent extras before starting the service
    var botColor: PlayerColor = PlayerColor.RED
    var strategy: StrategyEngine.Strategy = StrategyEngine.Strategy.AGGRESSIVE

    // Dependencies
    private lateinit var boardMapper: BoardMapper
    private lateinit var strategyEngine: StrategyEngine
    private lateinit var tapExecutor: TapExecutor
    private lateinit var analyser: GameStateAnalyser
    private var screenCaptureManager: ScreenCaptureManager? = null
    private var mediaProjection: MediaProjection? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var botLoopJob: Job? = null

    // ==================================================================================
    // Service lifecycle
    // ==================================================================================

    override fun onCreate() {
        super.onCreate()
        boardMapper    = BoardMapper()
        strategyEngine = StrategyEngine()
        tapExecutor    = TapExecutor()
        analyser       = GameStateAnalyser(boardMapper)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Read configuration from intent
                intent.getStringExtra(EXTRA_BOT_COLOR)?.let { name ->
                    botColor = PlayerColor.values().firstOrNull { it.name == name } ?: PlayerColor.RED
                }
                intent.getStringExtra(EXTRA_STRATEGY)?.let { name ->
                    strategy = StrategyEngine.Strategy.values().firstOrNull { it.name == name }
                                ?: StrategyEngine.Strategy.AGGRESSIVE
                }
                strategyEngine.strategy = strategy

                // Retrieve MediaProjection token
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data       = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                if (data != null) {
                    val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    try {
                        val projection = mpManager.getMediaProjection(resultCode, data)
                        mediaProjection = projection
                        screenCaptureManager = ScreenCaptureManager(this, projection)
                        // Mark media projection as granted
                        getSharedPreferences(PermissionManager.PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putBoolean(PermissionManager.KEY_MEDIA_PROJECTION_GRANTED, true).apply()
                        startBotLoop()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Failed to obtain MediaProjection: ${e.message}")
                    }
                } else {
                    Log.e(TAG, "No MediaProjection data provided — cannot start capture")
                }
            }

            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        botLoopJob?.cancel()
        serviceScope.cancel()
        screenCaptureManager?.release()
        screenCaptureManager = null
        mediaProjection = null

        // Clear SharedPrefs flag so next session requests a new token
        getSharedPreferences(PermissionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PermissionManager.KEY_MEDIA_PROJECTION_GRANTED).apply()

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================================================================================
    // Bot loop
    // ==================================================================================

    private fun startBotLoop() {
        botLoopJob?.cancel()
        botLoopJob = serviceScope.launch {
            Log.i(TAG, "Bot loop started for color=$botColor strategy=$strategy")
            while (isActive) {
                runBotCycle()
                delay(BOT_LOOP_INTERVAL_MS)
            }
        }
    }

    private suspend fun runBotCycle() {
        val capture = screenCaptureManager ?: return

        updateStatus(BotStatus.ANALYSING)

        // Capture a frame and analyse
        val frame = capture.captureOnce() ?: return
        val gameState = analyser.analyseFrame(frame)
        frame.recycle()

        // Check if it's our turn
        if (gameState.activePlayer != botColor) {
            updateStatus(BotStatus.WAITING_FOR_TURN)
            return
        }

        // --- Tap dice if not yet rolled -----------------------------------------------
        if (gameState.diceValue == -1) {
            updateStatus(BotStatus.ANALYSING)
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val diceCoord = boardMapper.getDiceButtonCoordinate(screenW, screenH)

            var tapped = false
            tapExecutor.tapAt(diceCoord.x, diceCoord.y) { tapped = true }

            // Wait up to 1 s for the callback, then continue regardless
            var waited = 0
            while (!tapped && waited < 1000) {
                delay(50)
                waited += 50
            }

            // Wait for dice animation
            delay(DICE_ANIMATION_WAIT_MS)
        }

        // --- Read updated game state after dice roll -----------------------------------
        val frameAfterDice = capture.captureOnce() ?: return
        val stateAfterRoll = analyser.analyseFrame(frameAfterDice)
        frameAfterDice.recycle()

        val diceValue = stateAfterRoll.diceValue
        if (diceValue == -1) {
            // Dice still not visible; skip this cycle
            lastActionText = "Waiting for dice"
            return
        }

        // --- Select best move ---------------------------------------------------------
        updateStatus(BotStatus.MOVING)

        val bestPiece = strategyEngine.selectBestMove(stateAfterRoll, botColor)
        if (bestPiece == null) {
            lastActionText = "No valid moves — skipping"
            Log.d(TAG, "No valid moves for $botColor (dice=$diceValue)")
            updateOverlayText()
            return
        }

        // Resolve tap coordinate
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val tapCoord = boardMapper.getCoordinateForPosition(
            index          = bestPiece.position.index,
            isHomeBase     = bestPiece.position.isHomeBase,
            isHomeColumn   = bestPiece.position.isHomeColumn,
            isCenter       = bestPiece.position.isCenter,
            color          = bestPiece.color,
            pieceSlot      = bestPiece.id,
            screenW        = screenW,
            screenH        = screenH
        )

        // Execute tap
        var tapped = false
        tapExecutor.tapAt(tapCoord.x, tapCoord.y) { tapped = true }
        var waited = 0
        while (!tapped && waited < 2000) {
            delay(50)
            waited += 50
        }

        moveCounter++
        lastActionText = "Moved ${bestPiece.color.colorName} piece #${bestPiece.id} (dice=$diceValue)"
        Log.i(TAG, lastActionText)

        // Wait for move animation
        delay(MOVE_ANIMATION_WAIT_MS)
        updateOverlayText()

        // --- Bonus turn (dice == 6) ----------------------------------------------------
        if (diceValue == 6) {
            Log.d(TAG, "Bonus turn triggered (dice==6)")
            delay(BONUS_TURN_DELAY_MS)
            runBotCycle()   // recursive call for bonus turn
        }
    }

    // ==================================================================================
    // Notification
    // ==================================================================================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ludo Auto Player",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Running in background to auto-play Ludo King"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ludo Auto Player Active")
            .setContentText("Auto-playing Ludo King…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private fun updateStatus(status: BotStatus) {
        botStatus = status
        val text = when (status) {
            BotStatus.IDLE              -> "Idle"
            BotStatus.ANALYSING         -> "Analysing…"
            BotStatus.MOVING            -> "Moving…"
            BotStatus.WAITING_FOR_TURN  -> "Waiting for turn"
        }
        LudoAccessibilityService.instance?.updateStatus(text)
    }

    private fun updateOverlayText() {
        LudoAccessibilityService.instance?.updateMoveCount(moveCounter)
        LudoAccessibilityService.instance?.updateStatus(lastActionText)
    }

    // ==================================================================================
    // Constants
    // ==================================================================================

    companion object {
        private const val TAG = "OverlayService"

        const val ACTION_START = "com.ludoautoplayer.ACTION_START"
        const val ACTION_STOP  = "com.ludoautoplayer.ACTION_STOP"

        const val EXTRA_BOT_COLOR        = "bot_color"
        const val EXTRA_STRATEGY         = "strategy"
        const val EXTRA_RESULT_CODE      = "result_code"
        const val EXTRA_PROJECTION_DATA  = "projection_data"

        private const val NOTIFICATION_ID            = 1001
        private const val CHANNEL_ID                 = "ludo_auto_player_overlay"
        private const val BOT_LOOP_INTERVAL_MS       = 500L
        private const val DICE_ANIMATION_WAIT_MS     = 2500L
        private const val MOVE_ANIMATION_WAIT_MS     = 1500L
        private const val BONUS_TURN_DELAY_MS        = 800L
    }
}
