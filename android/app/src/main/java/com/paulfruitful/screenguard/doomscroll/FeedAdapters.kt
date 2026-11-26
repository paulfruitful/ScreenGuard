package com.paulfruitful.screenguard.doomscroll

import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * Modular interface for application-specific short-form feed detection.
 *
 * Design rules:
 * - Never classify an entire app as a short-form feed by package name alone.
 * - Exclude DMs, Search, Profiles, Comments, Ordinary Timelines, and Long-Form Videos.
 * - Return [FeedContextType.UNKNOWN] with low confidence whenever UI signals are ambiguous
 *   (e.g. after a social app UI update) rather than fabricating behavioural events.
 */
interface SocialAppFeedAdapter {
    val app: SupportedSocialApp
    val adapterName: String
    fun classify(snapshot: FeedObservationSnapshot): FeedClassification
}

internal object AdapterSignalUtils {
    private val LONG_FORM_DURATION_REGEX = Regex("""-?(\d{1,2}):(\d{2})(?::(\d{2}))?""")

    fun combineLowerTokens(snapshot: FeedObservationSnapshot): String {
        val sb = StringBuilder(256)
        if (snapshot.className.isNotBlank()) sb.append(snapshot.className.lowercase()).append(' ')
        for (id in snapshot.viewIdResourceNames) {
            if (id.isNotBlank()) sb.append(id.lowercase()).append(' ')
        }
        for (desc in snapshot.contentDescriptions) {
            if (desc.isNotBlank()) sb.append(desc.lowercase()).append(' ')
        }
        for (txt in snapshot.texts) {
            if (txt.isNotBlank()) sb.append(txt.lowercase()).append(' ')
        }
        return sb.toString()
    }

    fun isVerticalScrollEvent(snapshot: FeedObservationSnapshot): Boolean {
        if (snapshot.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return false
        val dy = abs(snapshot.scrollDeltaY)
        val dx = abs(snapshot.scrollDeltaX)
        if (dy > 0 && dy >= dx) return true
        // Some vertical ViewPagers report fromIndex/toIndex without scrollDeltaY
        if (dx == 0 && snapshot.fromIndex >= 0 && snapshot.toIndex >= 0) return true
        return false
    }

    /**
     * Parses visible video duration/remaining time stamps (e.g. "14:22", "-18:05", "1:04:10")
     * and returns true if any duration indicates a deliberate long-form video (>= 3 minutes).
     */
    fun hasLongFormDurationIndicator(snapshot: FeedObservationSnapshot): Boolean {
        val candidates = snapshot.texts + snapshot.contentDescriptions
        for (raw in candidates) {
            val match = LONG_FORM_DURATION_REGEX.find(raw) ?: continue
            val g1 = match.groupValues[1].toIntOrNull() ?: continue
            val g2 = match.groupValues[2].toIntOrNull() ?: continue
            val g3 = match.groupValues[3].toIntOrNull()
            val totalSeconds = if (g3 != null) {
                g1 * 3600 + g2 * 60 + g3
            } else {
                g1 * 60 + g2
            }
            if (totalSeconds >= 180) {
                return true
            }
        }
        return false
    }

    fun buildContentSignature(snapshot: FeedObservationSnapshot): String {
        // Build a compact deterministic signature from visible non-numeric labels (handles, captions,
        // audio titles) so repeated animations/counters on the same video do NOT look like a new video.
        val parts = mutableListOf<String>()
        for (raw in snapshot.contentDescriptions + snapshot.texts) {
            val cleaned = raw.trim()
            if (cleaned.length < 4) continue
            // Skip pure timers / counts like "0:14", "-0:48", "12.4K", "893", "1x"
            if (cleaned.matches(Regex("""^[+-]?\d[\d.,:KMBkmbx\s-]*$"""))) continue
            val lower = cleaned.lowercase()
            if (lower in GENERIC_CHROME_WORDS) continue
            parts.add(lower.take(48))
            if (parts.size >= 4) break
        }
        return parts.joinToString("|")
    }

    private val GENERIC_CHROME_WORDS = setOf(
        "like", "comment", "share", "follow", "following", "for you", "reels",
        "home", "friends", "inbox", "profile", "search", "explore", "repost",
        "bookmark", "more", "more options", "back", "pause", "play", "mute",
        "unmute", "volume", "live", "shop", "notifications", "messages"
    )
}

class TikTokAdapter : SocialAppFeedAdapter {
    override val app: SupportedSocialApp = SupportedSocialApp.TIKTOK
    override val adapterName: String = "TikTokAdapter"

