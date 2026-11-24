package com.paulfruitful.screenguard.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.ConcurrentHashMap

data class ResolvedWindowContext(
    val packageName: String,
    val appLabel: String,
    val windowId: Int,
    val isPasswordOrSensitiveInputActive: Boolean
)

/**
 * Resolves the active foreground application window while enforcing privacy rules:
 * - Never reads or logs text values from editable inputs or password fields.
 * - Flags when a password field has input focus so screenshot capture is skipped.
 * - Ignores transient system UI overlays (`com.android.systemui`, `miui.systemui.plugin`, IMEs),
 *   Picture-in-Picture overlays, and inactive background windows.
 */
object ActiveWindowResolver {
    private val labelCache = ConcurrentHashMap<String, String>()
    private const val SELF_PACKAGE = "com.paulfruitful.screenguard"

    fun isSelfOverlayEvent(event: AccessibilityEvent?): Boolean {
        if (event == null) return false
        val pkg = event.packageName?.toString() ?: return false
        if (pkg != SELF_PACKAGE) return false
        val cls = event.className?.toString() ?: ""
        // Only MainActivity represents the actual Screen Guard React Native application activity;
        // any FrameLayout/TextView/View event from SELF_PACKAGE is our own protective overlay.
        return !cls.contains("MainActivity", ignoreCase = true)
    }

    private val IGNORED_TRANSIENT_PACKAGES = setOf(
        "com.android.systemui",
        "miui.systemui.plugin",
        "com.miui.systemui.plugin",
        "android",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.sec.android.inputmethod",
        "com.miui.securityinputmethod",
        "com.mi.globalminusscreen",
        "com.mi.appfinder"
    )

    private val LAUNCHER_PACKAGES = setOf(
        "com.miui.home",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3"
    )

