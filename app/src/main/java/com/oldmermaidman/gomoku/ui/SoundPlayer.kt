package com.oldmermaidman.gomoku.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Plays a short "stone placed" click.
 *
 * The click is synthesised at runtime rather than shipped as an audio asset, so
 * the APK carries no media files. One static [AudioTrack] is created on first use
 * and replayed with `reloadStaticData()`; the volume is applied per playback.
 */
class SoundPlayer {

    companion object {
        private const val TAG = "SoundPlayer"
        private const val SAMPLE_RATE = 44_100
        private const val DURATION_MS = 45
        private const val NOISE_MS = 2
    }

    private var track: AudioTrack? = null

    /** @param volumePercent 0..100; 0 plays nothing. */
    fun play(volumePercent: Int) {
        val volume = volumePercent.coerceIn(0, 100)
        if (volume == 0) return

        val t = ensureTrack() ?: return
        try {
            t.setVolume(volume / 100f)
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cannot play the move sound", e)
        }
    }

    fun release() {
        try {
            track?.release()
        } catch (_: Exception) {
            // Already released or the device is gone — nothing useful to do.
        }
        track = null
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        return try {
            val samples = synthesizeClick()
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(samples, 0, samples.size)
            track = t
            t
        } catch (e: Exception) {
            Log.w(TAG, "cannot create the audio track", e)
            null
        }
    }

    /**
     * A wooden tick: two decaying partials plus a very short noise transient.
     */
    private fun synthesizeClick(): ShortArray {
        val count = SAMPLE_RATE * DURATION_MS / 1000
        val noiseCount = SAMPLE_RATE * NOISE_MS / 1000
        val out = ShortArray(count)
        val random = Random(7)

        for (i in 0 until count) {
            val seconds = i.toDouble() / SAMPLE_RATE
            val body = sin(2 * PI * 880.0 * seconds) * 0.65 +
                sin(2 * PI * 2350.0 * seconds) * 0.35
            val envelope = exp(-seconds / 0.009)
            var value = body * envelope

            if (i < noiseCount) {
                val fade = 1.0 - i.toDouble() / noiseCount
                value += (random.nextDouble() * 2 - 1) * 0.35 * fade
            }

            val clamped = value.coerceIn(-1.0, 1.0)
            out[i] = (clamped * Short.MAX_VALUE * 0.9).toInt().toShort()
        }
        return out
    }
}
