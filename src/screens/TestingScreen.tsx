import React, {useEffect, useState} from 'react';
import {
  ActivityIndicator,
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {MetricRow} from '../components/MetricRow';
import {StatusBadge} from '../components/StatusBadge';
import {colors, radius, spacing, typography} from '../theme/theme';
import {
  AppCompatibilityRecord,
  CompatibilityOutcome,
  DebounceTestResult,
  DebugPreviewResponse,
  MonitoringStatus,
  ProtectionActivitySummary,
} from '../types/screenguard';

interface TestingScreenProps {
  status: MonitoringStatus;
  compatibilityRecords: AppCompatibilityRecord[];
  debugPreview: DebugPreviewResponse | null;
  debounceTestResult: DebounceTestResult | null;
  isCapturingTest: boolean;
  onOpenAccessibilitySettings: () => void;
  onOpenOverlaySettings: () => void;
  onTriggerOverlayTest: (
    category?: string,
    delayMs?: number,
    autoHideDurationMs?: number,
  ) => Promise<ProtectionActivitySummary | null>;
  onDismissOverlay: () => Promise<ProtectionActivitySummary | null>;
  onRunManualCapture: (
    delayMs: number,
    holdInMemoryPreview: boolean,
  ) => Promise<DebugPreviewResponse | null>;
  onPurgeInMemoryPreview: () => Promise<void>;
  onRunDebounceTest: (
    burstCount: number,
    intervalMs: number,
  ) => Promise<DebounceTestResult | null>;
  onRunDisconnectionTest: () => Promise<void>;
  onUpdateCompatibilityRecord: (record: AppCompatibilityRecord) => Promise<void>;
  onRefresh: () => void;
}

const OUTCOME_CYCLE: CompatibilityOutcome[] = [
  'UNTESTED',
  'SUPPORTED_WINDOW_API',
  'SUPPORTED_DISPLAY_API',
  'REJECTED_FLAG_SECURE',
  'FAILED_OTHER',
];

export function TestingScreen({
  status,
  compatibilityRecords,
  debugPreview,
  debounceTestResult,
  isCapturingTest,
  onOpenAccessibilitySettings,
  onOpenOverlaySettings,
  onTriggerOverlayTest,
  onDismissOverlay,
  onRunManualCapture,
  onPurgeInMemoryPreview,
  onRunDebounceTest,
  onRunDisconnectionTest,
  onUpdateCompatibilityRecord,
  onRefresh,
}: TestingScreenProps) {
  const [countdownSec, setCountdownSec] = useState<number>(0);
  const [overlayCountdownSec, setOverlayCountdownSec] = useState<number>(0);

  // Automatically purge any temporary in-memory preview when leaving the Testing screen
  useEffect(() => {
    return () => {
      onPurgeInMemoryPreview().catch(() => {});
    };
  }, [onPurgeInMemoryPreview]);

  const handleDelayedOverlayTest = async (category: string) => {
    setOverlayCountdownSec(3);
    const interval = setInterval(() => {
      setOverlayCountdownSec(prev => (prev > 1 ? prev - 1 : 0));
    }, 1000);
    try {
      await onTriggerOverlayTest(category, 3200, 0);
    } finally {
      clearInterval(interval);
      setOverlayCountdownSec(0);
    }
  };

  const handleDelayedCapture = async (holdPreview: boolean) => {
    setCountdownSec(3);
    const interval = setInterval(() => {
      setCountdownSec(prev => (prev > 1 ? prev - 1 : 0));
    }, 1000);
    try {
      await onRunManualCapture(3200, holdPreview);
    } finally {
      clearInterval(interval);
      setCountdownSec(0);
    }
  };

  const diag = status.bufferDiagnostics;
  const allBuffersReleased =
    diag.hardwareBuffersAllocated === diag.hardwareBuffersClosed &&
    diag.bitmapsAllocated === diag.bitmapsRecycled &&
    diag.activeInMemoryBuffers === 0;

  const cycleCompatibilityOutcome = (item: AppCompatibilityRecord) => {
    const idx = OUTCOME_CYCLE.indexOf(item.outcome);
    const nextOutcome = OUTCOME_CYCLE[(idx + 1) % OUTCOME_CYCLE.length];
    onUpdateCompatibilityRecord({
      ...item,
      outcome: nextOutcome,
      lastTestedAt: Date.now(),
    });
  };

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      showsVerticalScrollIndicator={false}>
      {/* 1. Accessibility Permission & Service Readiness */}
      <View style={styles.sectionCard}>
        <View style={styles.headerRow}>
          <Text style={styles.sectionTitle}>
            1. Accessibility Permission & Service Status
          </Text>
          <StatusBadge
            label={
              status.accessibilityPermissionGranted && status.serviceConnected
                ? 'READY'
                : 'NOT ENABLED'
            }
            tone={
              status.accessibilityPermissionGranted && status.serviceConnected
                ? 'success'
                : 'warning'
            }
          />
        </View>

        <MetricRow
          label="Accessibility Service Enabled"
          value={status.accessibilityPermissionGranted ? 'TRUE' : 'FALSE'}
          valueColor={
            status.accessibilityPermissionGranted
              ? colors.success
              : colors.warning
          }
          mono
        />
        <MetricRow
          label="Service Lifecycle Bound"
          value={status.serviceConnected ? 'CONNECTED' : 'DISCONNECTED'}
          valueColor={
            status.serviceConnected ? colors.success : colors.textMuted
          }
          mono
        />
        <MetricRow
          label="Android API Level"
          value={`API ${status.androidSdkInt} (Android ${status.androidRelease})`}
          subValue={
            status.supportsTakeScreenshotOfWindow
              ? 'takeScreenshotOfWindow + takeScreenshot available'
              : 'takeScreenshot available'
          }
          mono
          isLast
        />

        <View style={styles.buttonRow}>
          <Pressable
            onPress={onOpenAccessibilitySettings}
            style={styles.secondaryBtn}>
            <Text style={styles.secondaryBtnText}>
              Accessibility Settings
            </Text>
          </Pressable>
          <Pressable
            onPress={onOpenOverlaySettings}
            style={styles.secondaryBtn}>
            <Text style={styles.secondaryBtnText}>
              Overlay Permission
            </Text>
          </Pressable>
          <Pressable onPress={onRefresh} style={styles.secondaryBtn}>
            <Text style={styles.secondaryBtnText}>Sync Status</Text>
          </Pressable>
        </View>
      </View>

      {/* Milestone 3 Protective Overlay & Touch-Interception Test Console */}
      <View style={styles.sectionCard}>
        <View style={styles.headerRow}>
          <Text style={styles.sectionTitle}>
            Protective Overlay & Touch-Interception Verification
          </Text>
          <StatusBadge
            label={`STATE: ${status.protectionSummary?.overlayState || 'HIDDEN'}`}
            tone={
              status.protectionSummary?.overlayVisible
                ? 'danger'
                : 'success'
            }
          />
        </View>

        <Text style={styles.helperText}>
          Test the native WindowManager protective overlay (
          TYPE_ACCESSIBILITY_OVERLAY primary / TYPE_APPLICATION_OVERLAY
          fallback). Use the 3-second delayed trigger to switch into Chrome,
          Instagram, TikTok, X, YouTube, or Facebook and verify full-screen
          coverage, portrait/landscape orientation changes, touch blocking, and
          "Return to Safe Browsing".
        </Text>

        <View style={styles.buttonGrid}>
          <Pressable
            onPress={() => onTriggerOverlayTest('EXPLICIT', 0, 0)}
            style={[styles.actionBtn, styles.actionBtnAccent]}>
            <Text style={styles.actionBtnTitle}>
              Show Protective Overlay Now (Immediate)
            </Text>
            <Text style={styles.actionBtnSub}>
              Displays full-screen shield immediately · Test "Return to Safe Browsing" or "Dismiss"
            </Text>
          </Pressable>

          <Pressable
            onPress={() => handleDelayedOverlayTest('EXPLICIT')}
            style={styles.actionBtn}>
            <Text style={styles.actionBtnTitle}>
              {overlayCountdownSec > 0
                ? `Triggering Overlay in ${overlayCountdownSec}s...`
                : '3s Delayed Protective Overlay (Switch to Target App)'}
            </Text>
            <Text style={styles.actionBtnSub}>
              Switch to Chrome, Instagram, TikTok, X, YouTube, or Facebook before timer fires
            </Text>
          </Pressable>

          {status.protectionSummary?.overlayVisible && (
            <Pressable
              onPress={() => onDismissOverlay()}
              style={styles.actionBtn}>
              <Text style={styles.actionBtnTitle}>
                Dismiss Active Protective Overlay
              </Text>
              <Text style={styles.actionBtnSub}>
                Removes overlay and starts per-package dismissal cooldown
              </Text>
            </Pressable>
          )}
        </View>

        <MetricRow
          label="Overlay Mechanism"
          value={
            status.serviceConnected
              ? 'TYPE_ACCESSIBILITY_OVERLAY (Trusted Service Surface)'
              : status.overlayPermissionGranted
              ? 'TYPE_APPLICATION_OVERLAY (SYSTEM_ALERT_WINDOW)'
              : 'ENABLE ACCESSIBILITY OR OVERLAY PERMISSION'
          }
          valueColor={
            status.serviceConnected || status.overlayPermissionGranted
              ? colors.success
              : colors.warning
          }
          mono
        />
        <MetricRow
          label="Protection Events / Apps Protected"
          value={`${status.protectionSummary?.totalProtectionEvents || 0} events across ${
            status.protectionSummary?.protectedAppsCount || 0
          } app(s)`}
          subValue={`Safe Returns: ${
            status.protectionSummary?.totalReturnToSafetyClicks || 0
          } · Dismissals: ${
            status.protectionSummary?.totalDismissals || 0
          } · Auto-Recovered: ${
            status.protectionSummary?.totalAutoRecoveries || 0
          }`}
          mono
          isLast
        />
      </View>

      {/* 2–5. Screenshot Capture, Dimensions, Latency & Explicit In-Memory Debug Preview */}
      <View style={styles.sectionCard}>
        <View style={styles.headerRow}>
          <Text style={styles.sectionTitle}>
            2–5. Screenshot Capture & In-Memory Preview
          </Text>
          {isCapturingTest && (
            <ActivityIndicator size="small" color={colors.accent} />
          )}
        </View>

        <Text style={styles.helperText}>
          Test native AccessibilityService screenshot capture. Use the 3-second
          delayed capture to switch to Chrome, YouTube, Instagram, TikTok,
          Settings, or Home Screen and test capture of that target window.
        </Text>

        <View style={styles.buttonGrid}>
          <Pressable
            disabled={isCapturingTest}
            onPress={() => onRunManualCapture(0, false)}
            style={[
              styles.actionBtn,
              isCapturingTest && styles.buttonDisabled,
            ]}>
            <Text style={styles.actionBtnTitle}>
              Capture Now (Zero-Retention)
            </Text>
            <Text style={styles.actionBtnSub}>
              Captures, classifies & immediately frees RAM
            </Text>
          </Pressable>

          <Pressable
            disabled={isCapturingTest}
            onPress={() => handleDelayedCapture(false)}
            style={[
              styles.actionBtn,
              isCapturingTest && styles.buttonDisabled,
            ]}>
            <Text style={styles.actionBtnTitle}>
              {countdownSec > 0
                ? `Capturing in ${countdownSec}s...`
                : '3s Delayed Capture (Switch App)'}
            </Text>
            <Text style={styles.actionBtnSub}>
              Switch to another app before timer fires
            </Text>
          </Pressable>

          <Pressable
            disabled={isCapturingTest}
            onPress={() => onRunManualCapture(0, true)}
            style={[
              styles.actionBtn,
              styles.actionBtnAccent,
              isCapturingTest && styles.buttonDisabled,
            ]}>
            <Text style={styles.actionBtnTitle}>
              Capture & Hold In-Memory Debug Preview
            </Text>
            <Text style={styles.actionBtnSub}>
              Explicit user action · Volatile RAM only · Never saved to disk
            </Text>
          </Pressable>

          <Pressable
            disabled={isCapturingTest}
            onPress={() => handleDelayedCapture(true)}
            style={[
              styles.actionBtn,
              styles.actionBtnAccent,
              isCapturingTest && styles.buttonDisabled,
            ]}>
            <Text style={styles.actionBtnTitle}>
              3s Delayed Capture + In-Memory Preview
            </Text>
            <Text style={styles.actionBtnSub}>
              Switch to target app & inspect downsampled buffer
            </Text>
          </Pressable>
        </View>

        <MetricRow
          label="Original Hardware Buffer Dimensions"
          value={
            diag.lastOriginalWidth > 0
              ? `${diag.lastOriginalWidth} × ${diag.lastOriginalHeight} px`
              : '--'
          }
          mono
        />
        <MetricRow
          label="Downsampled Classifier Dimensions"
          value={
            diag.lastProcessedWidth > 0
              ? `${diag.lastProcessedWidth} × ${diag.lastProcessedHeight} px`
              : '--'
          }
          mono
        />
        <MetricRow
          label="Screenshot Capture Duration"
          value={
            diag.lastCaptureDurationMs > 0
              ? `${diag.lastCaptureDurationMs} ms`
              : '--'
          }
          mono
        />
        <MetricRow
          label="Classifier Processing Duration"
          value={
            diag.lastClassificationDurationMs > 0
              ? `${diag.lastClassificationDurationMs} ms`
              : '--'
          }
          mono
        />
        <MetricRow
          label="API Method / Error Code"
          value={`${diag.lastCaptureMethod || 'NONE'} / ${
            diag.lastErrorCode || 'NONE'
          }`}
          valueColor={
            diag.lastErrorCode && diag.lastErrorCode !== 'NONE'
              ? colors.warning
              : colors.success
          }
          mono
          isLast
        />

        {/* Temporary In-Memory Preview Box */}
        {debugPreview && debugPreview.captured && debugPreview.base64Jpeg ? (
          <View style={styles.previewBox}>
            <View style={styles.previewHeader}>
              <View>
                <Text style={styles.previewTitle}>
                  Volatile In-Memory Debug Preview
                </Text>
                <Text style={styles.previewMeta}>
                  {debugPreview.packageName} (windowId {debugPreview.windowId}) ·{' '}
                  {debugPreview.originalWidth}×{debugPreview.originalHeight} →{' '}
                  {debugPreview.previewWidth}×{debugPreview.previewHeight} ·{' '}
                  {debugPreview.captureDurationMs}ms
                </Text>
              </View>
              <Pressable
                onPress={onPurgeInMemoryPreview}
                style={styles.purgeBtn}>
                <Text style={styles.purgeBtnText}>Purge RAM</Text>
              </Pressable>
            </View>
            <Image
              source={{
                uri: `data:image/jpeg;base64,${debugPreview.base64Jpeg}`,
              }}
              style={styles.previewImage}
              resizeMode="contain"
            />
            <Text style={styles.previewFootnote}>
              HardwareBuffer closed immediately after copy. Preview exists only
              in volatile memory and is purged automatically when leaving this
              screen.
            </Text>
          </View>
        ) : debugPreview && !debugPreview.captured ? (
          <View style={styles.previewErrorBox}>
            <Text style={styles.previewErrorTitle}>
              Capture Failed: {debugPreview.errorCode}
            </Text>
            <Text style={styles.previewErrorSub}>
              Ensure Screen Guard AccessibilityService is enabled in Android
              Settings and the target window does not enforce FLAG_SECURE or the
              1-second Android screenshot rate limit.
            </Text>
          </View>
        ) : null}
      </View>

      {/* 8. HardwareBuffer & Bitmap Release Verification */}
      <View style={styles.sectionCard}>
        <View style={styles.headerRow}>
          <Text style={styles.sectionTitle}>
            8. HardwareBuffer & Memory Release Audit
          </Text>
          <StatusBadge
            label={allBuffersReleased ? '0 LEAKS VERIFIED' : 'BUFFERS ACTIVE'}
            tone={allBuffersReleased ? 'success' : 'warning'}
          />
        </View>
        <MetricRow
          label="HardwareBuffers Allocated / Closed"
          value={`${diag.hardwareBuffersAllocated} allocated / ${diag.hardwareBuffersClosed} closed`}
          valueColor={
            diag.hardwareBuffersAllocated === diag.hardwareBuffersClosed
              ? colors.success
              : colors.warning
          }
          mono
        />
        <MetricRow
          label="Bitmaps Allocated / Recycled"
          value={`${diag.bitmapsAllocated} allocated / ${diag.bitmapsRecycled} recycled`}
          valueColor={
            diag.bitmapsAllocated === diag.bitmapsRecycled
              ? colors.success
              : colors.warning
          }
          mono
        />
        <MetricRow
          label="Currently Active Hardware/Bitmap Buffers"
          value={`${diag.activeInMemoryBuffers}`}
          valueColor={
            diag.activeInMemoryBuffers === 0 ? colors.success : colors.warning
          }
          mono
        />
        <MetricRow
          label="Volatile Debug Preview Held"
          value={diag.hasInMemoryDebugPreview ? 'YES (IN RAM)' : 'NO (PURGED)'}
          mono
          isLast
        />
      </View>

      {/* 6 & 7. Event Debouncing & Service Disconnection Tests */}
      <View style={styles.sectionCard}>
        <Text style={styles.sectionTitle}>
          6 & 7. Event Debouncing & Disconnection Handling
        </Text>

        <MetricRow
          label="Live Debouncer Telemetry"
          value={`${diag.debounceEventsReceived} received → ${diag.debounceEventsAccepted} accepted / ${diag.debounceEventsSuppressed} suppressed`}
          mono
        />
        <MetricRow
          label="Service Reconnect / Disconnect Count"
          value={`${diag.serviceReconnectCount} connects / ${diag.serviceDisconnectCount} disconnects`}
          mono
          isLast={!debounceTestResult}
        />

        {debounceTestResult && (
          <MetricRow
            label="Last Burst Stress Test (25 events @ 20ms)"
            value={`Accepted ${debounceTestResult.acceptedCount} / Suppressed ${debounceTestResult.suppressedCount} (${debounceTestResult.elapsedMs}ms)`}
            valueColor={colors.success}
            mono
            isLast
          />
        )}

        <View style={styles.buttonRow}>
          <Pressable
            onPress={() => onRunDebounceTest(25, 20)}
            style={styles.secondaryBtn}>
            <Text style={styles.secondaryBtnText}>
              Test Event Debouncer (25x Burst)
            </Text>
          </Pressable>
          <Pressable
            onPress={onRunDisconnectionTest}
            style={styles.secondaryBtn}>
            <Text style={styles.secondaryBtnText}>
              Test Disconnection Cleanup
            </Text>
          </Pressable>
        </View>
      </View>

      {/* Target App Compatibility Matrix */}
      <View style={styles.sectionCard}>
        <Text style={styles.sectionTitle}>
          Target Application Compatibility Matrix
        </Text>
        <Text style={styles.helperText}>
          Records real on-device screenshot compatibility per target app.
          Automatically updated when a screenshot succeeds or fails in a listed
          package, or tap a row’s badge to log your physical device verification
          status.
        </Text>

        {compatibilityRecords.map((item, index) => {
          const isLast = index === compatibilityRecords.length - 1;
          const badgeMeta = getOutcomeBadge(item.outcome);
          return (
            <View
              key={item.packageName}
              style={[styles.compatRow, !isLast && styles.compatBorder]}>
              <View style={styles.compatTop}>
                <View style={styles.compatTitleCol}>
                  <Text style={styles.compatName}>{item.displayName}</Text>
                  <Text style={styles.compatPkg}>{item.packageName}</Text>
                </View>
                <Pressable onPress={() => cycleCompatibilityOutcome(item)}>
                  <StatusBadge
                    label={badgeMeta.label}
                    tone={badgeMeta.tone}
                    showDot={false}
                  />
                </Pressable>
              </View>
              <Text style={styles.compatNotes}>{item.notes}</Text>
              {item.lastTestedAt ? (
                <Text style={styles.compatTelemetry}>
                  Method: {item.captureMethod} · Res: {item.resolution} ·{' '}
                  {item.durationMs}ms · Err: {item.errorCode}
                </Text>
              ) : null}
            </View>
          );
        })}
      </View>
    </ScrollView>
  );
}

