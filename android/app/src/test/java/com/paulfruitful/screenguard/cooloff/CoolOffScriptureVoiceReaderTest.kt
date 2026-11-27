package com.paulfruitful.screenguard.cooloff

import android.speech.tts.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoolOffScriptureVoiceReaderTest {

    @Test
    fun formatSpokenReferenceHandlesBooksOrdinalsAndRanges() {
        assertEquals(
            "First Corinthians, chapter 10, verse 13.",
            CoolOffScriptureVoiceReader.formatSpokenReference("1 Corinthians 10:13")
        )
        assertEquals(
            "Galatians, chapter 5, verses 22 to 23.",
            CoolOffScriptureVoiceReader.formatSpokenReference("Galatians 5:22–23")
        )
        assertEquals(
            "Second Timothy, chapter 1, verse 7.",
            CoolOffScriptureVoiceReader.formatSpokenReference("2 Timothy 1:7")
        )
        assertEquals(
            "Psalm 119, verses 9 to 11.",
            CoolOffScriptureVoiceReader.formatSpokenReference("Psalm 119:9–11")
        )
        assertEquals(
            "Matthew, chapter 26, verse 41.",
            CoolOffScriptureVoiceReader.formatSpokenReference("Matthew 26:41")
        )
    }

    @Test
    fun buildReadingChunksSplitsAllCoolOffScripturesForPauseResume() {
        for (scripture in CoolOffScriptureRepository.ALL_SCRIPTURES) {
            val chunks = CoolOffScriptureVoiceReader.buildReadingChunks(scripture)
            assertTrue("Expected reference + passage chunks for ${scripture.reference}", chunks.size >= 2)
            assertTrue("Chunks should not contain blank strings", chunks.all { it.isNotBlank() })
        }
    }

    @Test
    fun deepOfflineMaleVoiceOutscoresFemaleAndNetworkVoices() {
        val deepOfflineMale = CoolOffScriptureVoiceReader.scoreVoiceForDeepMaleBass(
            name = "en-us-x-tpd-local",
            localeTag = "en-US",
            isNetworkRequired = false,
            quality = Voice.QUALITY_HIGH,
            features = emptySet()
        )
        val deepNetworkMale = CoolOffScriptureVoiceReader.scoreVoiceForDeepMaleBass(
            name = "en-us-x-tpd-network",
            localeTag = "en-US",
            isNetworkRequired = true,
            quality = Voice.QUALITY_HIGH,
            features = emptySet()
        )
        val offlineFemale = CoolOffScriptureVoiceReader.scoreVoiceForDeepMaleBass(
            name = "en-us-x-sfg-local",
            localeTag = "en-US",
            isNetworkRequired = false,
            quality = Voice.QUALITY_HIGH,
            features = setOf("female")
        )
        val nonEnglishMale = CoolOffScriptureVoiceReader.scoreVoiceForDeepMaleBass(
            name = "fr-fr-x-frd-local",
            localeTag = "fr-FR",
            isNetworkRequired = false,
            quality = Voice.QUALITY_HIGH,
            features = setOf("male")
        )

        assertTrue("Offline deep male should beat network male", deepOfflineMale > deepNetworkMale)
        assertTrue("Offline deep male should beat offline female", deepOfflineMale > offlineFemale)
        assertEquals("Non-English voice should be excluded", Int.MIN_VALUE, nonEnglishMale)
    }
}
