package com.paulfruitful.screenguard.cooloff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Cool-Off Room rolling-window escalation, exercise library,
 * offline scripture library, and completion validation.
 */
class CoolOffRoomTest {

    @Test
    fun rollingWindow_escalatesOnThirdActivationWithinThreeMinutes() {
        val evaluator = RollingTriggerWindowEvaluator(minDedupeIntervalMs = 4_000L)
        val windowMs = 3 * 60_000L

        // Activation 1 -> Normal shield
        assertTrue(evaluator.recordActivation(nowMs = 10_000L, threshold = 3, windowMs = windowMs))
        assertFalse(evaluator.isEscalated())

        // Activation 2 (60s later) -> Normal shield
        assertTrue(evaluator.recordActivation(nowMs = 70_000L, threshold = 3, windowMs = windowMs))
        assertFalse(evaluator.isEscalated())

        // Activation 3 (120s after first) -> Escalated shield!
        assertTrue(evaluator.recordActivation(nowMs = 130_000L, threshold = 3, windowMs = windowMs))
        assertTrue(evaluator.isEscalated())
    }

    @Test
    fun rollingWindow_doesNotEscalateWhenSpreadOutsideWindow() {
        val evaluator = RollingTriggerWindowEvaluator(minDedupeIntervalMs = 4_000L)
        val windowMs = 3 * 60_000L

        assertTrue(evaluator.recordActivation(nowMs = 10_000L, threshold = 3, windowMs = windowMs))
        assertTrue(evaluator.recordActivation(nowMs = 130_000L, threshold = 3, windowMs = windowMs))
        // 3rd activation arrives 3m10s after the 1st -> 1st is pruned, only 2 remain in window
        assertTrue(evaluator.recordActivation(nowMs = 200_000L, threshold = 3, windowMs = windowMs))
        assertFalse(evaluator.isEscalated())
        assertEquals(2, evaluator.getRecentTimestamps(200_000L, windowMs).size)
    }

    @Test
    fun rollingWindow_deduplicatesRapidBurstEvents() {
        val evaluator = RollingTriggerWindowEvaluator(minDedupeIntervalMs = 4_000L)
        val windowMs = 3 * 60_000L

        assertTrue(evaluator.recordActivation(nowMs = 10_000L, threshold = 3, windowMs = windowMs))
        assertFalse(evaluator.recordActivation(nowMs = 11_000L, threshold = 3, windowMs = windowMs))
        assertFalse(evaluator.recordActivation(nowMs = 12_500L, threshold = 3, windowMs = windowMs))
        assertFalse(evaluator.isEscalated())
        assertEquals(1, evaluator.getRecentTimestamps(13_000L, windowMs).size)
    }

    @Test
    fun escalation_persistsUntilExplicitSessionCompletion() {
        val evaluator = RollingTriggerWindowEvaluator(minDedupeIntervalMs = 4_000L)
        val windowMs = 3 * 60_000L

        evaluator.recordActivation(10_000L, 2, windowMs)
        evaluator.recordActivation(20_000L, 2, windowMs)
        assertTrue(evaluator.isEscalated())

        // Even 10 minutes later (all timestamps pruned), escalation MUST remain active until completed
        evaluator.prune(nowMs = 620_000L, windowMs = windowMs)
        assertTrue("Escalation must survive until Cool-Off session completes", evaluator.isEscalated())

        // Only clearing after validated completion resets escalation
        evaluator.clearEscalationAndReset()
        assertFalse(evaluator.isEscalated())
    }

    @Test
    fun exerciseLibrary_containsAllSixRequiredExercisesAndValidatesCompletion() {
        assertTrue("Expected at least 6 psychological exercises", CoolOffDrillEngine.EXERCISES.size >= 6)
        val ids = CoolOffDrillEngine.EXERCISES.map { it.id }.toSet()
        assertTrue(ids.contains("urge_surfing"))
        assertTrue(ids.contains("guided_breathing"))
        assertTrue(ids.contains("thought_reframing"))
        assertTrue(ids.contains("trigger_reflection"))
        assertTrue(ids.contains("future_self_visualisation"))
        assertTrue(ids.contains("scripture_meditation"))

        // Timed exercise rejects early exit, accepts full timer
        val urgeSurfing = CoolOffDrillEngine.findById("urge_surfing")
        assertFalse(CoolOffDrillEngine.isCompletionValid(urgeSurfing, elapsedSec = 10, totalSec = 180, completedInteractions = 0))
        assertTrue(CoolOffDrillEngine.isCompletionValid(urgeSurfing, elapsedSec = 180, totalSec = 180, completedInteractions = 0))

        // Interactive exercise requires BOTH timer AND interactions
        val reframing = CoolOffDrillEngine.findById("thought_reframing")
        assertFalse(CoolOffDrillEngine.isCompletionValid(reframing, elapsedSec = 180, totalSec = 180, completedInteractions = 1))
        assertFalse(CoolOffDrillEngine.isCompletionValid(reframing, elapsedSec = 60, totalSec = 180, completedInteractions = 3))
        assertTrue(CoolOffDrillEngine.isCompletionValid(reframing, elapsedSec = 180, totalSec = 180, completedInteractions = 3))
    }

    @Test
    fun scriptureLibrary_containsAllEightRequiredPassagesOffline() {
        val refs = CoolOffScriptureRepository.ALL_SCRIPTURES.map { it.reference }
        val expected = listOf(
            "1 Corinthians 10:13",
            "Galatians 5:22–23",
            "2 Timothy 1:7",
            "Philippians 4:8",
            "Psalm 119:9–11",
            "Romans 12:2",
            "James 1:19–20",
            "Matthew 26:41"
        )
        for (ref in expected) {
            assertTrue("Missing scripture $ref", refs.contains(ref))
        }
        val selected = CoolOffScriptureRepository.selectForTheme("thought_reframing", seed = 0)
        assertTrue(selected.passage.isNotBlank())
        assertTrue(selected.reflectionPrompt.isNotBlank())
        assertEquals(CoolOffScriptureRepository.TRANSLATION_WEB, selected.translation)
    }
}