function getOutcomeBadge(outcome: CompatibilityOutcome): {
  label: string;
  tone: 'success' | 'warning' | 'danger' | 'accent' | 'neutral';
} {
  switch (outcome) {
    case 'SUPPORTED_WINDOW_API':
      return {label: 'Window API OK', tone: 'success'};
    case 'SUPPORTED_DISPLAY_API':
      return {label: 'Display API OK', tone: 'accent'};
    case 'REJECTED_FLAG_SECURE':
      return {label: 'FLAG_SECURE Blocked', tone: 'warning'};
    case 'FAILED_OTHER':
      return {label: 'Capture Failed', tone: 'danger'};
    case 'UNTESTED':
    default:
      return {label: 'Untested (Tap to Log)', tone: 'neutral'};
  }
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  content: {
    padding: spacing.lg,
    paddingBottom: spacing.xxl + 16,
  },
  sectionCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.lg,
    marginBottom: spacing.md,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.sm,
    gap: spacing.sm,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: colors.textSecondary,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    flex: 1,
  },
  helperText: {
    fontSize: 12.5,
    color: colors.textSecondary,
    lineHeight: 18,
    marginBottom: spacing.md,
  },
  buttonGrid: {
    gap: spacing.sm,
    marginBottom: spacing.md,
  },
  actionBtn: {
    backgroundColor: colors.surfaceElevated,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.borderStrong,
    padding: spacing.md,
  },
  actionBtnAccent: {
    borderColor: colors.accentBorder,
    backgroundColor: colors.accentSoft,
  },
  buttonDisabled: {
    opacity: 0.55,
  },
  actionBtnTitle: {
    fontSize: 13.5,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: 2,
  },
  actionBtnSub: {
    fontSize: 11.5,
    color: colors.textSecondary,
  },
  buttonRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  secondaryBtn: {
    flex: 1,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.border,
    paddingVertical: 10,
    paddingHorizontal: spacing.sm,
    alignItems: 'center',
  },
  secondaryBtnText: {
    fontSize: 12,
    fontWeight: '600',
    color: colors.textPrimary,
    textAlign: 'center',
  },
  previewBox: {
    marginTop: spacing.md,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.accentBorder,
    padding: spacing.md,
  },
  previewHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.sm,
    gap: spacing.sm,
  },
  previewTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  previewMeta: {
    fontSize: 11,
    color: colors.textSecondary,
    fontFamily: typography.mono,
    marginTop: 2,
  },
  purgeBtn: {
    backgroundColor: colors.dangerSoft,
    borderWidth: 1,
    borderColor: colors.dangerBorder,
    borderRadius: radius.sm,
    paddingHorizontal: 10,
    paddingVertical: 6,
  },
  purgeBtnText: {
    fontSize: 11.5,
    fontWeight: '700',
    color: colors.danger,
  },
  previewImage: {
    width: '100%',
    height: 240,
    backgroundColor: '#05070A',
    borderRadius: radius.sm,
    marginBottom: spacing.xs + 2,
  },
  previewFootnote: {
    fontSize: 11,
    color: colors.textMuted,
    lineHeight: 16,
  },
  previewErrorBox: {
    marginTop: spacing.md,
    backgroundColor: colors.warningSoft,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.warningBorder,
    padding: spacing.md,
  },
  previewErrorTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: colors.warning,
    marginBottom: 4,
  },
  previewErrorSub: {
    fontSize: 12,
    color: colors.textSecondary,
    lineHeight: 17,
  },
  compatRow: {
    paddingVertical: spacing.sm + 4,
  },
  compatBorder: {
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  compatTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 4,
    gap: spacing.sm,
  },
  compatTitleCol: {
    flex: 1,
  },
  compatName: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  compatPkg: {
    fontSize: 11.5,
    color: colors.textMuted,
    fontFamily: typography.mono,
  },
  compatNotes: {
    fontSize: 12,
    color: colors.textSecondary,
    lineHeight: 17,
  },
  compatTelemetry: {
    fontSize: 11,
    color: '#60A5FA',
    fontFamily: typography.mono,
    marginTop: 4,
  },
});
