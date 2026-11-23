package com.paulfruitful.screenguard.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UserPreferencesData(
    val protectionEnabled: Boolean = true,
    val excludedPackages: Set<String> = setOf(
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard"
    ),
    val samplingIntervalMs: Long = 2000L,
    val debounceDelayMs: Long = 550L,
    val preferWindowCapture: Boolean = true,
    val confidenceThreshold: Float = 0.72f,
    val confirmationFramesRequired: Int = 2,
    val interventionBehavior: String = "PROTECTIVE_OVERLAY",
    val sensitivityMode: String = "BALANCED",
    val interveneOnSuggestiveInStrict: Boolean = true,
    val allowManualDismiss: Boolean = true,
    val dismissCooldownSeconds: Int = 12,
    val mockClassifierProfile: String = "DETERMINISTIC_SAFE",
    val allowSelfPackageInDiagnostics: Boolean = true
)

data class ProtectionStatsData(
    val totalProtectionEvents: Int = 0,
    val protectedPackages: Set<String> = emptySet(),
    val totalDismissals: Int = 0,
    val totalReturnToSafetyClicks: Int = 0,
    val totalAutoRecoveries: Int = 0
)

data class ActivityLogRecord(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val title: String,
    val detail: String,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val errorCode: String = "NONE",
    val captureMethod: String = "",
    val category: String = "",
    val nudityScore: Float = 0f,
    val explicitScore: Float = 0f,
    val suggestiveScore: Float = 0f,
    val safeScore: Float = 1f,
    val confirmedIntervention: Boolean = false
)

data class AppCompatibilityEntry(
    val packageName: String,
    val displayName: String,
    val category: String,
    val outcome: String,
    val lastTestedAt: Long?,
    val captureMethod: String,
    val resolution: String,
    val durationMs: Long,
    val errorCode: String,
    val notes: String
)

object LocalStorage {
    private const val PREFS_NAME = "screenguard_local_store"
    private const val KEY_USER_PREFS = "user_preferences_v1"
    private const val KEY_ACTIVITY_LOGS = "activity_logs_v1"
    private const val KEY_COMPATIBILITY_RECORDS = "compatibility_records_v1"
    private const val KEY_EVALUATION_REPORT = "evaluation_report_v1"
    private const val KEY_PROTECTION_STATS = "protection_stats_v1"
    private const val MAX_LOG_ENTRIES = 180

    private val lock = Any()
    @Volatile
    private var prefsInstance: SharedPreferences? = null
    @Volatile
    private var cachedPreferences: UserPreferencesData? = null

    fun init(context: Context) {
        if (prefsInstance == null) {
            synchronized(lock) {
                if (prefsInstance == null) {
                    prefsInstance = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    cachedPreferences = loadPreferencesInternal()
                    ensureDefaultCompatibilityRecords()
                }
            }
        }
    }

    private fun requirePrefs(context: Context? = null): SharedPreferences {
        prefsInstance?.let { return it }
        if (context != null) {
            init(context)
            return prefsInstance!!
        }
        throw IllegalStateException("LocalStorage not initialized")
    }

    fun getPreferences(context: Context? = null): UserPreferencesData {
        cachedPreferences?.let { return it }
        synchronized(lock) {
            if (context != null && prefsInstance == null) {
                init(context)
            }
            val loaded = loadPreferencesInternal()
            cachedPreferences = loaded
            return loaded
        }
    }

