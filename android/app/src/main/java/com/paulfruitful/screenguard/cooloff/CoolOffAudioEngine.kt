package com.paulfruitful.screenguard.cooloff

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 100% Offline Instrumental Audio Engine for the Cool-Off Room.
 *
 * Synthesizes warm, calming, royalty-free instrumental compositions in real time using Android's
 * [AudioTrack] across all 5 categories (Soft Piano, Ambient, Gentle Acoustic, Calm Cinematic,
 * Worship Instrumental):
 * - Zero internet dependency and zero external media downloads.
 * - Smooth 1.5-second fade-in on session start and smooth fade-out on completion/pause.
 * - Seamless infinite looping.
 * - Mute toggle and continuous volume control.
 * - Full Android AudioFocus lifecycle handling.
 */
class CoolOffAudioEngine(private val appContext: Context) {

    companion object {
        private const val SAMPLE_RATE = 22050
        private const val CHUNK_FRAMES = 1024
        private const val FADE_IN_SECONDS = 1.5f
        private const val FADE_OUT_SECONDS = 1.2f
    }

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val running = AtomicBoolean(false)
    private val fadingOut = AtomicBoolean(false)

    @Volatile
    private var category: InstrumentalCategory = InstrumentalCategory.SOFT_PIANO

    @Volatile
    private var muted: Boolean = false

    @Volatile
    private var userVolume: Float = 0.55f

    @Volatile
    private var duckMultiplier: Float = 1.0f