    override fun classify(snapshot: FeedObservationSnapshot): FeedClassification {
        val tokens = AdapterSignalUtils.combineLowerTokens(snapshot)
        val verticalScroll = AdapterSignalUtils.isVerticalScrollEvent(snapshot)
        val sig = AdapterSignalUtils.buildContentSignature(snapshot)
        val pagerIdx = if (snapshot.fromIndex >= 0) snapshot.fromIndex else -1

        // 1. Explicit non-feed exclusions
        if (containsAny(tokens, DM_TOKENS)) {
            return FeedClassification(app, FeedContextType.DIRECT_MESSAGES, 0.90f, adapterName, sig, pagerIdx, false, "tiktok_dm_or_inbox")
        }
        if (containsAny(tokens, COMMENT_TOKENS)) {
            return FeedClassification(app, FeedContextType.COMMENTS, 0.88f, adapterName, sig, pagerIdx, false, "tiktok_comments")
        }
        if (containsAny(tokens, SEARCH_TOKENS)) {
            return FeedClassification(app, FeedContextType.SEARCH, 0.88f, adapterName, sig, pagerIdx, false, "tiktok_search")
        }
        if (containsAny(tokens, PROFILE_TOKENS)) {
            return FeedClassification(app, FeedContextType.PROFILE, 0.88f, adapterName, sig, pagerIdx, false, "tiktok_profile")
        }
        if (AdapterSignalUtils.hasLongFormDurationIndicator(snapshot)) {
            return FeedClassification(app, FeedContextType.LONG_FORM_VIDEO, 0.80f, adapterName, sig, pagerIdx, false, "tiktok_long_form_duration")
        }

        // 2. Positive For You / Short-Form Feed signals
        var confidence = 0f
        val reasons = mutableListOf<String>()

        if (containsAny(tokens, FEED_ID_OR_CLASS_TOKENS)) {
            confidence += 0.45f
            reasons.add("feed_container")
        }
        if (containsAny(tokens, FEED_VIDEO_SURFACE_TOKENS)) {
            confidence += 0.30f
            reasons.add("video_surface")
        }
        if (containsAny(tokens, FEED_TAB_TOKENS)) {
            confidence += 0.30f
            reasons.add("feed_tab")
        }
        val actionHits = FEED_ACTION_TOKENS.count { tokens.contains(it) }
        if (actionHits >= 2) {
            confidence += 0.30f
            reasons.add("action_chrome($actionHits)")
        } else if (actionHits == 1) {
            confidence += 0.15f
            reasons.add("action_chrome(1)")
        }
        if (verticalScroll) {
            confidence += 0.25f
            reasons.add("vertical_scroll")
        }

        val clamped = confidence.coerceIn(0f, 0.98f)
        return if (clamped >= 0.55f) {
            FeedClassification(
                app = app,
                contextType = FeedContextType.SHORT_FORM_FEED,
                confidence = clamped,
                adapterName = adapterName,
                contentSignature = sig,
                pagerIndex = pagerIdx,
                isVerticalScrollSignal = verticalScroll,
                diagnosticReason = reasons.joinToString("+")
            )
        } else {
            FeedClassification(
                app = app,
                contextType = FeedContextType.UNKNOWN,
                confidence = clamped,
                adapterName = adapterName,
                contentSignature = sig,
                pagerIndex = pagerIdx,
                isVerticalScrollSignal = verticalScroll,
                diagnosticReason = "tiktok_insufficient_signals"
            )
        }
    }

