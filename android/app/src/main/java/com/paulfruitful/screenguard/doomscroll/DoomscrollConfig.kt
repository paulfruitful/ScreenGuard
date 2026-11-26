package com.paulfruitful.screenguard.doomscroll

import android.content.Context
import org.json.JSONObject

/**
 * Centralized, validated configuration for Screen Guard's optional Behavioural Protection
 * (Doomscroll Detection) engine.
 *
 * Opt-in by default (`enabled = false`): users activate and customize it from Settings.
 * All processing and history remain 100% local on-device.
 */
data class DoomscrollConfig(
    val enabled: Boolean = false,
    // Supported social apps
    val tiktokEnabled: Boolean = true,
    val instagramEnabled: Boolean = true,
    val facebookEnabled: Boolean = true,
    val xEnabled: Boolean = true,
    // Session & break settings
    val intendedDurationMinutes: Int = DEFAULT_INTENDED_DURATION_MINUTES,
    val strictTimeLimitEnabled: Boolean = false,
    val shortInterruptionMaxSeconds: Int = DEFAULT_SHORT_INTERRUPTION_MAX_SECONDS,
    val meaningfulBreakSeconds: Int = DEFAULT_MEANINGFUL_BREAK_SECONDS,
    // Detection parameters
    val scoringIntervalSeconds: Int = DEFAULT_SCORING_INTERVAL_SECONDS,
    val minimumFeedConfidence: Float = DEFAULT_MIN_FEED_CONFIDENCE,
    val minimumSessionDurationSeconds: Int = DEFAULT_MIN_SESSION_DURATION_SECONDS,
    val duplicateEventSuppressionMs: Long = DEFAULT_DUPLICATE_SUPPRESSION_MS,
    val shortDwellThresholdSeconds: Int = DEFAULT_SHORT_DWELL_THRESHOLD_SECONDS,
    val reopeningWindowMinutes: Int = DEFAULT_REOPENING_WINDOW_MINUTES,
    val scrollRateWindowSeconds: Int = DEFAULT_SCROLL_RATE_WINDOW_SECONDS,
    // Scoring weights (total = 100)
    val maxDurationPoints: Int = 25,
    val maxTransitionPoints: Int = 20,
    val maxShortDwellPoints: Int = 15,
    val maxScrollIntensityPoints: Int = 15,
    val maxReopeningPoints: Int = 15,
    val maxIntendedOverrunPoints: Int = 10,
    // Risk classification thresholds (0..100)
    val normalThresholdMax: Int = 29,
    val lowThresholdMax: Int = 49,
    val moderateThresholdMax: Int = 69,
    val highThresholdMax: Int = 84,
    // Intervention cooldowns
    val gentleReminderCooldownSeconds: Int = DEFAULT_REMINDER_COOLDOWN_SECONDS,
    val strongInterventionCooldownSeconds: Int = DEFAULT_STRONG_COOLDOWN_SECONDS,
    val criticalInterventionCooldownSeconds: Int = DEFAULT_CRITICAL_COOLDOWN_SECONDS,
    // Privacy invariants (always local-only)
    val localProcessingOnly: Boolean = true,
    val storeScreenshots: Boolean = false,
    val uploadBehaviouralData: Boolean = false
) {
    val safeIntendedDurationMinutes: Int
        get() = intendedDurationMinutes.coerceIn(MIN_INTENDED_DURATION_MINUTES, MAX_INTENDED_DURATION_MINUTES)

    val safeMeaningfulBreakMs: Long
        get() = meaningfulBreakSeconds.coerceIn(30, 600) * 1000L

    val safeShortInterruptionMaxMs: Long
        get() = shortInterruptionMaxSeconds.coerceIn(5, 59) * 1000L

    val safeScoringIntervalMs: Long
        get() = scoringIntervalSeconds.coerceIn(2, 30) * 1000L

    val safeMinFeedConfidence: Float
        get() = minimumFeedConfidence.coerceIn(0.35f, 0.95f)

    val safeMinSessionDurationMs: Long
        get() = minimumSessionDurationSeconds.coerceIn(30, 600) * 1000L

    val safeDuplicateSuppressionMs: Long
        get() = duplicateEventSuppressionMs.coerceIn(400L, 5_000L)

    val safeShortDwellThresholdMs: Long
        get() = shortDwellThresholdSeconds.coerceIn(2, 20) * 1000L

    val safeReopeningWindowMs: Long
        get() = reopeningWindowMinutes.coerceIn(15, 240) * 60_000L

    val safeScrollRateWindowMs: Long
        get() = scrollRateWindowSeconds.coerceIn(30, 300) * 1000L

    val safeReminderCooldownMs: Long
        get() = gentleReminderCooldownSeconds.coerceIn(60, 3600) * 1000L

    val safeStrongCooldownMs: Long
        get() = strongInterventionCooldownSeconds.coerceIn(120, 7200) * 1000L

    val safeCriticalCooldownMs: Long
        get() = criticalInterventionCooldownSeconds.coerceIn(120, 7200) * 1000L

    fun isAppEnabled(app: SupportedSocialApp): Boolean = when (app) {
        SupportedSocialApp.TIKTOK -> tiktokEnabled
        SupportedSocialApp.INSTAGRAM -> instagramEnabled
        SupportedSocialApp.FACEBOOK -> facebookEnabled
        SupportedSocialApp.X -> xEnabled
        SupportedSocialApp.UNSUPPORTED -> false
    }

    fun validated(): DoomscrollConfig {
        val normMax = normalThresholdMax.coerceIn(10, 40)
        val lowMax = lowThresholdMax.coerceIn(normMax + 5, 65)
        val modMax = moderateThresholdMax.coerceIn(lowMax + 5, 80)
        val hiMax = highThresholdMax.coerceIn(modMax + 5, 95)
        return copy(
            intendedDurationMinutes = safeIntendedDurationMinutes,
            shortInterruptionMaxSeconds = shortInterruptionMaxSeconds.coerceIn(5, 59),
            meaningfulBreakSeconds = meaningfulBreakSeconds.coerceIn(30, 600),
            scoringIntervalSeconds = scoringIntervalSeconds.coerceIn(2, 30),
            minimumFeedConfidence = safeMinFeedConfidence,
            minimumSessionDurationSeconds = minimumSessionDurationSeconds.coerceIn(30, 600),
            duplicateEventSuppressionMs = safeDuplicateSuppressionMs,
            shortDwellThresholdSeconds = shortDwellThresholdSeconds.coerceIn(2, 20),
            reopeningWindowMinutes = reopeningWindowMinutes.coerceIn(15, 240),
            scrollRateWindowSeconds = scrollRateWindowSeconds.coerceIn(30, 300),
            normalThresholdMax = normMax,
            lowThresholdMax = lowMax,
            moderateThresholdMax = modMax,
            highThresholdMax = hiMax,
            gentleReminderCooldownSeconds = gentleReminderCooldownSeconds.coerceIn(60, 3600),
            strongInterventionCooldownSeconds = strongInterventionCooldownSeconds.coerceIn(120, 7200),
            criticalInterventionCooldownSeconds = criticalInterventionCooldownSeconds.coerceIn(120, 7200),
            localProcessingOnly = true,
            storeScreenshots = false,
            uploadBehaviouralData = false
        )
    }

    companion object {
        const val MIN_INTENDED_DURATION_MINUTES = 1
        const val MAX_INTENDED_DURATION_MINUTES = 180
        const val DEFAULT_INTENDED_DURATION_MINUTES = 10
        val INTENDED_DURATION_PRESETS_MINUTES = listOf(5, 10, 15, 30)

        const val DEFAULT_SHORT_INTERRUPTION_MAX_SECONDS = 30
        const val DEFAULT_MEANINGFUL_BREAK_SECONDS = 60
        const val DEFAULT_SCORING_INTERVAL_SECONDS = 5
        const val DEFAULT_MIN_FEED_CONFIDENCE = 0.60f
        const val DEFAULT_MIN_SESSION_DURATION_SECONDS = 90
        const val DEFAULT_DUPLICATE_SUPPRESSION_MS = 1200L
        const val DEFAULT_SHORT_DWELL_THRESHOLD_SECONDS = 7
        const val DEFAULT_REOPENING_WINDOW_MINUTES = 60
        const val DEFAULT_SCROLL_RATE_WINDOW_SECONDS = 120

        const val DEFAULT_REMINDER_COOLDOWN_SECONDS = 300
        const val DEFAULT_STRONG_COOLDOWN_SECONDS = 600
        const val DEFAULT_CRITICAL_COOLDOWN_SECONDS = 900
    }
}

