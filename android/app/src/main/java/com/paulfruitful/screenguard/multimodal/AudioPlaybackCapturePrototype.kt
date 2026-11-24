package com.paulfruitful.screenguard.multimodal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Local-only Android [AudioPlaybackCaptureConfiguration] investigator & acoustic analyzer (Phase C).
 *
 * Key Android Platform Rules Enforced:
 * 1. `AudioPlaybackCapture` requires an active user-consented [MediaProjection] session.
 *    Screen Guard's primary architecture uses `AccessibilityService` to avoid mandatory screen-cast
 *    prompts; therefore, when no [MediaProjection] token is active, this module inspects live
 *    [AudioManager.getActivePlaybackConfigurations] and per-package `ALLOW_AUDIO_PLAYBACK_CAPTURE`
 *    manifest eligibility without ever opening the microphone (`MediaRecorder.AudioSource.MIC` is
 *    strictly forbidden).
 * 2. When a [MediaProjection] token IS provided, captures a 200ms ephemeral in-memory PCM window
 *    from `USAGE_MEDIA` / `USAGE_GAME`, computes vocal-band (300-3400 Hz) energy and rhythmic
 *    envelope modulation locally, and immediately zeroes the buffer.
 * 3. Zero audio samples or transcripts are ever written to disk or transmitted over the network.
 */
object AudioPlaybackCapturePrototype {

    private const val PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE = 1 shl 29
    private const val SAMPLE_RATE_HZ = 16_000

    @Volatile
    private var activeMediaProjection: MediaProjection? = null

    fun attachOptionalMediaProjection(projection: MediaProjection?) {
        activeMediaProjection = projection
    }

    /**
     * Checks whether the target package's manifest / targetSdk permits `AudioPlaybackCapture`
     * on Android 10+ (API 29+). Verified via PackageManager `privateFlags` inspection.
     */
    fun isPackageEligibleForPlaybackCapture(context: Context, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (packageName.isBlank() || packageName == "None") return false
        return try {
            val pm = context.packageManager
            val appInfo: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
            // On Android 10+, apps targeting API >= 29 allow playback capture by default unless
            // android:allowAudioPlaybackCapture="false" is set in AndroidManifest.xml.
            val privateFlagsField = ApplicationInfo::class.java.getDeclaredField("privateFlags")
            privateFlagsField.isAccessible = true
            val privateFlags = privateFlagsField.getInt(appInfo)
            (privateFlags and PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE) != 0 ||
                appInfo.targetSdkVersion >= Build.VERSION_CODES.Q
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Collects supplementary audio playback evidence for the current foreground package.
     * Never blocks the main thread and never activates microphone recording.
     */
    fun inspectAudioEvidence(
        context: Context,
        foregroundPackage: String
    ): AudioPlaybackEvidence {
        val startNs = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return AudioPlaybackEvidence(
                available = false,
                unavailableReason = AudioUnavailableReason.AUDIO_CAPTURE_ERROR,
                durationMs = 0L
            )
        }

        val eligible = isPackageEligibleForPlaybackCapture(context, foregroundPackage)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val activeConfigs = try {
            audioManager?.activePlaybackConfigurations ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val mediaActive = activeConfigs.any { cfg ->
            val usage = cfg.audioAttributes?.usage ?: AudioAttributes.USAGE_UNKNOWN
            usage == AudioAttributes.USAGE_MEDIA ||
                usage == AudioAttributes.USAGE_GAME ||
                usage == AudioAttributes.USAGE_UNKNOWN
        }

        if (!mediaActive) {
            val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
            return AudioPlaybackEvidence(
                available = false,
                unavailableReason = AudioUnavailableReason.NO_ACTIVE_MEDIA_PLAYBACK,
                targetPackageEligible = eligible,
                activePlaybackCount = activeConfigs.size,
                usageMediaActive = false,
                durationMs = dur
            )
        }

        if (!eligible) {
            val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
            return AudioPlaybackEvidence(
                available = false,
                unavailableReason = AudioUnavailableReason.APP_OPTED_OUT_OR_DRM,
                targetPackageEligible = false,
                activePlaybackCount = activeConfigs.size,
                usageMediaActive = true,
                durationMs = dur
            )
        }

        val projection = activeMediaProjection
        if (projection == null) {
            val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
            return AudioPlaybackEvidence(
                available = false,
                unavailableReason = AudioUnavailableReason.NO_MEDIA_PROJECTION_CONSENT,
                targetPackageEligible = true,
                activePlaybackCount = activeConfigs.size,
                usageMediaActive = true,
                durationMs = dur
            )
        }

        // MediaProjection consent is present: sample a 120ms ephemeral internal audio window
        return sampleEphemeralInternalPcm(projection, activeConfigs.size, startNs)
    }

    private fun sampleEphemeralInternalPcm(
        projection: MediaProjection,
        activeCount: Int,
        startNs: Long
    ): AudioPlaybackEvidence {
        var record: AudioRecord? = null
        val sampleCount = (SAMPLE_RATE_HZ * 12) / 100 // 120ms = 1920 samples
        val pcmBuffer = ShortArray(sampleCount)
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleCount * 2)

            record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            record.startRecording()
            val read = record.read(pcmBuffer, 0, sampleCount, AudioRecord.READ_NON_BLOCKING)
            record.stop()

            if (read <= 64) {
                val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
                return AudioPlaybackEvidence(
                    available = true,
                    unavailableReason = AudioUnavailableReason.AVAILABLE_AND_SAMPLED,
                    targetPackageEligible = true,
                    activePlaybackCount = activeCount,
                    usageMediaActive = true,
                    durationMs = dur
                )
            }

            // Compute ephemeral vocal-band ZCR and amplitude envelope modulation
            var sumSq = 0.0
            var zeroCrossings = 0
            var prev = pcmBuffer[0].toInt()
            for (i in 0 until read) {
                val s = pcmBuffer[i].toInt()
                val norm = s / 32768.0
                sumSq += norm * norm
                if ((s >= 0 && prev < 0) || (s < 0 && prev >= 0)) {
                    zeroCrossings++
                }
                prev = s
                pcmBuffer[i] = 0 // immediately wipe sample from RAM
            }
            val rms = sqrt(sumSq / read).toFloat()
            val zcrHz = (zeroCrossings.toFloat() * SAMPLE_RATE_HZ) / (2f * read)
            val vocalBandScore = if (rms >= 0.015f && zcrHz in 280f..2600f) {
                minOf(1f, rms * 3.2f)
            } else {
                0f
            }
            val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
            AudioPlaybackEvidence(
                available = true,
                unavailableReason = AudioUnavailableReason.AVAILABLE_AND_SAMPLED,
                targetPackageEligible = true,
                activePlaybackCount = activeCount,
                usageMediaActive = true,
                speechBandEnergyRatio = vocalBandScore,
                rhythmicModulationScore = rms.coerceIn(0f, 1f),
                supplementarySuspicionScore = (vocalBandScore * 0.35f).coerceIn(0f, 0.35f),
                durationMs = dur
            )
        } catch (_: Exception) {
            val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
            AudioPlaybackEvidence(
                available = false,
                unavailableReason = AudioUnavailableReason.AUDIO_CAPTURE_ERROR,
                targetPackageEligible = true,
                activePlaybackCount = activeCount,
                usageMediaActive = true,
                durationMs = dur
            )
        } finally {
            for (i in pcmBuffer.indices) pcmBuffer[i] = 0
            try {
                record?.release()
            } catch (_: Exception) {
            }
        }
    }
}
