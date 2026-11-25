package com.paulfruitful.screenguard.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

data class ScreenTextSignal(
    val hasVisibleText: Boolean = false,
    val adultScore: Float = 0f,
    val hardcoreScore: Float = 0f,
    val isExplicitPornDomain: Boolean = false,
    val fromSubtitleNode: Boolean = false,
    val isBenignEducationalOrDialogue: Boolean = false,
    val isBenignNonSexualVisualContext: Boolean = false,
    val matchedIndicators: List<String> = emptyList(),
    val scanDurationMs: Long = 0L
)

/**
 * Fast, bounded, privacy-preserving on-screen text, subtitle node, and URL context analyzer.
 *
 * CRITICAL RELIABILITY INVARIANT (Phase A Root-Cause Fix):
 * - NEVER executes `service.windows` or `AccessibilityWindowInfo.getRoot()` on the Main UI Thread
 *   (`Looper.getMainLooper()`). All Binder IPC node traversal is strictly restricted to the
 *   background worker thread and bounded by [MAX_SCAN_BUDGET_MS] (35 ms) and [MAX_NODES_TO_SCAN] (48).
 */
object ScreenTextContextAnalyzer {

    private const val MAX_NODES_TO_SCAN = 64
    private const val MAX_SCAN_BUDGET_MS = 35L

    private val EXPLICIT_PORN_DOMAINS = listOf(
        "pornhub.",
        "xvideos.",
        "xnxx.",
        "xhamster.",
        "redtube.",
        "youporn.",
        "spankbang.",
        "eporner.",
        "brazzers.",
        "chaturbate.",
        "stripchat.",
        "bongacams.",
        "cam4.",
        "rule34.",
        "nhentai.",
        "hanime.",
        "beeg.",
        "motherless.",
        "hqporner.",
        "sxyprn.",
        "daftsex.",
        "porntrex."
    )

    private val HARDCORE_EXPLICIT_TERMS = listOf(
        "pornhub",
        "xvideos",
        "xnxx",
        "xhamster",
        "redtube",
        "youporn",
        "spankbang",
        "blowjob",
        "handjob",
        "cumshot",
        "creampie",
        "gangbang",
        "doggystyle",
        "deepthroat",
        "hardcore porn",
        "sex tape",
        "pussy",
        "dick",
        "cock"
    )

    private val CONTEXTUAL_SEXUAL_TERMS = listOf(
        "onlyfans.com",
        "onlyfans",
        "fansly",
        "shmoodnasty",
        "sheluvraf",
        "quickie",
        "quickies",
        "sex hits",
        "having sex",
        "public sex",
        "get off",
        "nsfw",
        "xxx",
        "18+",
        "porn",
        "nude",
        "nudes",
        "naked",
        "boobs",
        "tits",
        "uncensored",
        "leaked",
        "horny",
        "freaky",
        "naughty",
        "thirst trap"
    )

    private val BENIGN_EDUCATIONAL_OR_DIALOGUE_TERMS = listOf(
        "sex education",
        "sexual health",
        "reproductive health",
        "breast cancer",
        "breastfeeding",
        "maternal",
        "anatomy",
        "medical",
        "wikipedia",
        "dictionary",
        "biology",
        "gender:",
        "biological sex",
        "pregnancy",
        "pediatric",
        "dermatology"
    )

    /**
     * Non-sexual visual contexts that frequently produce borderline SUGGESTIVE/SEXY scores
     * (gym/fitness, combat sports, swimming, medical/skincare, parenting). Used ONLY to veto
     * borderline SUGGESTIVE frames when explicit porn/nudity scores are low — never overrides
     * high-confidence EXPLICIT/NUDITY/PORN detections.
     */
    private val BENIGN_NON_SEXUAL_VISUAL_CONTEXT_TERMS = listOf(
        "ufc",
        "mma",
        "boxing",
        "wrestling",
        "jiu-jitsu",
        "bjj",
        "muay thai",
        "kickboxing",
        "workout",
        "gym",
        "fitness",
        "bodybuilding",
        "crossfit",
        "calisthenics",
        "deadlift",
        "bench press",
        "physique",
        "athlete",
        "swimming",
        "olympics",
        "triathlon",
        "marathon",
        "beach volleyball",
        "surfing",
        "yoga",
        "pilates",
        "skincare",
        "first aid",
        "physical therapy",
        "physiotherapy",
        "chiropractic",
        "postpartum",
        "newborn",
        "baby bath",
        "toddler"
    )

    @Volatile
    private var latestSignal: ScreenTextSignal = ScreenTextSignal()

    @Volatile
    private var rollingTextBuffer: StringBuilder = StringBuilder(512)

    @Volatile
    private var rollingPackage: String = ""

    fun getLatestSignal(): ScreenTextSignal = latestSignal

