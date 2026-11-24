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

    @Volatile
    private var latestSignal: ScreenTextSignal = ScreenTextSignal()

    fun getLatestSignal(): ScreenTextSignal = latestSignal

    fun analyzeActiveApplicationWindow(
        service: AccessibilityService,
        targetPackageName: String
    ): ScreenTextSignal {
        // Phase A Guard: Refuse to perform Binder IPC window/node traversal on the main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return latestSignal
        }

        val startNs = SystemClock.elapsedRealtimeNanos()
        val deadlineElapsedMs = SystemClock.elapsedRealtime() + MAX_SCAN_BUDGET_MS

        try {
            val root = findApplicationWindowRoot(service, targetPackageName)
            if (root == null) {
                val empty = ScreenTextSignal(
                    scanDurationMs = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
                )
                latestSignal = empty
                return empty
            }

            val sb = StringBuilder(512)
            var visited = 0
            var subtitleNodeFound = false
            val queue = ArrayDeque<AccessibilityNodeInfo>(MAX_NODES_TO_SCAN)
            queue.add(root)

            while (queue.isNotEmpty() &&
                visited < MAX_NODES_TO_SCAN &&
                SystemClock.elapsedRealtime() < deadlineElapsedMs
            ) {
                val node = queue.removeFirst()
                visited++

                if (!node.isPassword && !(node.isEditable && node.isFocused)) {
                    val viewId = try {
                        node.viewIdResourceName ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    if (viewId.contains("subtitle", ignoreCase = true) ||
                        viewId.contains("caption", ignoreCase = true) ||
                        viewId.contains("cues", ignoreCase = true)
                    ) {
                        subtitleNodeFound = true
                    }

                    val text = node.text
                    if (!text.isNullOrBlank() && text.length <= 220) {
                        sb.append(text).append(' ')
                    }
                    val desc = node.contentDescription
                    if (!desc.isNullOrBlank() && desc.length <= 140) {
                        sb.append(desc).append(' ')
                    }
                }

                if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) break

                val childCount = minOf(node.childCount, 8)
                for (i in 0 until childCount) {
                    if (visited + queue.size >= MAX_NODES_TO_SCAN) break
                    if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) break
                    val child = try {
                        node.getChild(i)
                    } catch (_: Exception) {
                        null
                    }
                    if (child != null) {
                        queue.add(child)
                    }
                }
            }

            val durMs = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
            val combinedLower = sb.toString().lowercase()
            if (combinedLower.isBlank()) {
                val empty = ScreenTextSignal(hasVisibleText = false, scanDurationMs = durMs)
                latestSignal = empty
                return empty
            }

            val matched = mutableListOf<String>()
            var isPornDomain = false
            var hardcoreScore = 0f
            var adultScore = 0f
            var isBenignEducational = false

            for (benign in BENIGN_EDUCATIONAL_OR_DIALOGUE_TERMS) {
                if (combinedLower.contains(benign)) {
                    isBenignEducational = true
                    break
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

            val signal = ScreenTextSignal(
                hasVisibleText = true,
                adultScore = adultScore.coerceIn(0f, 1f),
                hardcoreScore = hardcoreScore.coerceIn(0f, 1f),
                isExplicitPornDomain = isPornDomain,
                fromSubtitleNode = subtitleNodeFound,
                isBenignEducationalOrDialogue = isBenignEducational,
                matchedIndicators = matched.distinct(),
                scanDurationMs = durMs
            )
            latestSignal = signal
            return signal
        } catch (_: Exception) {
            return ScreenTextSignal()
        }
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
