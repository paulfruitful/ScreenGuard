package com.paulfruitful.screenguard.cooloff

import android.content.Context
import org.json.JSONObject

enum class InstrumentalCategory(val displayName: String) {
    SOFT_PIANO("Soft Piano"),
    AMBIENT("Ambient"),
    GENTLE_ACOUSTIC("Gentle Acoustic"),
    CALM_CINEMATIC("Calm Cinematic"),
    WORSHIP_INSTRUMENTAL("Worship Instrumental");

    companion object {
        fun fromString(raw: String?): InstrumentalCategory {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SOFT_PIANO
        }
    }
}

/**
 * Centralized configuration for the Cool-Off Room and repeated-trigger escalation policy.
 *
 * Defaults:
 * - Trigger threshold: 3 activations (configurable 2..5)
 * - Detection window: 3 minutes (configurable 2..5)
 * - Session duration: 3 minutes (supports 1, 3, or 5 minutes)
 */
data class CoolOffPolicyConfig(
    val triggerThreshold: Int = DEFAULT_TRIGGER_THRESHOLD,
    val detectionWindowMinutes: Int = DEFAULT_DETECTION_WINDOW_MINUTES,
    val defaultDurationMinutes: Int = DEFAULT_SESSION_DURATION_MINUTES,
    val audioCategory: InstrumentalCategory = InstrumentalCategory.SOFT_PIANO,
    val audioMuted: Boolean = false,
    val audioVolume: Float = 0.55f
) {
    val detectionWindowMs: Long
        get() = detectionWindowMinutes.coerceIn(MIN_WINDOW_MINUTES, MAX_WINDOW_MINUTES) * 60_000L

    val safeThreshold: Int
        get() = triggerThreshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    val safeDurationMinutes: Int
        get() = when (defaultDurationMinutes) {
            1, 3, 5 -> defaultDurationMinutes
            else -> DEFAULT_SESSION_DURATION_MINUTES
        }

    companion object {
        const val MIN_THRESHOLD = 2
        const val MAX_THRESHOLD = 5
        const val DEFAULT_TRIGGER_THRESHOLD = 3

        const val MIN_WINDOW_MINUTES = 2
        const val MAX_WINDOW_MINUTES = 5
        const val DEFAULT_DETECTION_WINDOW_MINUTES = 3

        const val DEFAULT_SESSION_DURATION_MINUTES = 3
        val SUPPORTED_DURATIONS_MINUTES = listOf(1, 3, 5)
    }
}

object CoolOffConfigStore {
    private const val PREFS_NAME = "screenguard_cooloff_store"
    private const val KEY_CONFIG = "cooloff_policy_config_v1"

    private val lock = Any()

    @Volatile
    private var cached: CoolOffPolicyConfig? = null

    fun load(context: Context): CoolOffPolicyConfig {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = sp.getString(KEY_CONFIG, null)
            val loaded = if (raw.isNullOrBlank()) {
                CoolOffPolicyConfig()
            } else {
                try {
                    val obj = JSONObject(raw)
                    CoolOffPolicyConfig(
                        triggerThreshold = obj.optInt(
                            "triggerThreshold",
                            CoolOffPolicyConfig.DEFAULT_TRIGGER_THRESHOLD
                        ).coerceIn(CoolOffPolicyConfig.MIN_THRESHOLD, CoolOffPolicyConfig.MAX_THRESHOLD),
                        detectionWindowMinutes = obj.optInt(
                            "detectionWindowMinutes",
                            CoolOffPolicyConfig.DEFAULT_DETECTION_WINDOW_MINUTES
                        ).coerceIn(CoolOffPolicyConfig.MIN_WINDOW_MINUTES, CoolOffPolicyConfig.MAX_WINDOW_MINUTES),
                        defaultDurationMinutes = obj.optInt(
                            "defaultDurationMinutes",
                            CoolOffPolicyConfig.DEFAULT_SESSION_DURATION_MINUTES
                        ).let { if (it in CoolOffPolicyConfig.SUPPORTED_DURATIONS_MINUTES) it else 3 },
                        audioCategory = InstrumentalCategory.fromString(obj.optString("audioCategory")),
                        audioMuted = obj.optBoolean("audioMuted", false),
                        audioVolume = obj.optDouble("audioVolume", 0.55).toFloat().coerceIn(0f, 1f)
                    )
                } catch (_: Exception) {
                    CoolOffPolicyConfig()
                }
            }
            cached = loaded
            return loaded
        }
    }

    fun save(context: Context, config: CoolOffPolicyConfig): CoolOffPolicyConfig {
        val normalized = CoolOffPolicyConfig(
            triggerThreshold = config.safeThreshold,
            detectionWindowMinutes = config.detectionWindowMinutes.coerceIn(
                CoolOffPolicyConfig.MIN_WINDOW_MINUTES,
                CoolOffPolicyConfig.MAX_WINDOW_MINUTES
            ),
            defaultDurationMinutes = config.safeDurationMinutes,
            audioCategory = config.audioCategory,
            audioMuted = config.audioMuted,
            audioVolume = config.audioVolume.coerceIn(0f, 1f)
        )
        synchronized(lock) {
            cached = normalized
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val obj = JSONObject().apply {
                put("triggerThreshold", normalized.triggerThreshold)
                put("detectionWindowMinutes", normalized.detectionWindowMinutes)
                put("defaultDurationMinutes", normalized.defaultDurationMinutes)
                put("audioCategory", normalized.audioCategory.name)
                put("audioMuted", normalized.audioMuted)
                put("audioVolume", normalized.audioVolume.toDouble())
            }
            sp.edit().putString(KEY_CONFIG, obj.toString()).apply()
        }
        return normalized
    }

    fun updateFromJson(context: Context, patchJson: String): CoolOffPolicyConfig {
        val current = load(context)
        val patch = try {
            JSONObject(patchJson)
        } catch (_: Exception) {
            JSONObject()
        }
        val next = current.copy(
            triggerThreshold = if (patch.has("triggerThreshold")) {
                patch.optInt("triggerThreshold", current.triggerThreshold)
            } else current.triggerThreshold,
            detectionWindowMinutes = if (patch.has("detectionWindowMinutes")) {
                patch.optInt("detectionWindowMinutes", current.detectionWindowMinutes)
            } else current.detectionWindowMinutes,
            defaultDurationMinutes = if (patch.has("defaultDurationMinutes")) {
                patch.optInt("defaultDurationMinutes", current.defaultDurationMinutes)
            } else current.defaultDurationMinutes,
            audioCategory = if (patch.has("audioCategory")) {
                InstrumentalCategory.fromString(patch.optString("audioCategory"))
            } else current.audioCategory,
            audioMuted = if (patch.has("audioMuted")) {
                patch.optBoolean("audioMuted", current.audioMuted)
            } else current.audioMuted,
            audioVolume = if (patch.has("audioVolume")) {
                patch.optDouble("audioVolume", current.audioVolume.toDouble()).toFloat()
            } else current.audioVolume
        )
        return save(context, next)
    }
}
