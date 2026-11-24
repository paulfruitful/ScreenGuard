package com.paulfruitful.screenguard.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

data class ScreenTextSignal(
    val adultScore: Float = 0f,
    val isExplicitPornDomain: Boolean = false,
    val matchedIndicators: List<String> = emptyList()
)

/**
 * Fast, privacy-preserving on-screen text and URL context analyzer.
 *
 * Inspects visible UI labels, video captions, account handles, and browser URL bars
 * in the underlying [AccessibilityWindowInfo.TYPE_APPLICATION] window to help identify
 * explicit or pornographic videos/pages immediately.
 *
 * Privacy guarantees:
 * - Never reads password nodes (`node.isPassword == true`).
 * - Never reads focused editable text inputs (`node.isEditable && node.isFocused`).
 * - Never stores or transmits raw screen text.
 */
object ScreenTextContextAnalyzer {

    private const val MAX_NODES_TO_SCAN = 110

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
        "horny",
        "freaky",
        "naughty",
        "thirst trap"
    )

    @Volatile
    private var latestSignal: ScreenTextSignal = ScreenTextSignal()

    fun getLatestSignal(): ScreenTextSignal = latestSignal

    fun analyzeActiveApplicationWindow(
        service: AccessibilityService,
        targetPackageName: String
    ): ScreenTextSignal {
        try {
            val root = findApplicationWindowRoot(service, targetPackageName)
                ?: return ScreenTextSignal().also { latestSignal = it }

            val sb = StringBuilder(512)
            var visited = 0
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)

            while (queue.isNotEmpty() && visited < MAX_NODES_TO_SCAN) {
                val node = queue.removeFirst()
                visited++

                if (!node.isPassword && !(node.isEditable && node.isFocused)) {
                    val text = node.text
                    if (!text.isNullOrBlank() && text.length <= 240) {
                        sb.append(text).append(' ')
                    }
                    val desc = node.contentDescription
                    if (!desc.isNullOrBlank() && desc.length <= 160) {
                        sb.append(desc).append(' ')
                    }
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    if (visited + queue.size >= MAX_NODES_TO_SCAN) break
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

            val combinedLower = sb.toString().lowercase()
            if (combinedLower.isBlank()) {
                return ScreenTextSignal().also { latestSignal = it }
            }

            val matched = mutableListOf<String>()
            var isPornDomain = false
            var score = 0f

            for (domain in EXPLICIT_PORN_DOMAINS) {
                if (combinedLower.contains(domain)) {
                    isPornDomain = true
                    score = maxOf(score, 0.96f)
                    matched.add(domain.trimEnd('.'))
                }
            }

            for (term in HARDCORE_EXPLICIT_TERMS) {
                if (containsWordOrSubstring(combinedLower, term)) {
                    score = maxOf(score, 0.88f)
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
                score = maxOf(score, 0.72f)
            } else if (contextualHits == 1) {
                score = maxOf(score, 0.48f)
            }

            val signal = ScreenTextSignal(
                adultScore = score.coerceIn(0f, 1f),
                isExplicitPornDomain = isPornDomain,
                matchedIndicators = matched.distinct()
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
        // For short 3-5 char words ("sex", "xxx", "18+", "nude", "porn", "tits", "dick", "cock"),
        // require word boundaries so innocent words like "essex", "sixth", or "cockpit" never match.
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
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                val appWin = windows.firstOrNull { win ->
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        win.isActive &&
                        try {
                            win.root?.packageName?.toString() == targetPackageName
                        } catch (_: Exception) {
                            false
                        }
                } ?: windows.firstOrNull { win ->
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION && win.isActive
                }
                if (appWin != null) {
                    appWin.root?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return try {
            val active = service.rootInActiveWindow
            if (active?.packageName?.toString() != "com.paulfruitful.screenguard") active else null
        } catch (_: Exception) {
            null
        }
    }
}
