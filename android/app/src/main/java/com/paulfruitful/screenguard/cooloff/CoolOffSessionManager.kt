package com.paulfruitful.screenguard.cooloff

import android.content.Context
import com.paulfruitful.screenguard.doomscroll.BehaviouralProtectionCoordinator
import com.paulfruitful.screenguard.storage.ActivityLogRecord
import com.paulfruitful.screenguard.storage.LocalStorage
import org.json.JSONObject
import java.util.UUID

data class CoolOffSessionState(
    val sessionId: String,
    val exerciseId: String,
    val scriptureReference: String,
    val durationSeconds: Int,
    val elapsedSeconds: Int,
    val completedInteractions: Int,
    val isCompleted: Boolean,
    val startedAtWallMs: Long,
    val audioCategory: InstrumentalCategory,
    val audioMuted: Boolean,
    val audioVolume: Float
)

/**
 * Manages the active Cool-Off session lifecycle, persistence across Activity recreation /
 * process death, and completion validation.
 */
object CoolOffSessionManager {
    private const val PREFS_NAME = "screenguard_cooloff_store"
    private const val KEY_ACTIVE_SESSION = "cooloff_active_session_v1"
    private const val KEY_ROTATION_COUNTER = "cooloff_rotation_counter_v1"

    private val lock = Any()

    fun getOrCreateSession(
        context: Context,
        requestedDurationMinutes: Int? = null
    ): CoolOffSessionState {
        synchronized(lock) {
            val existing = loadLocked(context)
            if (existing != null && !existing.isCompleted) {
                return existing
            }
            return createNewSessionLocked(context, requestedDurationMinutes)
        }
    }

    fun startFreshSession(
        context: Context,
        durationMinutes: Int? = null,
        exerciseId: String? = null
    ): CoolOffSessionState {
        synchronized(lock) {
            return createNewSessionLocked(context, durationMinutes, exerciseId)
        }
    }

    fun updateProgress(
        context: Context,
        elapsedSeconds: Int,
        completedInteractions: Int
    ): CoolOffSessionState? {
        synchronized(lock) {
            val current = loadLocked(context) ?: return null
            val safeElapsed = elapsedSeconds.coerceIn(0, current.durationSeconds)
            val updated = current.copy(
                elapsedSeconds = safeElapsed,
                completedInteractions = completedInteractions.coerceAtLeast(current.completedInteractions)
            )
            saveLocked(context, updated)
            return updated
        }
    }

    fun updateAudioPreferences(
        context: Context,
        category: InstrumentalCategory,
        muted: Boolean,
        volume: Float
    ): CoolOffSessionState? {
        synchronized(lock) {
            val cfg = CoolOffConfigStore.load(context)
            CoolOffConfigStore.save(
                context,
                cfg.copy(
                    audioCategory = category,
                    audioMuted = muted,
                    audioVolume = volume.coerceIn(0f, 1f)
                )
            )
            val current = loadLocked(context) ?: return null
            val updated = current.copy(
                audioCategory = category,
                audioMuted = muted,
                audioVolume = volume.coerceIn(0f, 1f)
            )
            saveLocked(context, updated)
            return updated
        }
    }

    /**
     * Validates completion criteria against [CoolOffDrillEngine].
     * Only marks completed and clears repeated-trigger escalation when criteria are genuinely met.
     */
    fun tryCompleteSession(
        context: Context,
        elapsedSeconds: Int,
        completedInteractions: Int
    ): Boolean {
        synchronized(lock) {
            val current = loadLocked(context) ?: return false
            val exercise = CoolOffDrillEngine.findById(current.exerciseId)
            val valid = CoolOffDrillEngine.isCompletionValid(
                exercise = exercise,
                elapsedSec = elapsedSeconds,
                totalSec = current.durationSeconds,
                completedInteractions = completedInteractions
            )
            if (!valid) {
                return false
            }
            val completed = current.copy(
                elapsedSeconds = current.durationSeconds,
                completedInteractions = completedInteractions,
                isCompleted = true
            )
            saveLocked(context, completed)
            TriggerFrequencyTracker.onCoolOffSessionCompleted(context)
            try {
                BehaviouralProtectionCoordinator.onCoolOffSessionCompleted(context)
            } catch (_: Exception) {
            }

            try {
                LocalStorage.appendActivityLog(
                    context,
                    ActivityLogRecord(
                        type = "SERVICE_STATE",
                        title = "Cool-Off Session Completed (${exercise.name})",
                        detail = "Completed ${current.durationSeconds / 60}-minute Cool-Off session. Protection remains active.",
                        packageName = context.packageName
                    )
                )
            } catch (_: Exception) {
            }
            return true
        }
    }