    companion object {
        private val DM_TOKENS = listOf("direct message", "send a message", "chat_input", "im_chat", "message...", "inbox_activity")
        private val COMMENT_TOKENS = listOf(
            "add comment", "comment_list", "comment_sheet", "tv_reply_comment_title",
            "view more replies", " comments"
        )
        private val SEARCH_TOKENS = listOf("search_edit", "search bar", "search_input", "discover_search", "recent searches")
        private val PROFILE_TOKENS = listOf("edit profile", "add bio", "share profile", "user_profile", "profile_header", "following_list")
        private val FEED_ID_OR_CLASS_TOKENS = listOf(
            "verticalviewpager", "viewpager2", "viewpager_container", "view_pager_layout_wrapper",
            "viewpager", "feed_viewpager", "detail_viewpager", "recommend_feed", "aweme", "video_view", "feed_item"
        )
        private val FEED_VIDEO_SURFACE_TOKENS = listOf("long_press_layout", "tv_like_count", "video")
        private val FEED_TAB_TOKENS = listOf("for you", "original sound", "add to favorites", "tiktok_feed", "following")
        private val FEED_ACTION_TOKENS = listOf("like", "comment", "share", "bookmark", "sound", "music", "follow", "thumbs up")
    }
}

class InstagramAdapter : SocialAppFeedAdapter {
    override val app: SupportedSocialApp = SupportedSocialApp.INSTAGRAM
    override val adapterName: String = "InstagramAdapter"

    override fun classify(snapshot: FeedObservationSnapshot): FeedClassification {
        val tokens = AdapterSignalUtils.combineLowerTokens(snapshot)
        val verticalScroll = AdapterSignalUtils.isVerticalScrollEvent(snapshot)
        val sig = AdapterSignalUtils.buildContentSignature(snapshot)
        val pagerIdx = if (snapshot.fromIndex >= 0) snapshot.fromIndex else -1

        // 1. Explicit non-Reels exclusions (DMs, Comments, Search, Profile, Stories, Ordinary Feed)
        if (containsAny(tokens, DM_TOKENS)) {
            return FeedClassification(app, FeedContextType.DIRECT_MESSAGES, 0.92f, adapterName, sig, pagerIdx, false, "ig_dm")
        }
        if (containsAny(tokens, COMMENT_TOKENS)) {
            return FeedClassification(app, FeedContextType.COMMENTS, 0.90f, adapterName, sig, pagerIdx, false, "ig_comments")
        }
        if (containsAny(tokens, SEARCH_TOKENS)) {
            return FeedClassification(app, FeedContextType.SEARCH, 0.88f, adapterName, sig, pagerIdx, false, "ig_search")
        }
        if (containsAny(tokens, PROFILE_TOKENS)) {
            return FeedClassification(app, FeedContextType.PROFILE, 0.90f, adapterName, sig, pagerIdx, false, "ig_profile")
        }
        if (containsAny(tokens, ORDINARY_TIMELINE_TOKENS) && !containsAny(tokens, REELS_ID_TOKENS)) {
            return FeedClassification(app, FeedContextType.ORDINARY_TIMELINE, 0.82f, adapterName, sig, pagerIdx, false, "ig_home_or_story")
        }
        if (AdapterSignalUtils.hasLongFormDurationIndicator(snapshot)) {
            return FeedClassification(app, FeedContextType.LONG_FORM_VIDEO, 0.80f, adapterName, sig, pagerIdx, false, "ig_long_form")
        }

        // 2. Positive Instagram Reels (Clips) signals
        var confidence = 0f
        val reasons = mutableListOf<String>()

        if (containsAny(tokens, REELS_ID_TOKENS)) {
            confidence += 0.50f
            reasons.add("clips_viewer")
        }
        if (containsAny(tokens, REELS_LABEL_TOKENS)) {
            confidence += 0.30f
            reasons.add("reels_label")
        }
        val actionHits = REELS_ACTION_TOKENS.count { tokens.contains(it) }
        if (actionHits >= 2) {
            confidence += 0.20f
            reasons.add("reels_actions($actionHits)")
        }
        if (verticalScroll && confidence > 0f) {
            confidence += 0.15f
            reasons.add("vertical_scroll")
        }

        val clamped = confidence.coerceIn(0f, 0.98f)
        return if (clamped >= 0.55f) {
            FeedClassification(
                app = app,
                contextType = FeedContextType.SHORT_FORM_FEED,
                confidence = clamped,
                adapterName = adapterName,
                contentSignature = sig,
                pagerIndex = pagerIdx,
                isVerticalScrollSignal = verticalScroll,
                diagnosticReason = reasons.joinToString("+")
            )
        } else {
            FeedClassification(
                app = app,
                contextType = FeedContextType.UNKNOWN,
                confidence = clamped,
                adapterName = adapterName,
                contentSignature = sig,
                pagerIndex = pagerIdx,
                isVerticalScrollSignal = verticalScroll,
                diagnosticReason = "ig_not_confirmed_reels"
            )
        }
    }