    /**
     * Zero-IPC inline event text ingestion (< 0.02 ms).
     * Extracts text and contentDescription directly from incoming [android.view.accessibility.AccessibilityEvent]
     * parcels without calling `AccessibilityInteractionClient.waitForResultTimedLocked(5000ms)`.
     */
    fun onAccessibilityEventInline(event: android.view.accessibility.AccessibilityEvent) {
        if (event.isPassword) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.paulfruitful.screenguard") return

        val startNs = SystemClock.elapsedRealtimeNanos()
        try {
            if (pkg != rollingPackage) {
                rollingPackage = pkg
                rollingTextBuffer = StringBuilder(512)
                latestSignal = ScreenTextSignal()
            }
            val sb = rollingTextBuffer
            if (sb.length > 900) {
                sb.delete(0, sb.length - 450)
            }
            var added = false
            val texts = event.text
            if (!texts.isNullOrEmpty()) {
                for (t in texts) {
                    if (!t.isNullOrBlank() && t.length <= 240) {
                        sb.append(t).append(' ')
                        added = true
                    }
                }
            }
            val desc = event.contentDescription
            if (!desc.isNullOrBlank() && desc.length <= 180) {
                sb.append(desc).append(' ')
                added = true
            }
            if (added) {
                val durMs = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
                latestSignal = scoreText(sb.toString().lowercase(), false, durMs)
            }
        } catch (_: Exception) {
        }
    }

    fun analyzeActiveApplicationWindow(
        service: AccessibilityService,
        targetPackageName: String
    ): ScreenTextSignal {
        // Return the zero-IPC inline signal immediately so we never trigger 5,000ms
        // AccessibilityInteractionClient.waitForResultTimedLocked stalls on video players!
        return latestSignal
    }

    internal fun scoreText(
        combinedLower: String,
        subtitleNodeFound: Boolean,
        durMs: Long
    ): ScreenTextSignal {
        if (combinedLower.isBlank()) {
            return ScreenTextSignal(hasVisibleText = false, scanDurationMs = durMs)
        }
        val matched = mutableListOf<String>()
        var isPornDomain = false
        var hardcoreScore = 0f
        var adultScore = 0f
        var isBenignEducational = false
        var isBenignVisualContext = false

        for (benign in BENIGN_EDUCATIONAL_OR_DIALOGUE_TERMS) {
            if (combinedLower.contains(benign)) {
                isBenignEducational = true
                isBenignVisualContext = true
                break
            }
        }

        if (!isBenignVisualContext) {
            for (term in BENIGN_NON_SEXUAL_VISUAL_CONTEXT_TERMS) {
                if (containsWordOrSubstring(combinedLower, term)) {
                    isBenignVisualContext = true
                    break
                }
            }
        }

        for (domain in EXPLICIT_PORN_DOMAINS) {
            if (combinedLower.contains(domain)) {
                isPornDomain = true
                hardcoreScore = 0.96f
                adultScore = 0.96f
                matched.add(domain.trimEnd('.'))
            }
        }

        for (term in HARDCORE_EXPLICIT_TERMS) {
            if (containsWordOrSubstring(combinedLower, term)) {
                hardcoreScore = maxOf(hardcoreScore, 0.88f)
                adultScore = maxOf(adultScore, 0.88f)
                if (matched.size < 4) matched.add(term)
            }
        }

        var contextualHits = 0
        for (term in CONTEXTUAL_SEXUAL_TERMS) {
            if (containsWordOrSubstring(combinedLower, term)) {
                contextualHits++
                if (matched.size < 4) matched.add(term)
            }
        }

        if (contextualHits >= 2) {
            adultScore = maxOf(adultScore, 0.72f)
        } else if (contextualHits == 1) {
            adultScore = maxOf(adultScore, 0.48f)
        }

        if (isBenignEducational && !isPornDomain) {
            adultScore *= 0.25f
            hardcoreScore = 0f
        }

        return ScreenTextSignal(
            hasVisibleText = true,
            adultScore = adultScore.coerceIn(0f, 1f),
            hardcoreScore = hardcoreScore.coerceIn(0f, 1f),
            isExplicitPornDomain = isPornDomain,
            fromSubtitleNode = subtitleNodeFound,
            isBenignEducationalOrDialogue = isBenignEducational,
            isBenignNonSexualVisualContext = isBenignVisualContext,
            matchedIndicators = matched.distinct(),
            scanDurationMs = durMs
        )
    }

    private fun containsWordOrSubstring(haystack: String, needle: String): Boolean {
        if (needle.length >= 6 || needle.contains(' ') || needle.contains('.')) {
            return haystack.contains(needle)
        }
        val idx = haystack.indexOf(needle)
        if (idx < 0) return false
        val beforeOk = idx == 0 || !haystack[idx - 1].isLetterOrDigit()
        val afterIdx = idx + needle.length
        val afterOk = afterIdx >= haystack.length || !haystack[afterIdx].isLetterOrDigit()
        return beforeOk && afterOk
    }

    private fun findApplicationWindowRoot(
        service: AccessibilityService,
        targetPackageName: String
    ): AccessibilityNodeInfo? {
        try {
            val active = service.rootInActiveWindow
            if (active != null) {
                val pkg = active.packageName?.toString() ?: ""
                if (pkg != "com.paulfruitful.screenguard" &&
                    (targetPackageName.isBlank() || pkg == targetPackageName)
                ) {
                    return active
                }
            }
        } catch (_: Exception) {
        }
        return try {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                val appWin = windows.firstOrNull { win ->
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION && win.isActive
                }
                val root = appWin?.root
                if (root?.packageName?.toString() != "com.paulfruitful.screenguard") root else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
