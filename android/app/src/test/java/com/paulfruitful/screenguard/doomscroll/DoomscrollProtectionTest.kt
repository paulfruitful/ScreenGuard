package com.paulfruitful.screenguard.doomscroll

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoomscrollProtectionTest {

    private val enabledConfig = DoomscrollConfig(enabled = true)
    private val detector = FeedDetector()

    @Test
    fun defaultConfig_isOptionalDisabledByDefault_andEnforcesPrivacyInvariants() {
        val defaults = DoomscrollConfig()
        assertFalse("Doomscroll protection must be opt-in (disabled by default)", defaults.enabled)
        assertTrue(defaults.localProcessingOnly)
        assertFalse(defaults.storeScreenshots)
        assertFalse(defaults.uploadBehaviouralData)
        assertEquals(10, defaults.safeIntendedDurationMinutes)
        assertEquals(60, defaults.meaningfulBreakSeconds)

        val clamped = defaults.copy(
            intendedDurationMinutes = 999,
            storeScreenshots = true,
            uploadBehaviouralData = true
        ).validated()
        assertEquals(180, clamped.intendedDurationMinutes)
        assertFalse(clamped.storeScreenshots)
        assertFalse(clamped.uploadBehaviouralData)
    }

    @Test
    fun adapters_distinguishShortFormFeedsFromDMsProfilesCommentsAndLongFormAcrossAllFourApps() {
        // 1. TikTok: For You feed vs. DMs / Comments / Profile / Unknown updated UI
        val ttFeed = detector.detect(
            FeedObservationSnapshot(
                packageName = "com.zhiliaoapp.musically",
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                className = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager",
                contentDescriptions = listOf("For You", "Like", "Comment", "Share", "@creator1"),
                scrollDeltaY = 850,
                fromIndex = 2,
                toIndex = 3
            ),
            enabledConfig
        )
        assertTrue(ttFeed.isConfirmedShortFormFeed(enabledConfig.safeMinFeedConfidence))

        val ttDm = detector.detect(
            FeedObservationSnapshot(
                packageName = "com.zhiliaoapp.musically",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                texts = listOf("Direct message", "Send a message")
            ),
            enabledConfig
        )
        assertEquals(FeedContextType.DIRECT_MESSAGES, ttDm.contextType)

        val ttComments = detector.detect(
            FeedObservationSnapshot(
                packageName = "com.zhiliaoapp.musically",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                texts = listOf("Add comment", "View more replies")
            ),
            enabledConfig
        )
        assertEquals(FeedContextType.COMMENTS, ttComments.contextType)

        // 2. Instagram: Reels vs. DMs / Stories / Ordinary Home Feed
        val igReels = detector.detect(
            FeedObservationSnapshot(
                packageName = "com.instagram.android",
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                className = "androidx.viewpager2.widget.ViewPager2",
                viewIdResourceNames = listOf("clips_viewer_view_pager"),
                contentDescriptions = listOf("Reels", "Original audio", "Like", "Comment", "Share"),
                scrollDeltaY = 920
            ),
            enabledConfig
        )
        assertTrue(igReels.isConfirmedShortFormFeed(enabledConfig.safeMinFeedConfidence))

        val igDm = detector.detect(
            FeedObservationSnapshot(
                packageName = "com.instagram.android",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                viewIdResourceNames = listOf("direct_thread_recycler_view"),
                texts = listOf("Message...", "Active now")
            ),
            enabledConfig
        )
        assertEquals(FeedContextType.DIRECT_MESSAGES, igDm.contextType)

        val igHome = detector.detect(
            FeedObservationSnapshot(
                packageName = "com.instagram.android",
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                texts = listOf("Your story", "Suggested for you"),
                viewIdResourceNames = listOf("feed_timeline")
            ),
            enabledConfig
        )
        assertEquals(FeedContextType.ORDINARY_TIMELINE, igHome.contextType)

        // 3. Facebook: Reels vs. News Feed / Long-Form Watch Video
        val fbReels = detector.detect(
            FeedObservationSnapshot(
                packageName = "com.facebook.katana",
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                viewIdResourceNames = listOf("fb_reels_viewer_container"),
                texts = listOf("Reels", "Original audio", "Like", "Comment", "Share"),
                scrollDeltaY = 780
            ),
            enabledConfig
        )
        assertTrue(fbReels.isConfirmedShortFormFeed(enabledConfig.safeMinFeedConfidence))

        val fbLongVideo = detector.detect(
            FeedObservationSnapshot(
                packageName = "com.facebook.katana",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                viewIdResourceNames = listOf("fb_reels_viewer_container"),
                texts = listOf("14:22", "Like", "Comment")
            ),
            enabledConfig
        )
        assertEquals(FeedContextType.LONG_FORM_VIDEO, fbLongVideo.contextType)

        // 4. X (Twitter): Immersive Video Feed vs. Reading Posts vs. Long Video
        val xVideoFeed = detector.detect(
            FeedObservationSnapshot(
                packageName = "com.twitter.android",
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                viewIdResourceNames = listOf("immersive_video_player"),
                texts = listOf("-0:28", "1x", "Follow", "Repost", "Like"),
                scrollDeltaY = 800
            ),
            enabledConfig
        )
        assertTrue(xVideoFeed.isConfirmedShortFormFeed(enabledConfig.safeMinFeedConfidence))

        val xTimeline = detector.detect(
            FeedObservationSnapshot(
                packageName = "com.twitter.android",
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                viewIdResourceNames = listOf("home_timeline"),
                texts = listOf("Show more posts", "New post")
            ),
            enabledConfig
        )
        assertEquals(FeedContextType.ORDINARY_TIMELINE, xTimeline.contextType)

        val xLongVideo = detector.detect(
            FeedObservationSnapshot(
                packageName = "com.twitter.android",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                viewIdResourceNames = listOf("immersive_video_player"),
                texts = listOf("-18:05", "1x", "Follow")
            ),
            enabledConfig
        )
        assertEquals(FeedContextType.LONG_FORM_VIDEO, xLongVideo.contextType)
    }

    @Test
    fun scenarioA_normalUsageThreeVideos_producesNoIntervention() {
        val tracker = BehaviourTracker()
        val intervention = DoomscrollInterventionManager()
        var now = 1_000_000L

        for (i in 1..3) {
            val obs = makeTikTokVideoObservation(index = i, creator = "@creator_$i", timestampMs = now)
            val cls = detector.detect(obs, enabledConfig)
            tracker.onFeedClassification(cls, obs, enabledConfig)
            now += 15_000L
        }
        val snap = tracker.snapshot(now, enabledConfig)
        val breakdown = DoomscrollDetectionEngine.evaluate(snap, enabledConfig)
        val decision = intervention.evaluate(snap, breakdown, enabledConfig, now, false)

        assertEquals(RiskLevel.NORMAL, breakdown.riskLevel)
        assertFalse("Watching 3 videos should never trigger intervention", decision.shouldIntervene)
    }

    @Test
    fun scenarioB_extendedConsumption18Minutes_escalatesToHighAndCritical() {
        val tracker = BehaviourTracker()
        val intervention = DoomscrollInterventionManager()
        var now = 2_000_000L
        var sawModerate = false
        var sawHighOrCritical = false

        // Simulate 18 minutes of continuous short-form TikTok scrolling (1 video every ~6s, ~180 transitions)
        for (i in 1..180) {
            val obs = makeTikTokVideoObservation(index = i, creator = "@clip_$i", timestampMs = now)
            val cls = detector.detect(obs, enabledConfig)
            tracker.onFeedClassification(cls, obs, enabledConfig)
            val snap = tracker.snapshot(now, enabledConfig)
            val breakdown = DoomscrollDetectionEngine.evaluate(snap, enabledConfig)
            val decision = intervention.evaluate(snap, breakdown, enabledConfig, now, false)
            if (decision.shouldIntervene && decision.level == DoomscrollInterventionLevel.GENTLE_REMINDER) {
                sawModerate = true
            }
            if (decision.shouldIntervene &&
                (decision.level == DoomscrollInterventionLevel.STRONG_INTERVENTION ||
                    decision.level == DoomscrollInterventionLevel.COOL_OFF_CRITICAL)
            ) {
                sawHighOrCritical = true
            }
            now += 6_000L
        }

        val finalSnap = tracker.snapshot(now, enabledConfig)
        val finalBreakdown = DoomscrollDetectionEngine.evaluate(finalSnap, enabledConfig)

        assertTrue("Active duration should be ~18m", finalSnap.activeFeedDurationMs >= 16 * 60_000L)
        assertTrue("Final risk score should be High or Critical (got ${finalBreakdown.totalScore})", finalBreakdown.totalScore >= 70)
        assertTrue("Should fire Gentle Reminder along the ramp", sawModerate)
        assertTrue("Should escalate to Strong or Critical intervention", sawHighOrCritical)
    }

    @Test
    fun scenarioC_rapidScrollingBriefly_raisesSignalsWithoutHardBlock() {
        val tracker = BehaviourTracker()
        val intervention = DoomscrollInterventionManager()
        var now = 3_000_000L

        // Rapidly skip 14 videos in 42 seconds (3 seconds each)
        for (i in 1..14) {
            val obs = makeTikTokVideoObservation(index = i, creator = "@rapid_$i", timestampMs = now)
            val cls = detector.detect(obs, enabledConfig)
            tracker.onFeedClassification(cls, obs, enabledConfig)
            now += 3_000L
        }

        val snap = tracker.snapshot(now, enabledConfig)
        val breakdown = DoomscrollDetectionEngine.evaluate(snap, enabledConfig)
        val decision = intervention.evaluate(snap, breakdown, enabledConfig, now, false)

        assertTrue("Transitions should be recorded", snap.videoTransitionCount >= 10)
        assertTrue("Short dwell points should accumulate", breakdown.shortDwellPoints > 0f)
        assertTrue("Rapid scrolling alone (<2m) must stay <= LOW (<=49), got ${breakdown.totalScore}", breakdown.totalScore <= 49)
        assertFalse("Rapid scrolling alone must not trigger a hard block", decision.shouldIntervene)
    }

    @Test
    fun scenarioD_repeatedReopeningInstagramReels_contributesToRiskScore() {
        val tracker = BehaviourTracker()
        var now = 4_000_000L

        // 4 separate Reels sessions within 30 minutes
        for (sessionIdx in 1..4) {
            for (v in 1..6) {
                val obs = FeedObservationSnapshot(
                    packageName = "com.instagram.android",
                    eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                    className = "androidx.viewpager2.widget.ViewPager2",
                    viewIdResourceNames = listOf("clips_viewer_view_pager"),
                    contentDescriptions = listOf("Reels", "Original audio", "Like", "Comment", "@ig_${sessionIdx}_$v"),
                    scrollDeltaY = 820,
                    fromIndex = v,
                    toIndex = v + 1,
                    timestampMs = now
                )
                val cls = detector.detect(obs, enabledConfig)
                tracker.onFeedClassification(cls, obs, enabledConfig)
                now += 10_000L
            }
            if (sessionIdx < 4) {
                tracker.onForegroundAppChanged("com.android.launcher", now, enabledConfig)
                now += 75_000L // >60s gap creates a separate reopened session
                tracker.onTick(now, enabledConfig)
            }
        }

        val snap = tracker.snapshot(now, enabledConfig)
        val breakdown = DoomscrollDetectionEngine.evaluate(snap, enabledConfig)
        assertTrue("Expected >= 4 reopens in rolling window, got ${snap.reopeningCountInWindow}", snap.reopeningCountInWindow >= 4)
        assertTrue("Reopening signal must contribute significant points (${breakdown.reopeningPoints})", breakdown.reopeningPoints >= 10f)
    }

    @Test
    fun scenarioE_intentionalLongTutorialVideo_neverFalseTriggers() {
        // Watching a single 20-minute tutorial video (0–1 transitions)
        val longSingleVideoSnap = BehaviourSnapshot(
            sessionId = "tutorial-1",
            app = SupportedSocialApp.X,
            applicationPackage = "com.twitter.android",
            isInConfirmedFeed = true,
            feedConfidence = 0.75f,
            feedContextType = FeedContextType.SHORT_FORM_FEED,
            activeFeedDurationMs = 20 * 60_000L,
            videoTransitionCount = 1,
            intendedDurationMinutes = 10
        )
        val breakdown = DoomscrollDetectionEngine.evaluate(longSingleVideoSnap, enabledConfig)
        assertEquals(RiskLevel.NORMAL, breakdown.riskLevel)
        assertEquals("insufficient_video_transitions", breakdown.cappedReason)
    }

    @Test
    fun scenarioF_ordinarySocialMediaUsage_recordsZeroFeedTime() {
        val tracker = BehaviourTracker()
        var now = 5_000_000L

        for (i in 1..20) {
            val dmObs = FeedObservationSnapshot(
                packageName = "com.instagram.android",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                viewIdResourceNames = listOf("direct_thread_recycler_view"),
                texts = listOf("Message...", "Chat line $i"),
                timestampMs = now
            )
            val cls = detector.detect(dmObs, enabledConfig)
            tracker.onFeedClassification(cls, dmObs, enabledConfig)
            now += 15_000L
        }

        val snap = tracker.snapshot(now, enabledConfig)
        assertEquals(0L, snap.activeFeedDurationMs)
        assertEquals(0, snap.videoTransitionCount)
        assertEquals(0, DoomscrollDetectionEngine.evaluate(snap, enabledConfig).totalScore)
    }

    @Test
    fun scenarioG_feedIdentificationFailure_pausesScoringWithoutFabricatingEvents() {
        val tracker = BehaviourTracker()
        var now = 6_000_000L

        // Start in valid TikTok feed for 30s
        for (i in 1..4) {
            val obs = makeTikTokVideoObservation(i, "@valid_$i", now)
            tracker.onFeedClassification(detector.detect(obs, enabledConfig), obs, enabledConfig)
            now += 8_000L
        }
        val activeBeforeUnknown = tracker.snapshot(now, enabledConfig).activeFeedDurationMs
        val transitionsBeforeUnknown = tracker.snapshot(now, enabledConfig).videoTransitionCount

        // UI suddenly changes to unknown/unrecognized layout for 40s
        for (i in 1..8) {
            val unknownObs = FeedObservationSnapshot(
                packageName = "com.zhiliaoapp.musically",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                className = "com.tiktok.newui.OpaqueCustomSurface",
                texts = listOf("Random custom text $i"),
                timestampMs = now
            )
            val cls = detector.detect(unknownObs, enabledConfig)
            assertEquals(FeedContextType.UNKNOWN, cls.contextType)
            tracker.onFeedClassification(cls, unknownObs, enabledConfig)
            now += 5_000L
        }

        val afterUnknown = tracker.snapshot(now, enabledConfig)
        assertEquals("Active feed duration must pause during UNKNOWN UI", activeBeforeUnknown, afterUnknown.activeFeedDurationMs)
        assertEquals("No transitions may be fabricated during UNKNOWN UI", transitionsBeforeUnknown, afterUnknown.videoTransitionCount)
    }

    @Test
    fun scenarioH_coolOffCompletionResetsRisk_andDuplicateSuppressionAndVisualFallbackWork() {
        val tracker = BehaviourTracker()
        val intervention = DoomscrollInterventionManager()
        var now = 7_000_000L

        // Duplicate events for same video within 500ms must be suppressed
        val firstObs = makeTikTokVideoObservation(1, "@same_creator", now)
        tracker.onFeedClassification(detector.detect(firstObs, enabledConfig), firstObs, enabledConfig)
        val dupObs = makeTikTokVideoObservation(1, "@same_creator", now + 400L)
        tracker.onFeedClassification(detector.detect(dupObs, enabledConfig), dupObs, enabledConfig)
        assertEquals(0, tracker.snapshot(now + 400L, enabledConfig).videoTransitionCount)

        // Visual frame hash fallback detects transition when accessibility scroll events are absent
        now += 3_000L
        tracker.onVisualFrameHash("com.zhiliaoapp.musically", 0x00000000000000FFL, now, enabledConfig)
        now += 4_000L
        tracker.onVisualFrameHash("com.zhiliaoapp.musically", 0x7FFF0000FFFF0000L, now, enabledConfig)
        assertEquals(1, tracker.snapshot(now, enabledConfig).videoTransitionCount)

        // Ramp into critical risk (~16 min unbroken marathon), then complete Cool-Off session
        for (i in 2..195) {
            now += 5_000L
            val obs = makeTikTokVideoObservation(i, "@vid_$i", now)
            tracker.onFeedClassification(detector.detect(obs, enabledConfig), obs, enabledConfig)
        }
        val criticalSnap = tracker.snapshot(now, enabledConfig)
        val criticalBreakdown = DoomscrollDetectionEngine.evaluate(criticalSnap, enabledConfig)
        assertTrue("Expected critical risk before Cool-Off, got ${criticalBreakdown.totalScore}", criticalBreakdown.totalScore >= 85)

        val closed = tracker.onCoolOffCompleted(now, enabledConfig)
        intervention.onCoolOffCompleted(now)
        assertNotNull(closed)
        assertEquals(SessionCompletionStatus.ENDED_BY_COOL_OFF, closed?.completionStatus)

        val afterCoolOff = tracker.snapshot(now + 1000L, enabledConfig)
        assertEquals(0, DoomscrollDetectionEngine.evaluate(afterCoolOff, enabledConfig).totalScore)
    }

    @Test
    fun strictTimeLimit_triggersCriticalOnlyWhenExplicitlyEnabled_andRespectsOverlayLock() {
        val strictCfg = enabledConfig.copy(intendedDurationMinutes = 5, strictTimeLimitEnabled = true)
        val snap = BehaviourSnapshot(
            sessionId = "strict-1",
            app = SupportedSocialApp.TIKTOK,
            applicationPackage = "com.zhiliaoapp.musically",
            isInConfirmedFeed = true,
            activeFeedDurationMs = 5 * 60_000L + 1000L,
            videoTransitionCount = 5,
            intendedDurationMinutes = 5,
            strictTimeLimitExceeded = true
        )
        val breakdown = DoomscrollDetectionEngine.evaluate(snap, strictCfg)
        assertEquals(RiskLevel.CRITICAL, breakdown.riskLevel)

        val mgr = DoomscrollInterventionManager()
        // When another overlay (e.g. visual porn shield) is already visible, never fight it
        val blockedByVisual = mgr.evaluate(snap, breakdown, strictCfg, 100_000L, isOverlayCurrentlyVisible = true)
        assertFalse(blockedByVisual.shouldIntervene)
        assertEquals("overlay_already_visible", blockedByVisual.reason)

        // When no overlay is visible, fire Level 3 Cool-Off
        val fired = mgr.evaluate(snap, breakdown, strictCfg, 100_000L, isOverlayCurrentlyVisible = false)
        assertTrue(fired.shouldIntervene)
        assertEquals(DoomscrollInterventionLevel.COOL_OFF_CRITICAL, fired.level)
    }

    private fun makeTikTokVideoObservation(
        index: Int,
        creator: String,
        timestampMs: Long
    ): FeedObservationSnapshot {
        return FeedObservationSnapshot(
            packageName = "com.zhiliaoapp.musically",
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            className = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager",
            contentDescriptions = listOf("For You", "Like", "Comment", "Share", creator),
            scrollDeltaY = 900,
            fromIndex = index,
            toIndex = index + 1,
            timestampMs = timestampMs
        )
    }
}