    companion object {
        private val DM_TOKENS = listOf("direct_inbox", "direct_thread", "direct_message", "row_thread_composer", "message...", "send message", "active now")
        private val COMMENT_TOKENS = listOf("add a comment", "comment_composer", "comment_row", "view all comments")
        private val SEARCH_TOKENS = listOf("action_bar_search_edit_text", "search_edit_text", "explore_grid", "search meta ai")
        private val PROFILE_TOKENS = listOf("edit profile", "share profile", "profile_header", "followers", "posts")
        private val ORDINARY_TIMELINE_TOKENS = listOf("your story", "reel_viewer_story", "story_viewer", "feed_timeline", "sponsored", "suggested for you")
        private val REELS_ID_TOKENS = listOf("clips_viewer", "clips_video", "reels_viewer", "clips_swipe", "clips_tab", "viewpager2")
        private val REELS_LABEL_TOKENS = listOf("reels", "original audio", "use audio", "remix this reel")
        private val REELS_ACTION_TOKENS = listOf("like", "comment", "share", "audio", "follow")
    }
}

class FacebookAdapter : SocialAppFeedAdapter {
    override val app: SupportedSocialApp = SupportedSocialApp.FACEBOOK
    override val adapterName: String = "FacebookAdapter"

    override fun classify(snapshot: FeedObservationSnapshot): FeedClassification {
        val tokens = AdapterSignalUtils.combineLowerTokens(snapshot)
        val verticalScroll = AdapterSignalUtils.isVerticalScrollEvent(snapshot)
        val sig = AdapterSignalUtils.buildContentSignature(snapshot)
        val pagerIdx = if (snapshot.fromIndex >= 0) snapshot.fromIndex else -1

        // 1. Explicit non-Reels exclusions
        if (containsAny(tokens, DM_TOKENS)) {
            return FeedClassification(app, FeedContextType.DIRECT_MESSAGES, 0.92f, adapterName, sig, pagerIdx, false, "fb_dm")
        }
        if (containsAny(tokens, COMMENT_TOKENS)) {
            return FeedClassification(app, FeedContextType.COMMENTS, 0.88f, adapterName, sig, pagerIdx, false, "fb_comments")
        }
        if (containsAny(tokens, SEARCH_TOKENS)) {
            return FeedClassification(app, FeedContextType.SEARCH, 0.88f, adapterName, sig, pagerIdx, false, "fb_search")
        }
        if (containsAny(tokens, PROFILE_TOKENS)) {
            return FeedClassification(app, FeedContextType.PROFILE, 0.88f, adapterName, sig, pagerIdx, false, "fb_profile")
        }
        if (AdapterSignalUtils.hasLongFormDurationIndicator(snapshot)) {
            return FeedClassification(app, FeedContextType.LONG_FORM_VIDEO, 0.82f, adapterName, sig, pagerIdx, false, "fb_long_form_video")
        }
        if (containsAny(tokens, ORDINARY_FEED_TOKENS) && !containsAny(tokens, REELS_ID_TOKENS)) {
            return FeedClassification(app, FeedContextType.ORDINARY_TIMELINE, 0.82f, adapterName, sig, pagerIdx, false, "fb_news_feed")
        }

        // 2. Positive Facebook Reels / Short Video Feed signals
        var confidence = 0f
        val reasons = mutableListOf<String>()

        if (containsAny(tokens, REELS_ID_TOKENS)) {
            confidence += 0.50f
            reasons.add("fb_reels_container")
        }
        if (containsAny(tokens, REELS_LABEL_TOKENS)) {
            confidence += 0.30f
            reasons.add("fb_reels_label")
        }
        val actionHits = REELS_ACTION_TOKENS.count { tokens.contains(it) }
        if (actionHits >= 2) {
            confidence += 0.20f
            reasons.add("fb_actions($actionHits)")
        }
        if (verticalScroll && confidence > 0f) {
            confidence += 0.15f
            reasons.add("vertical_scroll")
        }

        val clamped = confidence.coerceIn(0f, 0.98f)
        return if (clamped >= 0.55f) {
            FeedClassification(
                app = app,
                contextType = FeedContextType.SHORT_FORM_FEED,
                confidence = clamped,
                adapterName = adapterName,
                contentSignature = sig,
                pagerIndex = pagerIdx,
                isVerticalScrollSignal = verticalScroll,
                diagnosticReason = reasons.joinToString("+")
            )
        } else {
            FeedClassification(
                app = app,
                contextType = FeedContextType.UNKNOWN,
                confidence = clamped,
                adapterName = adapterName,
                contentSignature = sig,
                pagerIndex = pagerIdx,
                isVerticalScrollSignal = verticalScroll,
                diagnosticReason = "fb_not_confirmed_reels"
            )
        }
    }

