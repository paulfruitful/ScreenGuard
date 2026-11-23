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

    private val IGNORED_TRANSIENT_PACKAGES = setOf(
        "com.android.systemui",
        "miui.systemui.plugin",
        "com.miui.systemui.plugin",
        "android",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.sec.android.inputmethod",
        "com.miui.securityinputmethod"
    )

    fun isTransientSystemOverlay(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        if (IGNORED_TRANSIENT_PACKAGES.contains(packageName)) return true
        val lower = packageName.lowercase()
        return lower.contains("systemui") ||
            lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.endsWith(".overlay")
    }

    fun resolve(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        fallbackPackage: String,
        fallbackWindowId: Int
    ): ResolvedWindowContext {
        val eventPkg = event?.packageName?.toString()
        if (event != null && !eventPkg.isNullOrBlank() && isTransientSystemOverlay(eventPkg)) {
            return ResolvedWindowContext(
                packageName = fallbackPackage,
                appLabel = getAppLabel(service, fallbackPackage),
                windowId = fallbackWindowId,
                isPasswordOrSensitiveInputActive = false
            )
        }

        if (event?.isPassword == true) {
            val pkg = eventPkg?.takeIf { !isTransientSystemOverlay(it) } ?: fallbackPackage
            return ResolvedWindowContext(
                packageName = pkg,
                appLabel = getAppLabel(service, pkg),
                windowId = event.windowId.takeIf { it >= 0 } ?: fallbackWindowId,
                isPasswordOrSensitiveInputActive = true
            )
        }

        var candidatePackage: String? = null
        var candidateWindowId: Int = -1
        var passwordFocused = false

        // 0. If this is an explicit TYPE_WINDOW_STATE_CHANGED from a real application package,
        // prioritize that newly opened activity package so transition frames don't read the closing window.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !isTransientSystemOverlay(eventPkg)
        ) {
            candidatePackage = eventPkg
            if (event.windowId >= 0) {
                candidateWindowId = event.windowId
            }
        }

        // 1. Inspect interactive windows for the primary focused, active, non-PiP APPLICATION window
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

                // If we already know the new package from TYPE_WINDOW_STATE_CHANGED, prefer its window
                val matchingWindow = if (!candidatePackage.isNullOrBlank()) {
                    activeAppWindows.firstOrNull { win ->
                        val p = try {
                            win.root?.packageName?.toString()
                        } catch (_: Exception) {
                            null
                        }
                        p == candidatePackage
                    }
                } else {
                    null
                }

                // Otherwise prefer focused window with the largest screen area
                val primaryWindow = matchingWindow ?: activeAppWindows
                    .sortedWith(
                        compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                            .thenByDescending { win ->
                                val r = Rect()
                                win.getBoundsInScreen(r)
                                r.width().toLong() * r.height().toLong()
                            }
                    )
                    .firstOrNull()

                if (primaryWindow != null) {
                    val root = try {
                        primaryWindow.root
                    } catch (_: Exception) {
                        null
                    }
                    val winPkg = root?.packageName?.toString()
                    if (candidatePackage.isNullOrBlank() && !isTransientSystemOverlay(winPkg)) {
                        candidatePackage = winPkg
                        candidateWindowId = primaryWindow.id
                    } else if (winPkg == candidatePackage || candidateWindowId < 0) {
                        candidateWindowId = primaryWindow.id
                    }
                    if (root != null) {
                        passwordFocused = isPasswordInputFocused(root)
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. If primaryWindow.root was temporarily null, query service.rootInActiveWindow
        if (candidatePackage.isNullOrBlank()) {
            try {
                val activeRoot = service.rootInActiveWindow
                if (activeRoot != null) {
                    val rootPkg = activeRoot.packageName?.toString()
                    if (!isTransientSystemOverlay(rootPkg)) {
                        candidatePackage = rootPkg
                        if (candidateWindowId < 0 && activeRoot.windowId >= 0) {
                            candidateWindowId = activeRoot.windowId
                        }
                        if (!passwordFocused) {
                            passwordFocused = isPasswordInputFocused(activeRoot)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Fallback to event packageName ONLY on TYPE_WINDOW_STATE_CHANGED
        if (candidatePackage.isNullOrBlank() &&
            !isTransientSystemOverlay(eventPkg) &&
            (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                fallbackPackage == "None" ||
                eventPkg == fallbackPackage)
        ) {
            candidatePackage = eventPkg
            if (candidateWindowId < 0 && event != null && event.windowId >= 0) {
                candidateWindowId = event.windowId
            }
        }

        val finalPackage = candidatePackage?.takeIf { it.isNotBlank() } ?: fallbackPackage
        val finalWindowId = when {
            candidateWindowId >= 0 -> candidateWindowId
            event != null && event.windowId >= 0 && eventPkg == finalPackage -> event.windowId
            else -> fallbackWindowId
        }

        return ResolvedWindowContext(
            packageName = finalPackage,
            appLabel = getAppLabel(service, finalPackage),
            windowId = finalWindowId,
            isPasswordOrSensitiveInputActive = passwordFocused
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