    private var workerThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                duckMultiplier = 1.0f
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckMultiplier = 0.25f
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS -> {
                duckMultiplier = 0.0f
            }
        }
    }

    fun start(
        initialCategory: InstrumentalCategory,
        initialVolume: Float,
        initialMuted: Boolean
    ) {
        category = initialCategory
        userVolume = initialVolume.coerceIn(0f, 1f)
        muted = initialMuted
        fadingOut.set(false)
        if (running.get()) return

        requestFocus()
        running.set(true)
        workerThread = Thread({ runSynthLoop() }, "screenguard-cooloff-audio").apply {
            isDaemon = true
            start()
        }
    }

    fun setCategory(newCategory: InstrumentalCategory) {
        category = newCategory
    }

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
    }

    fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
    }

    fun fadeOutAndStop() {
        if (!running.get()) return
        fadingOut.set(true)
    }

    fun stopImmediately() {
        running.set(false)
        fadingOut.set(false)
        workerThread?.interrupt()
        workerThread = null
        releaseTrack()
        abandonFocus()
    }

    private fun runSynthLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_FRAMES * 4)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            running.set(false)
            abandonFocus()
            return
        }

        audioTrack = track
        try {
            track.play()
            val pcm = ShortArray(CHUNK_FRAMES)
            var sampleIndex = 0L
            var fadeOutProgress = 0f
            val fadeOutStep = 1f / (SAMPLE_RATE * FADE_OUT_SECONDS)

            // Peaceful 4-chord progression (Cmaj7 -> Am7 -> Fmaj7 -> G6) in Hz
            val chords = arrayOf(
                doubleArrayOf(261.63, 329.63, 392.00, 493.88), // Cmaj7
                doubleArrayOf(220.00, 261.63, 329.63, 392.00), // Am7
                doubleArrayOf(174.61, 220.00, 261.63, 329.63), // Fmaj7
                doubleArrayOf(196.00, 246.94, 293.66, 329.63)  // G6
            )
            val chordDurationSec = 4.5
            val chordDurationSamples = (SAMPLE_RATE * chordDurationSec).toLong()

            while (running.get()) {
                val activeCategory = category
                val baseGain = if (muted) 0f else (userVolume * duckMultiplier * 0.28f)

                for (i in 0 until CHUNK_FRAMES) {
                    val t = sampleIndex.toDouble() / SAMPLE_RATE.toDouble()
                    val chordIdx = ((sampleIndex / chordDurationSamples) % chords.size).toInt()
                    val posInChordSec = (sampleIndex % chordDurationSamples).toDouble() / SAMPLE_RATE.toDouble()
                    val chord = chords[chordIdx]

                    // Smooth chord crossfade envelope
                    val chordEnv = when {
                        posInChordSec < 0.35 -> posInChordSec / 0.35
                        posInChordSec > (chordDurationSec - 0.35) -> ((chordDurationSec - posInChordSec) / 0.35).coerceAtLeast(0.0)
                        else -> 1.0
                    }

                    val rawSample = when (activeCategory) {
                        InstrumentalCategory.SOFT_PIANO -> {
                            // Gentle arpeggiated warm felt-piano voices with natural decay
                            var acc = 0.0
                            for (n in chord.indices) {
                                val noteStart = n * 0.55
                                if (posInChordSec >= noteStart) {
                                    val dt = posInChordSec - noteStart
                                    val env = (1.0 - exp(-dt * 45.0)) * exp(-dt * 0.75)
                                    val f = chord[n]
                                    acc += env * (0.72 * sin(2.0 * PI * f * t) + 0.20 * sin(4.0 * PI * f * t))
                                }
                            }
                            acc * 0.36
                        }
                        InstrumentalCategory.AMBIENT -> {
                            // Slow-breathing warm harmonic pad
                            val breath = 0.75 + 0.25 * sin(2.0 * PI * 0.12 * t)
                            var acc = 0.0
                            for (f in chord) {
                                acc += sin(2.0 * PI * f * t) + 0.35 * sin(2.0 * PI * (f * 0.5) * t)
                            }
                            acc * 0.20 * chordEnv * breath
                        }
                        InstrumentalCategory.GENTLE_ACOUSTIC -> {
                            // Plucked soft acoustic harp/guitar arpeggio
                            var acc = 0.0
                            for (n in 0 until 6) {
                                val noteStart = n * 0.65
                                if (posInChordSec >= noteStart) {
                                    val dt = posInChordSec - noteStart
                                    val env = (1.0 - exp(-dt * 90.0)) * exp(-dt * 1.45)
                                    val f = chord[n % chord.size] * (if (n >= 4) 2.0 else 1.0)
                                    acc += env * (0.65 * sin(2.0 * PI * f * t) + 0.25 * sin(4.0 * PI * f * t) + 0.10 * sin(6.0 * PI * f * t))
                                }
                            }
                            acc * 0.34
                        }
                        InstrumentalCategory.CALM_CINEMATIC -> {
                            // Warm low cello root + lush slow string triad
                            val root = chord[0] * 0.5
                            val cello = 0.55 * sin(2.0 * PI * root * t) + 0.22 * sin(4.0 * PI * root * t)
                            val strings = 0.35 * sin(2.0 * PI * chord[1] * t) +
                                0.35 * sin(2.0 * PI * chord[2] * t) +
                                0.25 * sin(2.0 * PI * chord[3] * t)
                            (cello + strings) * 0.36 * chordEnv
                        }
                        InstrumentalCategory.WORSHIP_INSTRUMENTAL -> {
                            // Open-fifth shimmer pad + gentle upper melody bell
                            val pad = sin(2.0 * PI * (chord[0] * 0.5) * t) +
                                0.7 * sin(2.0 * PI * chord[2] * t) +
                                0.45 * sin(2.0 * PI * (chord[0] * 2.0) * t)
                            val bellDt = posInChordSec % 2.25
                            val bellEnv = (1.0 - exp(-bellDt * 50.0)) * exp(-bellDt * 1.2)
                            val bell = bellEnv * 0.35 * sin(2.0 * PI * (chord[3] * 2.0) * t)
                            (pad * 0.24 * chordEnv) + bell
                        }
                    }

                    // Smooth fade-in over FADE_IN_SECONDS
                    val fadeInGain = (t.toFloat() / FADE_IN_SECONDS).coerceIn(0f, 1f)

                    // Smooth fade-out if requested
                    val fadeOutGain = if (fadingOut.get()) {
                        fadeOutProgress += fadeOutStep
                        (1f - fadeOutProgress).coerceIn(0f, 1f)
                    } else {
                        1f
                    }

                    val finalVal = (rawSample * baseGain * fadeInGain * fadeOutGain).coerceIn(-0.95, 0.95)
                    pcm[i] = (finalVal * Short.MAX_VALUE).toInt().toShort()
                    sampleIndex++
                }

                track.write(pcm, 0, CHUNK_FRAMES)

                if (fadingOut.get() && fadeOutProgress >= 1f) {
                    running.set(false)
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            releaseTrack()
            abandonFocus()
        }
    }

    private fun releaseTrack() {
        val t = audioTrack
        audioTrack = null
        if (t != null) {
            try {
                t.pause()
                t.flush()
                t.stop()
            } catch (_: Exception) {
            }
            try {
                t.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        } catch (_: Exception) {
        }
    }
}