    companion object {
        private val DM_TOKENS = listOf("messenger", "send a message", "chat_head", "type a message")
        private val COMMENT_TOKENS = listOf("write a comment", "most relevant", "all comments", "comment_composer")
        private val SEARCH_TOKENS = listOf("search facebook", "recent searches", "marketplace")
        private val PROFILE_TOKENS = listOf("add to story", "edit profile", "friends_list", "mutual friends")
        private val ORDINARY_FEED_TOKENS = listOf("what's on your mind", "create story", "news_feed", "feed_story")
        private val REELS_ID_TOKENS = listOf("fb_reels", "reels_viewer", "short_form_video", "shortformvideo", "fb_shorts", "viewpager2")
        private val REELS_LABEL_TOKENS = listOf("reels", "original audio", "create reel", "watch more reels")
        private val REELS_ACTION_TOKENS = listOf("like", "comment", "share", "follow", "audio")
    }
}

class XAdapter : SocialAppFeedAdapter {
    override val app: SupportedSocialApp = SupportedSocialApp.X
    override val adapterName: String = "XAdapter"

    override fun classify(snapshot: FeedObservationSnapshot): FeedClassification {
        val tokens = AdapterSignalUtils.combineLowerTokens(snapshot)
        val verticalScroll = AdapterSignalUtils.isVerticalScrollEvent(snapshot)
        val sig = AdapterSignalUtils.buildContentSignature(snapshot)
        val pagerIdx = if (snapshot.fromIndex >= 0) snapshot.fromIndex else -1

        // 1. Explicit non-video-feed exclusions (reading posts, DMs, Search, Profile, Replies)
        if (containsAny(tokens, DM_TOKENS)) {
            return FeedClassification(app, FeedContextType.DIRECT_MESSAGES, 0.92f, adapterName, sig, pagerIdx, false, "x_dm")
        }
        if (containsAny(tokens, REPLY_TOKENS)) {
            return FeedClassification(app, FeedContextType.COMMENTS, 0.88f, adapterName, sig, pagerIdx, false, "x_replies")
        }
        if (containsAny(tokens, SEARCH_TOKENS)) {
            return FeedClassification(app, FeedContextType.SEARCH, 0.88f, adapterName, sig, pagerIdx, false, "x_search")
        }
        if (containsAny(tokens, PROFILE_TOKENS)) {
            return FeedClassification(app, FeedContextType.PROFILE, 0.88f, adapterName, sig, pagerIdx, false, "x_profile")
        }
        if (AdapterSignalUtils.hasLongFormDurationIndicator(snapshot)) {
            return FeedClassification(app, FeedContextType.LONG_FORM_VIDEO, 0.84f, adapterName, sig, pagerIdx, false, "x_long_form_video")
        }
        if (containsAny(tokens, ORDINARY_TIMELINE_TOKENS) && !containsAny(tokens, IMMERSIVE_VIDEO_TOKENS)) {
            return FeedClassification(app, FeedContextType.ORDINARY_TIMELINE, 0.82f, adapterName, sig, pagerIdx, false, "x_text_timeline")
        }

        // 2. Positive X immersive continuous video feed signals
        var confidence = 0f
        val reasons = mutableListOf<String>()

        if (containsAny(tokens, IMMERSIVE_VIDEO_TOKENS)) {
            confidence += 0.50f
            reasons.add("x_immersive_video")
        }
        if (containsAny(tokens, SHORT_VIDEO_CHROME_TOKENS)) {
            confidence += 0.25f
            reasons.add("x_video_chrome")
        }
        val actionHits = VIDEO_ENGAGEMENT_TOKENS.count { tokens.contains(it) }
        if (actionHits >= 2) {
            confidence += 0.20f
            reasons.add("x_actions($actionHits)")
        }
        if (verticalScroll && confidence > 0f) {
            confidence += 0.15f
            reasons.add("vertical_scroll")
        }

        val clamped = confidence.coerceIn(0f, 0.98f)
        return if (clamped >= 0.55f) {
            FeedClassification(
                app = app,
                contextType = FeedContextType.SHORT_FORM_FEED,
                confidence = clamped,
                adapterName = adapterName,
                contentSignature = sig,
                pagerIndex = pagerIdx,
                isVerticalScrollSignal = verticalScroll,
                diagnosticReason = reasons.joinToString("+")
            )
        } else {
            FeedClassification(
                app = app,
                contextType = FeedContextType.UNKNOWN,
                confidence = clamped,
                adapterName = adapterName,
                contentSignature = sig,
                pagerIndex = pagerIdx,
                isVerticalScrollSignal = verticalScroll,
                diagnosticReason = "x_not_confirmed_video_feed"
            )
        }
    }