    fun isLauncherPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (LAUNCHER_PACKAGES.contains(packageName)) return true
        return packageName.contains("launcher", ignoreCase = true)
    }

    fun isTransientSystemOverlay(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        if (IGNORED_TRANSIENT_PACKAGES.contains(packageName)) return true
        val lower = packageName.lowercase()
        return lower.contains("systemui") ||
            lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.endsWith(".overlay")
    }

    @Volatile
    private var lastKnownPasswordFocused: Boolean = false

    /**
     * Ultra-fast (< 0.05 ms) main-thread resolution that reads ONLY fields already inside the
     * [AccessibilityEvent] parcel without making any synchronous Binder IPC calls (`service.windows`
     * or `window.root`). Prevents main-thread ANRs and 5000ms AccessibilityInteractionClient stalls.
     */
    fun resolveFastFromEventInline(
        context: Context,
        event: AccessibilityEvent,
        fallbackPackage: String,
        fallbackWindowId: Int
    ): ResolvedWindowContext {
        if (event.isPassword) {
            lastKnownPasswordFocused = true
            val pkg = event.packageName?.toString()?.takeIf { !isTransientSystemOverlay(it) } ?: fallbackPackage
            return ResolvedWindowContext(
                packageName = pkg,
                appLabel = getAppLabel(context, pkg),
                windowId = event.windowId.takeIf { it >= 0 } ?: fallbackWindowId,
                isPasswordOrSensitiveInputActive = true
            )
        } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            lastKnownPasswordFocused = false
        }
        val eventPkg = event.packageName?.toString()
        if (eventPkg.isNullOrBlank() || isTransientSystemOverlay(eventPkg) || isSelfOverlayEvent(event)) {
            return ResolvedWindowContext(
                packageName = fallbackPackage,
                appLabel = getAppLabel(context, fallbackPackage),
                windowId = fallbackWindowId,
                isPasswordOrSensitiveInputActive = false
            )
        }
        val isSelfMainActivity = eventPkg == SELF_PACKAGE &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (event.className?.toString()?.contains("MainActivity", ignoreCase = true) == true)

        // Prevent background Xiaomi launcher (`com.miui.home`) or Google Discover minus-one pane
        // (`com.google.android.googlequicksearchbox`) from hijacking foreground state via background
        // TYPE_WINDOW_CONTENT_CHANGED events while the user is actively inside X/Twitter or Instagram!
        val isExplicitForegroundTransition =
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        val isFallbackUnsetOrLauncher =
            fallbackPackage.isBlank() ||
                fallbackPackage == "None" ||
                fallbackPackage == SELF_PACKAGE ||
                isLauncherPackage(fallbackPackage)

        val canSwitchToEventPkg = when {
            eventPkg == fallbackPackage -> true
            eventPkg == SELF_PACKAGE -> isSelfMainActivity
            isLauncherPackage(eventPkg) -> event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            eventPkg == "com.google.android.googlequicksearchbox" -> event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            isFallbackUnsetOrLauncher -> true
            isExplicitForegroundTransition -> true
            else -> false
        }

        val resolvedPkg = if (canSwitchToEventPkg) {
            eventPkg
        } else {
            fallbackPackage
        }
        val resolvedWinId = if (event.windowId >= 0 && eventPkg == resolvedPkg) {
            event.windowId
        } else {
            fallbackWindowId
        }
        return ResolvedWindowContext(
            packageName = resolvedPkg,
            appLabel = getAppLabel(context, resolvedPkg),
            windowId = resolvedWinId,
            isPasswordOrSensitiveInputActive = lastKnownPasswordFocused
        )
    }

    /**
     * Non-blocking (< 0.3 ms) window ID & package resolution.
     *
     * CRITICAL FIX: Never calls `win.root`, `service.rootInActiveWindow`, or `root.findFocus()`,
     * because those invoke `AccessibilityInteractionClient.waitForResultTimedLocked(5000ms)` and
     * block for 5,000-20,000 ms whenever X/Twitter or Instagram is playing a 60fps video!
     * Instead, reads only the in-parcel `AccessibilityWindowInfo.id`, `type`, `isActive`,
     * `isFocused`, and `boundsInScreen` metadata.
     */
    fun resolve(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        fallbackPackage: String,
        fallbackWindowId: Int
    ): ResolvedWindowContext {
        if (event != null) {
            return resolveFastFromEventInline(service, event, fallbackPackage, fallbackWindowId)
        }

        var candidateWindowId: Int = fallbackWindowId
        try {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                val activeAppWindows = windows.filter { win ->
                    val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        win.isInPictureInPictureMode
                    } else {
                        false
                    }
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        win.isActive &&
                        !isPip
                }

                val primaryWindow = activeAppWindows
                    .sortedWith(
                        compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                            .thenByDescending { win ->
                                val r = Rect()
                                win.getBoundsInScreen(r)
                                r.width().toLong() * r.height().toLong()
                            }
                    )
                    .firstOrNull()

                if (primaryWindow != null && primaryWindow.id >= 0) {
                    candidateWindowId = primaryWindow.id
                }
            }
        } catch (_: Exception) {
        }

        val finalPackage = if (fallbackPackage.isNotBlank() && fallbackPackage != "None") {
            fallbackPackage
        } else if (candidateWindowId >= 0) {
            "active_window_$candidateWindowId"
        } else {
            "None"
        }

        return ResolvedWindowContext(
            packageName = finalPackage,
            appLabel = getAppLabel(service, finalPackage),
            windowId = candidateWindowId,
            isPasswordOrSensitiveInputActive = lastKnownPasswordFocused
        )
    }

    private fun isPasswordInputFocused(root: AccessibilityNodeInfo): Boolean {
        return try {
            val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            inputFocused?.isPassword == true
        } catch (_: Exception) {
            false
        }
    }

    fun getAppLabel(context: Context, packageName: String): String {
        if (packageName.isBlank() || packageName == "None" || packageName == "none") {
            return "No active window"
        }
        labelCache[packageName]?.let { return it }
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            if (label.isNotBlank()) {
                labelCache[packageName] = label
                label
            } else {
                packageName
            }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: Exception) {
            packageName
        }
    }
}
