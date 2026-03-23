package com.app.remotesync.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebRtcModule {

    /**
     * A single shared EglBase for the lifetime of the application.
     * Both the encoder (ScreenCapturerAndroid) and renderer (SurfaceViewRenderer)
     * must share the same EglBase context to exchange video frames on the GPU.
     */
    @Provides
    @Singleton
    fun provideEglBase(): EglBase = EglBase.create()

    /**
     * Initializes and provides the PeerConnectionFactory.
     * Called once at application scope; safe to reuse across sessions.
     */
    @Provides
    @Singleton
    fun providePeerConnectionFactory(
        @ApplicationContext context: Context,
        eglBase: EglBase
    ): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }
}