    companion object {
        private val DM_TOKENS = listOf("start a message", "dm_inbox", "dm_conversation", "direct messages", "write a message")
        private val REPLY_TOKENS = listOf("post your reply", "replying to", "tweet_reply_composer")
        private val SEARCH_TOKENS = listOf("search x", "trending in", "what's happening", "search_query")
        private val PROFILE_TOKENS = listOf("edit profile", "joined ", "following", "followers", "highlights")
        private val ORDINARY_TIMELINE_TOKENS = listOf("home_timeline", "composer_write", "new post", "show more posts")
        private val IMMERSIVE_VIDEO_TOKENS = listOf(
            "immersive_video", "immersiveplayer", "explore_video", "video_carousel",
            "av_player", "media_player_immersive", "viewpager2"
        )
        private val SHORT_VIDEO_CHROME_TOKENS = listOf("1x", "playback speed", "mute", "unmute", "watch more")
        private val VIDEO_ENGAGEMENT_TOKENS = listOf("like", "repost", "reply", "bookmark", "share", "follow")
    }
}

private fun containsAny(haystack: String, needles: List<String>): Boolean {
    for (n in needles) {
        if (haystack.contains(n)) return true
    }
    return false
}

/**
 * Central FeedDetector dispatching to modular per-application adapters.
 */
class FeedDetector(
    adapters: List<SocialAppFeedAdapter> = listOf(
        TikTokAdapter(),
        InstagramAdapter(),
        FacebookAdapter(),
        XAdapter()
    )
) {
    private val adapterByApp: Map<SupportedSocialApp, SocialAppFeedAdapter> =
        adapters.associateBy { it.app }

    fun detect(snapshot: FeedObservationSnapshot, config: DoomscrollConfig): FeedClassification {
        val app = SupportedSocialApp.fromPackage(snapshot.packageName)
        if (app == SupportedSocialApp.UNSUPPORTED || !config.isAppEnabled(app)) {
            return FeedClassification(
                app = app,
                contextType = FeedContextType.UNKNOWN,
                confidence = 0f,
                adapterName = "None",
                diagnosticReason = if (app == SupportedSocialApp.UNSUPPORTED) "unsupported_app" else "app_disabled_in_config"
            )
        }
        val adapter = adapterByApp[app] ?: return FeedClassification(
            app = app,
            contextType = FeedContextType.UNKNOWN,
            confidence = 0f,
            adapterName = "Missing",
            diagnosticReason = "no_adapter"
        )
        return adapter.classify(snapshot)
    }
}
