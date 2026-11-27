package com.paulfruitful.screenguard.cooloff

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

enum class VoiceReaderState {
    INITIALIZING,
    SPEAKING,
    PAUSED,
    COMPLETED,
    UNAVAILABLE
}

/**
 * On-device Text-to-Speech scripture reader designed exclusively for the Cool-Off Room.
 *
 * Selects the deepest available offline male English voice, configures a slow meditative cadence
 * and rich bass pitch, splits the Bible verse into natural spoken clauses for true Pause/Resume,
 * and signals speaking state so [CoolOffAudioEngine] can gently duck background music.
 */
class CoolOffScriptureVoiceReader(
    private val appContext: Context,
    private val onStateChanged: (VoiceReaderState) -> Unit,
    private val onSpeakingActiveChanged: (Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "CoolOffVoiceReader"

        // Slow, calm, meditative reading pace
        const val SPEECH_RATE_SLOW = 0.74f

        // Deep bass pitch: warm baritone/bass on native male voices, deeper shift on fallback voices
        const val PITCH_DEEP_MALE = 0.74f
        const val PITCH_FALLBACK_BASS = 0.66f

        private const val AUTO_START_DELAY_MS = 550L
        private const val PAUSE_AFTER_REFERENCE_MS = 450L
        private const val PAUSE_BETWEEN_CLAUSES_MS = 340L

        // Google TTS & common Android English male voice identifiers (ordered deepest/warmest first)
        private val DEEP_MALE_VOICE_TOKENS = listOf(
            "-tpd-", "-iom-", "-gbd-", "-rjs-", "-iol-", "-gbb-",
            "-aud-", "-aub-", "-ene-", "-end-", "-mal-", "baritone", "bass"
        )

        // Known female voice identifiers to avoid when seeking a deep male voice
        private val FEMALE_VOICE_TOKENS = listOf(
            "female", "#female", "-sfg-", "-tpf-", "-tpc-", "-iob-", "-iog-",
            "-gba-", "-gbc-", "-fis-", "-auc-", "-aua-", "-enc-", "-ena-"
        )

        /**
         * Formats Bible references into natural spoken English:
         * e.g. "1 Corinthians 10:13" -> "First Corinthians, chapter 10, verse 13."
         *      "Psalm 119:9–11"      -> "Psalm 119, verses 9 to 11."
         */
        fun formatSpokenReference(reference: String): String {
            val trimmed = reference.trim()
            if (trimmed.isEmpty()) return ""

            // Convert leading ordinal book numbers ("1 ", "2 ", "3 ") to spoken ordinals
            var spoken = trimmed
                .replace(Regex("^1\\s+"), "First ")
                .replace(Regex("^2\\s+"), "Second ")
                .replace(Regex("^3\\s+"), "Third ")

            val refPattern = Regex("""^(.+?)\s+(\d+):(\d+)(?:\s*[–\-]\s*(\d+))?(?:,\s*(\d+))?$""")
            val match = refPattern.matchEntire(spoken)
            if (match != null) {
                val book = match.groupValues[1].trim()
                val chapter = match.groupValues[2]
                val verseStart = match.groupValues[3]
                val verseEnd = match.groupValues[4]
                val verseExtra = match.groupValues[5]

                val isPsalm = book.equals("Psalm", ignoreCase = true) || book.equals("Psalms", ignoreCase = true)
                val chapterPhrase = if (isPsalm) "$book $chapter" else "$book, chapter $chapter"

                spoken = when {
                    verseEnd.isNotEmpty() -> "$chapterPhrase, verses $verseStart to $verseEnd."
                    verseExtra.isNotEmpty() -> "$chapterPhrase, verses $verseStart and $verseExtra."
                    else -> "$chapterPhrase, verse $verseStart."
                }
            } else if (!spoken.endsWith(".")) {
                spoken = "$spoken."
            }
            return spoken
        }

        /**
         * Splits the scripture reference and passage into natural clauses so Pause / Resume can
         * stop cleanly and pick up right where the listener left off.
         */
        fun buildReadingChunks(scripture: CoolOffScripture): List<String> {
            val chunks = mutableListOf<String>()
            val spokenRef = formatSpokenReference(scripture.reference)
            if (spokenRef.isNotBlank()) {
                chunks.add(spokenRef)
            }

            val cleanPassage = scripture.passage
                .replace("“", "")
                .replace("”", "")
                .replace("\"", "")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleanPassage.isEmpty()) return chunks

            // Primary split on sentence / clause punctuation (. ? ! ; :)
            val rawSentences = cleanPassage.split(Regex("(?<=[.!?;:])\\s+"))
            for (sentence in rawSentences) {
                val s = sentence.trim()
                if (s.isEmpty()) continue
                if (s.length <= 95) {
                    chunks.add(s)
                } else {
                    // Break long sentences gently at commas so pause/resume stays responsive
                    val subParts = s.split(Regex("(?<=,)\\s+"))
                    val acc = StringBuilder()
                    for (part in subParts) {
                        if (acc.isNotEmpty() && acc.length + part.length + 1 > 85) {
                            chunks.add(acc.toString().trim())
                            acc.clear()
                        }
                        if (acc.isNotEmpty()) acc.append(' ')
                        acc.append(part)
                    }
                    if (acc.isNotEmpty()) {
                        chunks.add(acc.toString().trim())
                    }
                }
            }
            return chunks
        }

        /**
         * Pure scoring function for selecting the deepest, clearest offline male English voice.
         */
        fun scoreVoiceForDeepMaleBass(
            name: String,
            localeTag: String,
            isNetworkRequired: Boolean,
            quality: Int,
            features: Set<String>
        ): Int {
            val lowerName = name.lowercase(Locale.US)
            val lowerLocale = localeTag.lowercase(Locale.US)
            val lowerFeatures = features.map { it.lowercase(Locale.US) }

            // Must be English
            if (!lowerLocale.startsWith("en")) return Int.MIN_VALUE

            var score = 0

            // Strong preference for known deep male Google TTS / Android voices
            val deepIndex = DEEP_MALE_VOICE_TOKENS.indexOfFirst { token -> lowerName.contains(token) }
            if (deepIndex >= 0) {
                score += 120 - (deepIndex * 4)
            }

            // Explicit male metadata or naming (without matching "female")
            val mentionsFemale = FEMALE_VOICE_TOKENS.any { token -> lowerName.contains(token) } ||
                lowerFeatures.any { it.contains("female") }
            val mentionsMale = (!mentionsFemale && (
                lowerName.contains("male") ||
                    lowerName.contains("_m_") ||
                    lowerName.contains("-m-") ||
                    lowerFeatures.any { it.contains("male") && !it.contains("female") }
                ))

            if (mentionsMale) {
                score += 75
            }
            if (mentionsFemale) {
                score -= 110
            }

            // Prefer 100% offline local voices for instant, reliable Cool-Off playback
            if (!isNetworkRequired) {
                score += 30
            } else {
                score -= 15
            }

            // Prefer high-quality neural/local voices
            if (quality >= Voice.QUALITY_HIGH) {
                score += 18
            } else if (quality >= Voice.QUALITY_NORMAL) {
                score += 8
            }

            // Prefer US / UK English accents for clear scripture pronunciation
            if (lowerLocale == "en-us" || lowerLocale == "en_us") {
                score += 12
            } else if (lowerLocale == "en-gb" || lowerLocale == "en_gb") {
                score += 10
            }

            return score
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    @Volatile
    private var isInitialized = false
    @Volatile
    private var isReleased = false
    @Volatile
    private var state: VoiceReaderState = VoiceReaderState.INITIALIZING

    private var chunks: List<String> = emptyList()
    private var currentChunkIndex: Int = 0
    private var pendingAutoPlay: Boolean = false
    private var playbackGeneration: Int = 0
    private var selectedKnownMaleVoice: Boolean = false

    private val autoStartRunnable = Runnable {
        if (!isReleased && isInitialized && pendingAutoPlay && state != VoiceReaderState.PAUSED) {
            pendingAutoPlay = false
            speakFromChunk(currentChunkIndex)
        }
    }

    fun getState(): VoiceReaderState = state

    fun loadAndStart(scripture: CoolOffScripture, autoPlay: Boolean = true) {
        if (isReleased) return
        chunks = buildReadingChunks(scripture)
        currentChunkIndex = 0
        pendingAutoPlay = autoPlay

        if (chunks.isEmpty()) {
            updateState(VoiceReaderState.UNAVAILABLE)
            return
        }

        if (tts == null) {
            updateState(VoiceReaderState.INITIALIZING)
            try {
                tts = TextToSpeech(appContext) { status ->
                    mainHandler.post { handleInitResult(status) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TextToSpeech init failed: ${e.message}")
                updateState(VoiceReaderState.UNAVAILABLE)
            }
        } else if (isInitialized) {
            playbackGeneration++
            tts?.stop()
            mainHandler.removeCallbacks(autoStartRunnable)
            if (autoPlay) {
                mainHandler.postDelayed(autoStartRunnable, AUTO_START_DELAY_MS)
            } else {
                updateState(VoiceReaderState.PAUSED)
            }
        }
    }

    fun pause() {
        if (isReleased) return
        pendingAutoPlay = false
        mainHandler.removeCallbacks(autoStartRunnable)
        playbackGeneration++
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        onSpeakingActiveChanged(false)
        if (state != VoiceReaderState.UNAVAILABLE) {
            updateState(VoiceReaderState.PAUSED)
        }
    }

    fun resume() {
        if (isReleased || state == VoiceReaderState.UNAVAILABLE) return
        if (!isInitialized) {
            pendingAutoPlay = true
            updateState(VoiceReaderState.INITIALIZING)
            return
        }
        if (currentChunkIndex >= chunks.size) {
            currentChunkIndex = 0
        }
        speakFromChunk(currentChunkIndex)
    }

    fun togglePauseResume() {
        when (state) {
            VoiceReaderState.SPEAKING -> pause()
            VoiceReaderState.PAUSED, VoiceReaderState.COMPLETED -> resume()
            VoiceReaderState.INITIALIZING -> {
                pendingAutoPlay = !pendingAutoPlay
                if (!pendingAutoPlay) {
                    updateState(VoiceReaderState.PAUSED)
                }
            }
            VoiceReaderState.UNAVAILABLE -> Unit
        }
    }

    fun stopAndRelease() {
        isReleased = true
        pendingAutoPlay = false
        playbackGeneration++
        mainHandler.removeCallbacks(autoStartRunnable)
        onSpeakingActiveChanged(false)
        val engine = tts
        tts = null
        isInitialized = false
        if (engine != null) {
            try {
                engine.setOnUtteranceProgressListener(null)
                engine.stop()
                engine.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleInitResult(status: Int) {
        if (isReleased) {
            stopAndRelease()
            return
        }
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.w(TAG, "TTS initialization returned status=$status")
            updateState(VoiceReaderState.UNAVAILABLE)
            return
        }

        configureDeepBassMaleVoice(engine)
        installProgressListener(engine)
        isInitialized = true

        if (pendingAutoPlay && chunks.isNotEmpty()) {
            mainHandler.removeCallbacks(autoStartRunnable)
            mainHandler.postDelayed(autoStartRunnable, AUTO_START_DELAY_MS)
        } else {
            updateState(VoiceReaderState.PAUSED)
        }
    }

    private fun configureDeepBassMaleVoice(engine: TextToSpeech) {
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Exception) {
        }

        selectedKnownMaleVoice = false
        var chosenVoice: Voice? = null
        var bestScore = Int.MIN_VALUE

        try {
            val availableVoices = engine.voices
            if (!availableVoices.isNullOrEmpty()) {
                for (voice in availableVoices) {
                    val localeTag = voice.locale?.toLanguageTag().orEmpty()
                    val features = voice.features ?: emptySet()
                    val score = scoreVoiceForDeepMaleBass(
                        name = voice.name.orEmpty(),
                        localeTag = localeTag,
                        isNetworkRequired = voice.isNetworkConnectionRequired,
                        quality = voice.quality,
                        features = features
                    )
                    if (score > bestScore) {
                        bestScore = score
                        chosenVoice = voice
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enumerate TTS voices: ${e.message}")
        }

        if (chosenVoice != null && bestScore > 0) {
            try {
                val res = engine.setVoice(chosenVoice)
                if (res == TextToSpeech.SUCCESS) {
                    selectedKnownMaleVoice = bestScore >= 90
                    Log.d(TAG, "Selected deep male TTS voice: ${chosenVoice.name} (score=$bestScore)")
                } else {
                    engine.language = Locale.US
                }
            } catch (_: Exception) {
                engine.language = Locale.US
            }
        } else {
            try {
                val usResult = engine.setLanguage(Locale.US)
                if (usResult == TextToSpeech.LANG_MISSING_DATA || usResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.language = Locale.ENGLISH
                }
            } catch (_: Exception) {
            }
        }

        // Apply slow meditative speed and deep bass register
        engine.setSpeechRate(SPEECH_RATE_SLOW)
        engine.setPitch(if (selectedKnownMaleVoice) PITCH_DEEP_MALE else PITCH_FALLBACK_BASS)
    }

    private fun installProgressListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val parsed = parseUtteranceId(utteranceId) ?: return
                mainHandler.post {
                    if (isReleased || parsed.first != playbackGeneration) return@post
                    currentChunkIndex = parsed.second
                    if (state != VoiceReaderState.SPEAKING) {
                        updateState(VoiceReaderState.SPEAKING)
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                val parsed = parseUtteranceId(utteranceId) ?: return
                mainHandler.post {
                    if (isReleased || parsed.first != playbackGeneration) return@post
                    val finishedIdx = parsed.second
                    if (finishedIdx >= chunks.lastIndex) {
                        currentChunkIndex = 0
                        onSpeakingActiveChanged(false)
                        updateState(VoiceReaderState.COMPLETED)
                    } else {
                        currentChunkIndex = finishedIdx + 1
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleError(utteranceId)
            }

            private fun handleError(utteranceId: String?) {
                val parsed = parseUtteranceId(utteranceId)
                mainHandler.post {
                    if (isReleased) return@post
                    if (parsed != null && parsed.first != playbackGeneration) return@post
                    onSpeakingActiveChanged(false)
                    updateState(VoiceReaderState.PAUSED)
                }
            }
        })
    }

    private fun speakFromChunk(startIndex: Int) {
        val engine = tts ?: return
        if (chunks.isEmpty()) return

        val safeStart = startIndex.coerceIn(0, chunks.lastIndex)
        currentChunkIndex = safeStart
        playbackGeneration++
        val gen = playbackGeneration

        try {
            engine.stop()
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }

            for (idx in safeStart until chunks.size) {
                val queueMode = if (idx == safeStart) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(chunks[idx], queueMode, params, "sg_v_${gen}_$idx")
                if (idx < chunks.lastIndex) {
                    val silenceMs = if (idx == 0) PAUSE_AFTER_REFERENCE_MS else PAUSE_BETWEEN_CLAUSES_MS
                    engine.playSilentUtterance(silenceMs, TextToSpeech.QUEUE_ADD, "sg_s_${gen}_$idx")
                }
            }
            onSpeakingActiveChanged(true)
            updateState(VoiceReaderState.SPEAKING)
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed: ${e.message}")
            onSpeakingActiveChanged(false)
            updateState(VoiceReaderState.PAUSED)
        }
    }

    private fun parseUtteranceId(utteranceId: String?): Pair<Int, Int>? {
        if (utteranceId == null || !utteranceId.startsWith("sg_v_")) return null
        val parts = utteranceId.removePrefix("sg_v_").split("_")
        if (parts.size != 2) return null
        val gen = parts[0].toIntOrNull() ?: return null
        val idx = parts[1].toIntOrNull() ?: return null
        return gen to idx
    }

    private fun updateState(newState: VoiceReaderState) {
        state = newState
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStateChanged(newState)
        } else {
            mainHandler.post {
                if (!isReleased) onStateChanged(newState)
            }
        }
    }
}
