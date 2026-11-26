package com.paulfruitful.screenguard.doomscroll

enum class SupportedSocialApp(
    val displayName: String,
    val packages: Set<String>
) {
    TIKTOK(
        displayName = "TikTok",
        packages = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go"
        )
    ),
    INSTAGRAM(
        displayName = "Instagram",
        packages = setOf(
            "com.instagram.android",
            "com.instagram.lite"
        )
    ),
    FACEBOOK(
        displayName = "Facebook",
        packages = setOf(
            "com.facebook.katana",
            "com.facebook.lite"
        )
    ),
    X(
        displayName = "X (Twitter)",
        packages = setOf(
            "com.twitter.android",
            "com.twitter.android.lite"
        )
    ),
    UNSUPPORTED(
        displayName = "Other",
        packages = emptySet()
    );

    companion object {
        private val packageLookup: Map<String, SupportedSocialApp> = buildMap {
            for (app in SupportedSocialApp.entries) {
                for (pkg in app.packages) {
                    put(pkg, app)
                }
            }
        }

        fun fromPackage(packageName: String?): SupportedSocialApp {
            if (packageName.isNullOrBlank()) return UNSUPPORTED
            return packageLookup[packageName.lowercase()] ?: UNSUPPORTED
        }
    }
}

enum class FeedContextType {
    SHORT_FORM_FEED,
    ORDINARY_TIMELINE,
    DIRECT_MESSAGES,
    SEARCH,
    PROFILE,
    COMMENTS,
    INDIVIDUAL_VIDEO,
    LONG_FORM_VIDEO,
    UNKNOWN
}

enum class RiskLevel {
    NORMAL,
    LOW,
    MODERATE,
    HIGH,
    CRITICAL
}

enum class DoomscrollInterventionLevel {
    NONE,
    GENTLE_REMINDER,
    STRONG_INTERVENTION,
    COOL_OFF_CRITICAL
}

enum class SessionCompletionStatus {
    ACTIVE,
    ENDED_NATURALLY,
    ENDED_BY_BREAK,
    ENDED_BY_COOL_OFF,
    RECOVERED
}

/**
 * Lightweight observation snapshot gathered from inline AccessibilityEvent fields (and optional
 * bounded node inspection or existing 64-bit perceptual frame hash). Contains zero raw images
 * and is never persisted.
 */
data class FeedObservationSnapshot(
    val packageName: String,
    val eventType: Int,
    val className: String = "",
    val contentDescriptions: List<String> = emptyList(),
    val texts: List<String> = emptyList(),
    val viewIdResourceNames: List<String> = emptyList(),
    val isScrollable: Boolean = false,
    val scrollDeltaY: Int = 0,
    val scrollDeltaX: Int = 0,
    val fromIndex: Int = -1,
    val toIndex: Int = -1,
    val itemCount: Int = -1,
    val timestampMs: Long = System.currentTimeMillis(),
    val visualFrameHash: Long? = null
)

data class FeedClassification(
    val app: SupportedSocialApp,
    val contextType: FeedContextType,
    val confidence: Float,
    val adapterName: String,
    val contentSignature: String = "",
    val pagerIndex: Int = -1,
    val isVerticalScrollSignal: Boolean = false,
    val diagnosticReason: String = ""
) {
    fun isConfirmedShortFormFeed(minConfidence: Float): Boolean {
        return contextType == FeedContextType.SHORT_FORM_FEED && confidence >= minConfidence
    }
}

data class SignalBreakdown(
    val durationPoints: Float = 0f,
    val transitionPoints: Float = 0f,
    val shortDwellPoints: Float = 0f,
    val scrollIntensityPoints: Float = 0f,
    val reopeningPoints: Float = 0f,
    val intendedOverrunPoints: Float = 0f,
    val breakDecayPoints: Float = 0f,
    val totalScore: Int = 0,
    val riskLevel: RiskLevel = RiskLevel.NORMAL,
    val cappedReason: String = "",
    val explanation: String = ""
)

/**
 * Immutable behavioural snapshot fed into [DoomscrollDetectionEngine].
 */
data class BehaviourSnapshot(
    val sessionId: String = "",
    val app: SupportedSocialApp = SupportedSocialApp.UNSUPPORTED,
    val applicationPackage: String = "",
    val isInConfirmedFeed: Boolean = false,
    val feedConfidence: Float = 0f,
    val feedContextType: FeedContextType = FeedContextType.UNKNOWN,
    val sessionStartTimestampMs: Long = 0L,
    val activeFeedDurationMs: Long = 0L,
    val pausedDurationMs: Long = 0L,
    val backgroundDurationMs: Long = 0L,
    val videoTransitionCount: Int = 0,
    val averageDwellTimeMs: Long = 0L,
    val medianDwellTimeMs: Long = 0L,
    val shortTransitionCount: Int = 0,
    val consecutiveRapidTransitions: Int = 0,
    val transitionsPerMinute: Float = 0f,
    val reopeningCountInWindow: Int = 0,
    val shortInterruptionCount: Int = 0,
    val meaningfulBreakCount: Int = 0,
    val elapsedSinceMeaningfulBreakMs: Long = Long.MAX_VALUE,
    val pendingBreakDecayPoints: Float = 0f,
    val intendedDurationMinutes: Int = DoomscrollConfig.DEFAULT_INTENDED_DURATION_MINUTES,
    val strictTimeLimitExceeded: Boolean = false,
    val lastUpdatedMs: Long = 0L
)

/**
 * Local-only persisted session summary matching Section 13 (zero video content, screenshots, or DMs).
 */
data class FeedSessionRecord(
    val sessionId: String,
    val applicationPackage: String,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val activeDurationMs: Long,
    val videoTransitionCount: Int,
    val averageDwellTimeMs: Long,
    val shortTransitionCount: Int,
    val reopeningCount: Int,
    val intendedDurationMinutes: Int,
    val maximumRiskScore: Int,
    val interventionCount: Int,
    val completionStatus: SessionCompletionStatus
)

data class DoomscrollLiveStatus(
    val enabled: Boolean,
    val activeAppPackage: String,
    val activeAppName: String,
    val feedContextType: String,
    val isInConfirmedFeed: Boolean,
    val feedConfidence: Float,
    val activeDurationSeconds: Int,
    val videoTransitionCount: Int,
    val averageDwellSeconds: Float,
    val transitionsPerMinute: Float,
    val reopeningCount: Int,
    val riskScore: Int,
    val riskLevel: String,
    val lastInterventionLevel: String,
    val intendedDurationMinutes: Int,
    val totalSessionsToday: Int,
    val totalActiveMinutesToday: Int,
    val signalExplanation: String
)