    private fun createNewSessionLocked(
        context: Context,
        requestedDurationMinutes: Int? = null,
        requestedExerciseId: String? = null
    ): CoolOffSessionState {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val config = CoolOffConfigStore.load(context)
        val durationMin = if (requestedDurationMinutes != null && requestedDurationMinutes in 1..60) {
            requestedDurationMinutes
        } else {
            config.safeDurationMinutes
        }
        val rotation = sp.getInt(KEY_ROTATION_COUNTER, 0)
        sp.edit().putInt(KEY_ROTATION_COUNTER, rotation + 1).apply()

        val exercise = if (!requestedExerciseId.isNullOrBlank()) {
            CoolOffDrillEngine.findById(requestedExerciseId)
        } else {
            CoolOffDrillEngine.selectForDuration(durationMin, rotation)
        }
        val scripture = CoolOffScriptureRepository.findByReference(exercise.scriptureReference)

        val session = CoolOffSessionState(
            sessionId = UUID.randomUUID().toString(),
            exerciseId = exercise.id,
            scriptureReference = scripture.reference,
            durationSeconds = durationMin * 60,
            elapsedSeconds = 0,
            completedInteractions = 0,
            isCompleted = false,
            startedAtWallMs = System.currentTimeMillis(),
            audioCategory = config.audioCategory,
            audioMuted = config.audioMuted,
            audioVolume = config.audioVolume
        )
        saveLocked(context, session)
        return session
    }

    private fun loadLocked(context: Context): CoolOffSessionState? {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_ACTIVE_SESSION, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            CoolOffSessionState(
                sessionId = obj.optString("sessionId", UUID.randomUUID().toString()),
                exerciseId = obj.optString("exerciseId", "urge_surfing"),
                scriptureReference = obj.optString("scriptureReference", "1 Corinthians 10:13"),
                durationSeconds = obj.optInt("durationSeconds", 180).coerceAtLeast(60),
                elapsedSeconds = obj.optInt("elapsedSeconds", 0).coerceAtLeast(0),
                completedInteractions = obj.optInt("completedInteractions", 0).coerceAtLeast(0),
                isCompleted = obj.optBoolean("isCompleted", false),
                startedAtWallMs = obj.optLong("startedAtWallMs", System.currentTimeMillis()),
                audioCategory = InstrumentalCategory.fromString(obj.optString("audioCategory")),
                audioMuted = obj.optBoolean("audioMuted", false),
                audioVolume = obj.optDouble("audioVolume", 0.55).toFloat().coerceIn(0f, 1f)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun saveLocked(context: Context, session: CoolOffSessionState) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val obj = JSONObject().apply {
            put("sessionId", session.sessionId)
            put("exerciseId", session.exerciseId)
            put("scriptureReference", session.scriptureReference)
            put("durationSeconds", session.durationSeconds)
            put("elapsedSeconds", session.elapsedSeconds)
            put("completedInteractions", session.completedInteractions)
            put("isCompleted", session.isCompleted)
            put("startedAtWallMs", session.startedAtWallMs)
            put("audioCategory", session.audioCategory.name)
            put("audioMuted", session.audioMuted)
            put("audioVolume", session.audioVolume.toDouble())
        }
        sp.edit().putString(KEY_ACTIVE_SESSION, obj.toString()).apply()
    }
}