    private fun loadPreferencesInternal(): UserPreferencesData {
        val sp = prefsInstance ?: return UserPreferencesData()
        val raw = sp.getString(KEY_USER_PREFS, null) ?: return UserPreferencesData()
        return try {
            val obj = JSONObject(raw)
            val excludedArr = obj.optJSONArray("excludedPackages")
            val excludedSet = mutableSetOf<String>()
            if (excludedArr != null) {
                for (i in 0 until excludedArr.length()) {
                    val pkg = excludedArr.optString(i).trim()
                    if (pkg.isNotEmpty()) {
                        excludedSet.add(pkg)
                    }
                }
            } else {
                excludedSet.addAll(UserPreferencesData().excludedPackages)
            }
            UserPreferencesData(
                protectionEnabled = obj.optBoolean("protectionEnabled", true),
                excludedPackages = excludedSet,
                samplingIntervalMs = obj.optLong("samplingIntervalMs", 2000L).coerceAtLeast(1050L),
                debounceDelayMs = obj.optLong("debounceDelayMs", 550L).coerceIn(150L, 5000L),
                preferWindowCapture = obj.optBoolean("preferWindowCapture", true),
                confidenceThreshold = obj.optDouble("confidenceThreshold", 0.72).toFloat().coerceIn(0.3f, 0.99f),
                confirmationFramesRequired = obj.optInt("confirmationFramesRequired", 2).coerceIn(1, 10),
                interventionBehavior = obj.optString("interventionBehavior", "PROTECTIVE_OVERLAY"),
                sensitivityMode = obj.optString("sensitivityMode", "BALANCED").uppercase(),
                interveneOnSuggestiveInStrict = obj.optBoolean("interveneOnSuggestiveInStrict", true),
                allowManualDismiss = obj.optBoolean("allowManualDismiss", true),
                dismissCooldownSeconds = obj.optInt("dismissCooldownSeconds", 12).coerceIn(3, 120),
                mockClassifierProfile = obj.optString("mockClassifierProfile", "DETERMINISTIC_SAFE"),
                allowSelfPackageInDiagnostics = obj.optBoolean("allowSelfPackageInDiagnostics", true)
            )
        } catch (_: Exception) {
            UserPreferencesData()
        }
    }

    fun updatePreferencesFromJson(context: Context, patchJson: String): UserPreferencesData {
        synchronized(lock) {
            init(context)
            val current = getPreferences(context)
            val patch = try {
                JSONObject(patchJson)
            } catch (_: Exception) {
                JSONObject()
            }

            val updatedExcluded = if (patch.has("excludedPackages")) {
                val arr = patch.optJSONArray("excludedPackages")
                val set = mutableSetOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val pkg = arr.optString(i).trim()
                        if (pkg.isNotEmpty()) set.add(pkg)
                    }
                }
                set
            } else {
                current.excludedPackages
            }

            val updated = current.copy(
                protectionEnabled = if (patch.has("protectionEnabled")) {
                    patch.optBoolean("protectionEnabled", current.protectionEnabled)
                } else current.protectionEnabled,
                excludedPackages = updatedExcluded,
                samplingIntervalMs = if (patch.has("samplingIntervalMs")) {
                    patch.optLong("samplingIntervalMs", current.samplingIntervalMs).coerceAtLeast(1050L)
                } else current.samplingIntervalMs,
                debounceDelayMs = if (patch.has("debounceDelayMs")) {
                    patch.optLong("debounceDelayMs", current.debounceDelayMs).coerceIn(150L, 5000L)
                } else current.debounceDelayMs,
                preferWindowCapture = if (patch.has("preferWindowCapture")) {
                    patch.optBoolean("preferWindowCapture", current.preferWindowCapture)
                } else current.preferWindowCapture,
                confidenceThreshold = if (patch.has("confidenceThreshold")) {
                    patch.optDouble("confidenceThreshold", current.confidenceThreshold.toDouble())
                        .toFloat()
                        .coerceIn(0.3f, 0.99f)
                } else current.confidenceThreshold,
                confirmationFramesRequired = if (patch.has("confirmationFramesRequired")) {
                    patch.optInt("confirmationFramesRequired", current.confirmationFramesRequired)
                        .coerceIn(1, 10)
                } else current.confirmationFramesRequired,
                interventionBehavior = if (patch.has("interventionBehavior")) {
                    patch.optString("interventionBehavior", current.interventionBehavior)
                } else current.interventionBehavior,
                sensitivityMode = if (patch.has("sensitivityMode")) {
                    val rawMode = patch.optString("sensitivityMode", current.sensitivityMode).uppercase()
                    if (rawMode == "STRICT") "STRICT" else "BALANCED"
                } else current.sensitivityMode,
                interveneOnSuggestiveInStrict = if (patch.has("interveneOnSuggestiveInStrict")) {
                    patch.optBoolean("interveneOnSuggestiveInStrict", current.interveneOnSuggestiveInStrict)
                } else current.interveneOnSuggestiveInStrict,
                allowManualDismiss = if (patch.has("allowManualDismiss")) {
                    patch.optBoolean("allowManualDismiss", current.allowManualDismiss)
                } else current.allowManualDismiss,
                dismissCooldownSeconds = if (patch.has("dismissCooldownSeconds")) {
                    patch.optInt("dismissCooldownSeconds", current.dismissCooldownSeconds).coerceIn(3, 120)
                } else current.dismissCooldownSeconds,
                mockClassifierProfile = if (patch.has("mockClassifierProfile")) {
                    patch.optString("mockClassifierProfile", current.mockClassifierProfile)
                } else current.mockClassifierProfile,
                allowSelfPackageInDiagnostics = if (patch.has("allowSelfPackageInDiagnostics")) {
                    patch.optBoolean("allowSelfPackageInDiagnostics", current.allowSelfPackageInDiagnostics)
                } else current.allowSelfPackageInDiagnostics
            )