object DoomscrollConfigStore {
    private const val PREFS_NAME = "screenguard_doomscroll_store"
    private const val KEY_CONFIG = "doomscroll_config_v1"

    private val lock = Any()

    @Volatile
    private var cached: DoomscrollConfig? = null

    fun load(context: Context): DoomscrollConfig {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = sp.getString(KEY_CONFIG, null)
            val loaded = if (raw.isNullOrBlank()) {
                DoomscrollConfig()
            } else {
                parseJson(raw)
            }
            cached = loaded
            return loaded
        }
    }

    fun save(context: Context, config: DoomscrollConfig): DoomscrollConfig {
        val valid = config.validated()
        synchronized(lock) {
            cached = valid
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().putString(KEY_CONFIG, toJson(valid).toString()).apply()
            return valid
        }
    }

    fun updateFromJson(context: Context, patchJson: String): DoomscrollConfig {
        val current = load(context)
        val obj = try {
            JSONObject(patchJson)
        } catch (_: Exception) {
            return current
        }
        val merged = current.copy(
            enabled = if (obj.has("enabled")) obj.optBoolean("enabled", current.enabled) else current.enabled,
            tiktokEnabled = if (obj.has("tiktokEnabled")) obj.optBoolean("tiktokEnabled", current.tiktokEnabled) else current.tiktokEnabled,
            instagramEnabled = if (obj.has("instagramEnabled")) obj.optBoolean("instagramEnabled", current.instagramEnabled) else current.instagramEnabled,
            facebookEnabled = if (obj.has("facebookEnabled")) obj.optBoolean("facebookEnabled", current.facebookEnabled) else current.facebookEnabled,
            xEnabled = if (obj.has("xEnabled")) obj.optBoolean("xEnabled", current.xEnabled) else current.xEnabled,
            intendedDurationMinutes = if (obj.has("intendedDurationMinutes")) {
                obj.optInt("intendedDurationMinutes", current.intendedDurationMinutes)
            } else current.intendedDurationMinutes,
            strictTimeLimitEnabled = if (obj.has("strictTimeLimitEnabled")) {
                obj.optBoolean("strictTimeLimitEnabled", current.strictTimeLimitEnabled)
            } else current.strictTimeLimitEnabled,
            meaningfulBreakSeconds = if (obj.has("meaningfulBreakSeconds")) {
                obj.optInt("meaningfulBreakSeconds", current.meaningfulBreakSeconds)
            } else current.meaningfulBreakSeconds,
            scoringIntervalSeconds = if (obj.has("scoringIntervalSeconds")) {
                obj.optInt("scoringIntervalSeconds", current.scoringIntervalSeconds)
            } else current.scoringIntervalSeconds,
            minimumFeedConfidence = if (obj.has("minimumFeedConfidence")) {
                obj.optDouble("minimumFeedConfidence", current.minimumFeedConfidence.toDouble()).toFloat()
            } else current.minimumFeedConfidence,
            minimumSessionDurationSeconds = if (obj.has("minimumSessionDurationSeconds")) {
                obj.optInt("minimumSessionDurationSeconds", current.minimumSessionDurationSeconds)
            } else current.minimumSessionDurationSeconds,
            gentleReminderCooldownSeconds = if (obj.has("gentleReminderCooldownSeconds")) {
                obj.optInt("gentleReminderCooldownSeconds", current.gentleReminderCooldownSeconds)
            } else current.gentleReminderCooldownSeconds,
            strongInterventionCooldownSeconds = if (obj.has("strongInterventionCooldownSeconds")) {
                obj.optInt("strongInterventionCooldownSeconds", current.strongInterventionCooldownSeconds)
            } else current.strongInterventionCooldownSeconds,
            normalThresholdMax = if (obj.has("normalThresholdMax")) obj.optInt("normalThresholdMax", current.normalThresholdMax) else current.normalThresholdMax,
            lowThresholdMax = if (obj.has("lowThresholdMax")) obj.optInt("lowThresholdMax", current.lowThresholdMax) else current.lowThresholdMax,
            moderateThresholdMax = if (obj.has("moderateThresholdMax")) obj.optInt("moderateThresholdMax", current.moderateThresholdMax) else current.moderateThresholdMax,
            highThresholdMax = if (obj.has("highThresholdMax")) obj.optInt("highThresholdMax", current.highThresholdMax) else current.highThresholdMax
        )
        return save(context, merged)
    }

    fun clearCacheForTests() {
        synchronized(lock) {
            cached = null
        }
    }

    private fun parseJson(raw: String): DoomscrollConfig {
        return try {
            val obj = JSONObject(raw)
            DoomscrollConfig(
                enabled = obj.optBoolean("enabled", false),
                tiktokEnabled = obj.optBoolean("tiktokEnabled", true),
                instagramEnabled = obj.optBoolean("instagramEnabled", true),
                facebookEnabled = obj.optBoolean("facebookEnabled", true),
                xEnabled = obj.optBoolean("xEnabled", true),
                intendedDurationMinutes = obj.optInt(
                    "intendedDurationMinutes",
                    DoomscrollConfig.DEFAULT_INTENDED_DURATION_MINUTES
                ),
                strictTimeLimitEnabled = obj.optBoolean("strictTimeLimitEnabled", false),
                shortInterruptionMaxSeconds = obj.optInt(
                    "shortInterruptionMaxSeconds",
                    DoomscrollConfig.DEFAULT_SHORT_INTERRUPTION_MAX_SECONDS
                ),
                meaningfulBreakSeconds = obj.optInt(
                    "meaningfulBreakSeconds",
                    DoomscrollConfig.DEFAULT_MEANINGFUL_BREAK_SECONDS
                ),
                scoringIntervalSeconds = obj.optInt(
                    "scoringIntervalSeconds",
                    DoomscrollConfig.DEFAULT_SCORING_INTERVAL_SECONDS
                ),
                minimumFeedConfidence = obj.optDouble(
                    "minimumFeedConfidence",
                    DoomscrollConfig.DEFAULT_MIN_FEED_CONFIDENCE.toDouble()
                ).toFloat(),
                minimumSessionDurationSeconds = obj.optInt(
                    "minimumSessionDurationSeconds",
                    DoomscrollConfig.DEFAULT_MIN_SESSION_DURATION_SECONDS
                ),
                duplicateEventSuppressionMs = obj.optLong(
                    "duplicateEventSuppressionMs",
                    DoomscrollConfig.DEFAULT_DUPLICATE_SUPPRESSION_MS
                ),
                shortDwellThresholdSeconds = obj.optInt(
                    "shortDwellThresholdSeconds",
                    DoomscrollConfig.DEFAULT_SHORT_DWELL_THRESHOLD_SECONDS
                ),
                reopeningWindowMinutes = obj.optInt(
                    "reopeningWindowMinutes",
                    DoomscrollConfig.DEFAULT_REOPENING_WINDOW_MINUTES
                ),
                scrollRateWindowSeconds = obj.optInt(
                    "scrollRateWindowSeconds",
                    DoomscrollConfig.DEFAULT_SCROLL_RATE_WINDOW_SECONDS
                ),
                normalThresholdMax = obj.optInt("normalThresholdMax", 29),
                lowThresholdMax = obj.optInt("lowThresholdMax", 49),
                moderateThresholdMax = obj.optInt("moderateThresholdMax", 69),
                highThresholdMax = obj.optInt("highThresholdMax", 84),
                gentleReminderCooldownSeconds = obj.optInt(
                    "gentleReminderCooldownSeconds",
                    DoomscrollConfig.DEFAULT_REMINDER_COOLDOWN_SECONDS
                ),
                strongInterventionCooldownSeconds = obj.optInt(
                    "strongInterventionCooldownSeconds",
                    DoomscrollConfig.DEFAULT_STRONG_COOLDOWN_SECONDS
                ),
                criticalInterventionCooldownSeconds = obj.optInt(
                    "criticalInterventionCooldownSeconds",
                    DoomscrollConfig.DEFAULT_CRITICAL_COOLDOWN_SECONDS
                )
            ).validated()
        } catch (_: Exception) {
            DoomscrollConfig()
        }
    }

    private fun toJson(c: DoomscrollConfig): JSONObject {
        return JSONObject().apply {
            put("enabled", c.enabled)
            put("tiktokEnabled", c.tiktokEnabled)
            put("instagramEnabled", c.instagramEnabled)
            put("facebookEnabled", c.facebookEnabled)
            put("xEnabled", c.xEnabled)
            put("intendedDurationMinutes", c.intendedDurationMinutes)
            put("strictTimeLimitEnabled", c.strictTimeLimitEnabled)
            put("shortInterruptionMaxSeconds", c.shortInterruptionMaxSeconds)
            put("meaningfulBreakSeconds", c.meaningfulBreakSeconds)
            put("scoringIntervalSeconds", c.scoringIntervalSeconds)
            put("minimumFeedConfidence", c.minimumFeedConfidence.toDouble())
            put("minimumSessionDurationSeconds", c.minimumSessionDurationSeconds)
            put("duplicateEventSuppressionMs", c.duplicateEventSuppressionMs)
            put("shortDwellThresholdSeconds", c.shortDwellThresholdSeconds)
            put("reopeningWindowMinutes", c.reopeningWindowMinutes)
            put("scrollRateWindowSeconds", c.scrollRateWindowSeconds)
            put("normalThresholdMax", c.normalThresholdMax)
            put("lowThresholdMax", c.lowThresholdMax)
            put("moderateThresholdMax", c.moderateThresholdMax)
            put("highThresholdMax", c.highThresholdMax)
            put("gentleReminderCooldownSeconds", c.gentleReminderCooldownSeconds)
            put("strongInterventionCooldownSeconds", c.strongInterventionCooldownSeconds)
            put("criticalInterventionCooldownSeconds", c.criticalInterventionCooldownSeconds)
        }
    }
}
