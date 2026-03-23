package com.app.remotesync.utils

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

object ScreenUtils {

    /** Returns the real display size (including system bars) in pixels. */
    fun getRealScreenSize(context: Context): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Point(dm.widthPixels, dm.heightPixels)
        }
    }

    /** Returns screen width in pixels. */
    fun getScreenWidth(context: Context): Int = getRealScreenSize(context).x

    /** Returns screen height in pixels. */
    fun getScreenHeight(context: Context): Int = getRealScreenSize(context).y
}
