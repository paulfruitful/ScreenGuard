package com.paulfruitful.screenguard.doomscroll

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class PersistedActiveSession(
    val sessionId: String,
    val packageName: String,
    val startTimestampMs: Long,
    val activeDurationMs: Long,
    val videoTransitionCount: Int,
    val shortTransitionCount: Int,
    val reopeningCount: Int,
    val lastActiveMs: Long
)

/**
 * Local-only privacy-preserving persistence for active session recovery and historical
 * feed session metrics (Section 13).
 *
 * Strictly stores ONLY aggregate numerical telemetry:
 * - Zero video content
 * - Zero screenshots
 * - Zero private messages or browsing history
 * - Zero external uploads
 */
object DoomscrollSessionStore {
    private const val PREFS_NAME = "screenguard_doomscroll_store"
    private const val KEY_ACTIVE_SESSION = "doomscroll_active_session_v1"
    private const val KEY_SESSION_HISTORY = "doomscroll_session_history_v1"
    private const val MAX_HISTORY_RECORDS = 40

    private val lock = Any()

    fun saveActiveSession(context: Context, snapshot: BehaviourSnapshot) {
        if (snapshot.sessionId.isBlank() || snapshot.applicationPackage.isBlank()) {
            clearActiveSession(context)
            return
        }
        synchronized(lock) {
            try {
                val obj = JSONObject().apply {
                    put("sessionId", snapshot.sessionId)
                    put("packageName", snapshot.applicationPackage)
                    put("startTimestampMs", snapshot.sessionStartTimestampMs)
                    put("activeDurationMs", snapshot.activeFeedDurationMs)
                    put("videoTransitionCount", snapshot.videoTransitionCount)
                    put("shortTransitionCount", snapshot.shortTransitionCount)
                    put("reopeningCount", snapshot.reopeningCountInWindow)
                    put("lastActiveMs", snapshot.lastUpdatedMs)
                }
                val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sp.edit().putString(KEY_ACTIVE_SESSION, obj.toString()).apply()
            } catch (_: Exception) {
            }
        }
    }

    fun loadActiveSession(context: Context): PersistedActiveSession? {
        synchronized(lock) {
            return try {
                val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = sp.getString(KEY_ACTIVE_SESSION, null) ?: return null
                val obj = JSONObject(raw)
                val id = obj.optString("sessionId", "")
                val pkg = obj.optString("packageName", "")
                if (id.isBlank() || pkg.isBlank()) return null
                PersistedActiveSession(
                    sessionId = id,
                    packageName = pkg,
                    startTimestampMs = obj.optLong("startTimestampMs", 0L),
                    activeDurationMs = obj.optLong("activeDurationMs", 0L),
                    videoTransitionCount = obj.optInt("videoTransitionCount", 0),
                    shortTransitionCount = obj.optInt("shortTransitionCount", 0),
                    reopeningCount = obj.optInt("reopeningCount", 0),
                    lastActiveMs = obj.optLong("lastActiveMs", 0L)
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun clearActiveSession(context: Context) {
        synchronized(lock) {
            try {
                val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sp.edit().remove(KEY_ACTIVE_SESSION).apply()
            } catch (_: Exception) {
            }
        }
    }

    fun appendHistory(context: Context, record: FeedSessionRecord) {
        if (record.activeDurationMs < 5_000L) return
        synchronized(lock) {
            try {
                val current = loadHistoryLocked(context).toMutableList()
                current.add(0, record)
                val trimmed = current.take(MAX_HISTORY_RECORDS)
                val arr = JSONArray()
                for (item in trimmed) {
                    arr.put(
                        JSONObject().apply {
                            put("sessionId", item.sessionId)
                            put("applicationPackage", item.applicationPackage)
                            put("startTimestamp", item.startTimestamp)
                            put("endTimestamp", item.endTimestamp)
                            put("activeDurationMs", item.activeDurationMs)
                            put("videoTransitionCount", item.videoTransitionCount)
                            put("averageDwellTimeMs", item.averageDwellTimeMs)
                            put("shortTransitionCount", item.shortTransitionCount)
                            put("reopeningCount", item.reopeningCount)
                            put("intendedDurationMinutes", item.intendedDurationMinutes)
                            put("maximumRiskScore", item.maximumRiskScore)
                            put("interventionCount", item.interventionCount)
                            put("completionStatus", item.completionStatus.name)
                        }
                    )
                }
                val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sp.edit().putString(KEY_SESSION_HISTORY, arr.toString()).apply()
            } catch (_: Exception) {
            }
        }
    }

    fun getHistory(context: Context): List<FeedSessionRecord> {
        synchronized(lock) {
            return loadHistoryLocked(context)
        }
    }

    fun getTodaySummary(context: Context, currentActiveDurationMs: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDayMs = cal.timeInMillis
        val history = getHistory(context).filter { it.startTimestamp >= startOfDayMs }
        val totalMs = history.sumOf { it.activeDurationMs } + currentActiveDurationMs.coerceAtLeast(0L)
        val count = history.size + if (currentActiveDurationMs > 0L) 1 else 0
        return Pair(count, (totalMs / 60_000L).toInt())
    }

    private fun loadHistoryLocked(context: Context): List<FeedSessionRecord> {
        return try {
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = sp.getString(KEY_SESSION_HISTORY, null) ?: return emptyList()
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val statusRaw = obj.optString("completionStatus", SessionCompletionStatus.ENDED_NATURALLY.name)
                    val status = SessionCompletionStatus.entries.firstOrNull { it.name == statusRaw }
                        ?: SessionCompletionStatus.ENDED_NATURALLY
                    add(
                        FeedSessionRecord(
                            sessionId = obj.optString("sessionId", ""),
                            applicationPackage = obj.optString("applicationPackage", ""),
                            startTimestamp = obj.optLong("startTimestamp", 0L),
                            endTimestamp = obj.optLong("endTimestamp", 0L),
                            activeDurationMs = obj.optLong("activeDurationMs", 0L),
                            videoTransitionCount = obj.optInt("videoTransitionCount", 0),
                            averageDwellTimeMs = obj.optLong("averageDwellTimeMs", 0L),
                            shortTransitionCount = obj.optInt("shortTransitionCount", 0),
                            reopeningCount = obj.optInt("reopeningCount", 0),
                            intendedDurationMinutes = obj.optInt("intendedDurationMinutes", 10),
                            maximumRiskScore = obj.optInt("maximumRiskScore", 0),
                            interventionCount = obj.optInt("interventionCount", 0),
                            completionStatus = status
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