            savePreferencesInternal(updated)
            cachedPreferences = updated
            return updated
        }
    }

    fun setProtectionEnabled(context: Context, enabled: Boolean): UserPreferencesData {
        synchronized(lock) {
            init(context)
            val updated = getPreferences(context).copy(protectionEnabled = enabled)
            savePreferencesInternal(updated)
            cachedPreferences = updated
            return updated
        }
    }

    private fun savePreferencesInternal(prefs: UserPreferencesData) {
        val sp = prefsInstance ?: return
        val obj = JSONObject().apply {
            put("protectionEnabled", prefs.protectionEnabled)
            put("excludedPackages", JSONArray(prefs.excludedPackages.toList()))
            put("samplingIntervalMs", prefs.samplingIntervalMs)
            put("debounceDelayMs", prefs.debounceDelayMs)
            put("preferWindowCapture", prefs.preferWindowCapture)
            put("confidenceThreshold", prefs.confidenceThreshold.toDouble())
            put("confirmationFramesRequired", prefs.confirmationFramesRequired)
            put("interventionBehavior", prefs.interventionBehavior)
            put("sensitivityMode", prefs.sensitivityMode)
            put("interveneOnSuggestiveInStrict", prefs.interveneOnSuggestiveInStrict)
            put("allowManualDismiss", prefs.allowManualDismiss)
            put("dismissCooldownSeconds", prefs.dismissCooldownSeconds)
            put("mockClassifierProfile", prefs.mockClassifierProfile)
            put("allowSelfPackageInDiagnostics", prefs.allowSelfPackageInDiagnostics)
        }
        sp.edit().putString(KEY_USER_PREFS, obj.toString()).apply()
    }

    fun getProtectionStats(context: Context? = null): ProtectionStatsData {
        synchronized(lock) {
            val sp = try {
                requirePrefs(context)
            } catch (_: Exception) {
                return ProtectionStatsData()
            }
            val raw = sp.getString(KEY_PROTECTION_STATS, null) ?: return ProtectionStatsData()
            return try {
                val obj = JSONObject(raw)
                val pkgArr = obj.optJSONArray("protectedPackages")
                val pkgSet = mutableSetOf<String>()
                if (pkgArr != null) {
                    for (i in 0 until pkgArr.length()) {
                        val pkg = pkgArr.optString(i).trim()
                        if (pkg.isNotEmpty()) pkgSet.add(pkg)
                    }
                }
                ProtectionStatsData(
                    totalProtectionEvents = obj.optInt("totalProtectionEvents", 0),
                    protectedPackages = pkgSet,
                    totalDismissals = obj.optInt("totalDismissals", 0),
                    totalReturnToSafetyClicks = obj.optInt("totalReturnToSafetyClicks", 0),
                    totalAutoRecoveries = obj.optInt("totalAutoRecoveries", 0)
                )
            } catch (_: Exception) {
                ProtectionStatsData()
            }
        }
    }

    private fun saveProtectionStatsInternal(sp: SharedPreferences, stats: ProtectionStatsData) {
        val obj = JSONObject().apply {
            put("totalProtectionEvents", stats.totalProtectionEvents)
            put("protectedPackages", JSONArray(stats.protectedPackages.toList()))
            put("totalDismissals", stats.totalDismissals)
            put("totalReturnToSafetyClicks", stats.totalReturnToSafetyClicks)
            put("totalAutoRecoveries", stats.totalAutoRecoveries)
        }
        sp.edit().putString(KEY_PROTECTION_STATS, obj.toString()).apply()
    }

    fun recordProtectionTriggered(context: Context? = null, packageName: String): ProtectionStatsData {
        synchronized(lock) {
            val sp = try {
                requirePrefs(context)
            } catch (_: Exception) {
                return ProtectionStatsData()
            }
            val current = getProtectionStats(context)
            val nextPackages = current.protectedPackages.toMutableSet()
            if (packageName.isNotBlank() && packageName != "None") {
                nextPackages.add(packageName)
            }
            val updated = current.copy(
                totalProtectionEvents = current.totalProtectionEvents + 1,
                protectedPackages = nextPackages
            )
            saveProtectionStatsInternal(sp, updated)
            return updated
        }
    }

    fun recordProtectionDismissed(context: Context? = null): ProtectionStatsData {
        synchronized(lock) {
            val sp = try {
                requirePrefs(context)
            } catch (_: Exception) {
                return ProtectionStatsData()
            }
            val current = getProtectionStats(context)
            val updated = current.copy(totalDismissals = current.totalDismissals + 1)
            saveProtectionStatsInternal(sp, updated)
            return updated
        }
    }

    fun recordReturnToSafetyClicked(context: Context? = null): ProtectionStatsData {
        synchronized(lock) {
            val sp = try {
                requirePrefs(context)
            } catch (_: Exception) {
                return ProtectionStatsData()
            }
            val current = getProtectionStats(context)
            val updated = current.copy(
                totalReturnToSafetyClicks = current.totalReturnToSafetyClicks + 1
            )
            saveProtectionStatsInternal(sp, updated)
            return updated
        }
    }

    fun recordAutoRecovery(context: Context? = null): ProtectionStatsData {
        synchronized(lock) {
            val sp = try {
                requirePrefs(context)
            } catch (_: Exception) {
                return ProtectionStatsData()
            }
            val current = getProtectionStats(context)
            val updated = current.copy(totalAutoRecoveries = current.totalAutoRecoveries + 1)
            saveProtectionStatsInternal(sp, updated)
            return updated
        }
    }

    fun appendActivityLog(context: Context? = null, record: ActivityLogRecord) {
        synchronized(lock) {
            val sp = try {
                requirePrefs(context)
            } catch (_: Exception) {
                return
            }
            val existing = getActivityLogsInternal(sp, MAX_LOG_ENTRIES - 1).toMutableList()
            existing.add(0, record)
            val arr = JSONArray()
            existing.take(MAX_LOG_ENTRIES).forEach { item ->
                arr.put(activityRecordToJson(item))
            }
            sp.edit().putString(KEY_ACTIVITY_LOGS, arr.toString()).apply()
        }
    }

    fun getActivityLogs(context: Context? = null, limit: Int = 120): List<ActivityLogRecord> {
        synchronized(lock) {
            val sp = try {
                requirePrefs(context)
            } catch (_: Exception) {
                return emptyList()
            }
            return getActivityLogsInternal(sp, limit)
        }
    }

    fun clearActivityLogs(context: Context? = null) {
        synchronized(lock) {
            val sp = try {
                requirePrefs(context)
            } catch (_: Exception) {
                return
            }
            sp.edit().remove(KEY_ACTIVITY_LOGS).apply()
        }
    }

    private fun getActivityLogsInternal(sp: SharedPreferences, limit: Int): List<ActivityLogRecord> {
        val raw = sp.getString(KEY_ACTIVITY_LOGS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<ActivityLogRecord>(minOf(arr.length(), limit))
            val count = minOf(arr.length(), limit)
            for (i in 0 until count) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    ActivityLogRecord(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        type = obj.optString("type", "SERVICE_STATE"),
                        title = obj.optString("title", ""),
                        detail = obj.optString("detail", ""),
                        packageName = obj.optString("packageName", "none"),
                        timestamp = obj.optLong("timestamp", 0L),
                        durationMs = obj.optLong("durationMs", 0L),
                        width = obj.optInt("width", 0),
                        height = obj.optInt("height", 0),
                        errorCode = obj.optString("errorCode", "NONE"),
                        captureMethod = obj.optString("captureMethod", ""),
                        category = obj.optString("category", ""),
                        nudityScore = obj.optDouble("nudityScore", 0.0).toFloat(),
                        explicitScore = obj.optDouble("explicitScore", 0.0).toFloat(),
                        suggestiveScore = obj.optDouble("suggestiveScore", 0.0).toFloat(),
                        safeScore = obj.optDouble("safeScore", 1.0).toFloat(),
                        confirmedIntervention = obj.optBoolean("confirmedIntervention", false)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun activityRecordToJson(item: ActivityLogRecord): JSONObject {
        return JSONObject().apply {
            put("id", item.id)
            put("type", item.type)
            put("title", item.title)
            put("detail", item.detail)
            put("packageName", item.packageName)
            put("timestamp", item.timestamp)
            put("durationMs", item.durationMs)
            put("width", item.width)
            put("height", item.height)
            put("errorCode", item.errorCode)
            put("captureMethod", item.captureMethod)
            put("category", item.category)
            put("nudityScore", item.nudityScore.toDouble())
            put("explicitScore", item.explicitScore.toDouble())
            put("suggestiveScore", item.suggestiveScore.toDouble())
            put("safeScore", item.safeScore.toDouble())
            put("confirmedIntervention", item.confirmedIntervention)
        }
    }

    private fun ensureDefaultCompatibilityRecords() {
        val sp = prefsInstance ?: return
        val defaults = listOf(
            AppCompatibilityEntry(
                packageName = "com.android.settings",
                displayName = "Android Settings",
                category = "System Settings",
                outcome = "UNTESTED",
                lastTestedAt = null,
                captureMethod = "N/A",
                resolution = "N/A",
                durationMs = 0L,
                errorCode = "NONE",
                notes = "Standard system activities allow accessibility capture; credential/PIN subscreens enforce FLAG_SECURE."
            ),
            AppCompatibilityEntry(
                packageName = "com.android.chrome",
                displayName = "Google Chrome",
                category = "Browser",
                outcome = "UNTESTED",
                lastTestedAt = null,
                captureMethod = "N/A",
                resolution = "N/A",
                durationMs = 0L,
                errorCode = "NONE",
                notes = "Normal tabs support takeScreenshotOfWindow/takeScreenshot + TYPE_ACCESSIBILITY_OVERLAY; Incognito tabs enforce FLAG_SECURE."
            ),
            AppCompatibilityEntry(
                packageName = "com.google.android.youtube",
                displayName = "YouTube",
                category = "Video Streaming",
                outcome = "UNTESTED",
                lastTestedAt = null,
                captureMethod = "N/A",
                resolution = "N/A",
                durationMs = 0L,
                errorCode = "NONE",
                notes = "Standard video feeds are capturable & coverable; DRM-protected Movies/TV purchases block surface capture."
            ),
            AppCompatibilityEntry(
                packageName = "com.instagram.android",
                displayName = "Instagram",
                category = "Social Media",
                outcome = "UNTESTED",
                lastTestedAt = null,
                captureMethod = "N/A",
                resolution = "N/A",
                durationMs = 0L,
                errorCode = "NONE",
                notes = "Feed, Reels, and Stories windows are capturable & coverable; view-once direct media may set FLAG_SECURE."
            ),
            AppCompatibilityEntry(
                packageName = "com.zhiliaoapp.musically",
                displayName = "TikTok",
                category = "Short Video",
                outcome = "UNTESTED",
                lastTestedAt = null,
                captureMethod = "N/A",
                resolution = "N/A",
                durationMs = 0L,
                errorCode = "NONE",
                notes = "Hardware-accelerated video surface composited into window buffer on Android 11+."
            ),
            AppCompatibilityEntry(
                packageName = "com.twitter.android",
                displayName = "X (Twitter)",
                category = "Social Media",
                outcome = "UNTESTED",
                lastTestedAt = null,
                captureMethod = "N/A",
                resolution = "N/A",
                durationMs = 0L,
                errorCode = "NONE",
                notes = "Timeline, media lightbox, and video player windows support window capture and full-screen protective overlay."
            ),
            AppCompatibilityEntry(
                packageName = "com.facebook.katana",
                displayName = "Facebook",
                category = "Social Media",
                outcome = "UNTESTED",
                lastTestedAt = null,
                captureMethod = "N/A",
                resolution = "N/A",
                durationMs = 0L,
                errorCode = "NONE",
                notes = "Feed, Reels, and Watch surfaces support accessibility window capture and full-screen protective overlay."
            ),
            AppCompatibilityEntry(
                packageName = "com.sec.android.app.launcher",
                displayName = "Home Screen (One UI / System Launcher)",
                category = "Launcher",
                outcome = "UNTESTED",
                lastTestedAt = null,
                captureMethod = "N/A",
                resolution = "N/A",
                durationMs = 0L,
                errorCode = "NONE",
                notes = "Home screen workspace and app drawer windows."
            )
        )
        if (!sp.contains(KEY_COMPATIBILITY_RECORDS)) {
            saveCompatibilityRecordsInternal(defaults)
            return
        }
        val current = getCompatibilityRecords().toMutableList()
        var changed = false
        for (def in defaults) {
            if (current.none { it.packageName.equals(def.packageName, ignoreCase = true) }) {
                current.add(def)
                changed = true
            }
        }
        if (changed) {
            saveCompatibilityRecordsInternal(current)
        }
    }

    fun getCompatibilityRecords(context: Context? = null): List<AppCompatibilityEntry> {
        synchronized(lock) {
            val sp = try {
                requirePrefs(context)
            } catch (_: Exception) {
                return emptyList()
            }
            val raw = sp.getString(KEY_COMPATIBILITY_RECORDS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val list = ArrayList<AppCompatibilityEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(
                        AppCompatibilityEntry(
                            packageName = obj.optString("packageName"),
                            displayName = obj.optString("displayName"),
                            category = obj.optString("category"),
                            outcome = obj.optString("outcome", "UNTESTED"),
                            lastTestedAt = if (obj.isNull("lastTestedAt")) null else obj.optLong("lastTestedAt"),
                            captureMethod = obj.optString("captureMethod", "N/A"),
                            resolution = obj.optString("resolution", "N/A"),
                            durationMs = obj.optLong("durationMs", 0L),
                            errorCode = obj.optString("errorCode", "NONE"),
                            notes = obj.optString("notes", "")
                        )
                    )
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun updateCompatibilityRecordFromJson(context: Context, recordJson: String): List<AppCompatibilityEntry> {
        synchronized(lock) {
            init(context)
            val current = getCompatibilityRecords(context).toMutableList()
            return try {
                val obj = JSONObject(recordJson)
                val pkg = obj.optString("packageName")
                val entry = AppCompatibilityEntry(
                    packageName = pkg,
                    displayName = obj.optString("displayName", pkg),
                    category = obj.optString("category", "Application"),
                    outcome = obj.optString("outcome", "UNTESTED"),
                    lastTestedAt = if (obj.isNull("lastTestedAt")) System.currentTimeMillis() else obj.optLong("lastTestedAt"),
                    captureMethod = obj.optString("captureMethod", "N/A"),
                    resolution = obj.optString("resolution", "N/A"),
                    durationMs = obj.optLong("durationMs", 0L),
                    errorCode = obj.optString("errorCode", "NONE"),
                    notes = obj.optString("notes", "")
                )
                val idx = current.indexOfFirst { it.packageName == pkg }
                if (idx >= 0) {
                    current[idx] = entry
                } else {
                    current.add(entry)
                }
                saveCompatibilityRecordsInternal(current)
                current
            } catch (_: Exception) {
                current
            }
        }
    }

    fun recordAutomaticCompatibilityOutcome(
        context: Context? = null,
        packageName: String,
        succeeded: Boolean,
        captureMethod: String,
        width: Int,
        height: Int,
        durationMs: Long,
        errorCode: String
    ) {
        if (packageName.isBlank() || packageName == "none") return
        synchronized(lock) {
            val current = getCompatibilityRecords(context).toMutableList()
            val normalizedTarget = when {
                packageName.contains("launcher", ignoreCase = true) ||
                    packageName == "com.miui.home" ||
                    packageName == "com.google.android.apps.nexuslauncher" ->
                    "com.sec.android.app.launcher"
                packageName == "com.ss.android.ugc.trill" -> "com.zhiliaoapp.musically"
                else -> packageName
            }
            val idx = current.indexOfFirst {
                it.packageName.equals(normalizedTarget, ignoreCase = true) ||
                    it.packageName.equals(packageName, ignoreCase = true)
            }
            if (idx < 0) return

            val existing = current[idx]
            val outcome = if (succeeded) {
                if (captureMethod.contains("WINDOW", ignoreCase = true)) {
                    "SUPPORTED_WINDOW_API"
                } else {
                    "SUPPORTED_DISPLAY_API"
                }
            } else {
                if (errorCode == "SECURE_WINDOW") {
                    "REJECTED_FLAG_SECURE"
                } else {
                    "FAILED_OTHER"
                }
            }
            current[idx] = existing.copy(
                outcome = outcome,
                lastTestedAt = System.currentTimeMillis(),
                captureMethod = captureMethod,
                resolution = if (width > 0 && height > 0) "${width}x${height}" else "N/A",
                durationMs = durationMs,
                errorCode = errorCode
            )
            saveCompatibilityRecordsInternal(current)
        }
    }

    private fun saveCompatibilityRecordsInternal(list: List<AppCompatibilityEntry>) {
        val sp = prefsInstance ?: return
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("packageName", item.packageName)
                    put("displayName", item.displayName)
                    put("category", item.category)
                    put("outcome", item.outcome)
                    if (item.lastTestedAt != null) {
                        put("lastTestedAt", item.lastTestedAt)
                    } else {
                        put("lastTestedAt", JSONObject.NULL)
                    }
                    put("captureMethod", item.captureMethod)
                    put("resolution", item.resolution)
                    put("durationMs", item.durationMs)
                    put("errorCode", item.errorCode)
                    put("notes", item.notes)
                }
            )
        }
        sp.edit().putString(KEY_COMPATIBILITY_RECORDS, arr.toString()).apply()
    }

    fun saveEvaluationReportJson(context: Context? = null, reportJson: String) {
        synchronized(lock) {
            val sp = try {
                requirePrefs(context)
            } catch (_: Exception) {
                return
            }
            sp.edit().putString(KEY_EVALUATION_REPORT, reportJson).apply()
        }
    }

    fun getEvaluationReportJson(context: Context? = null): String? {
        synchronized(lock) {
            val sp = try {
                requirePrefs(context)
            } catch (_: Exception) {
                return null
            }
            return sp.getString(KEY_EVALUATION_REPORT, null)
        }
    }
}
