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
import {ScreenGuardLogo} from '../components/ScreenGuardLogo';
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
  const [showAdvanced, setShowAdvanced] = useState<boolean>(false);

  useEffect(() => {
    return () => {
      onPurgeInMemoryPreview().catch(() => {});
    };
  }, [onPurgeInMemoryPreview]);

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

  const handleDelayedOverlayTest = async () => {
    setOverlayCountdownSec(3);
    const interval = setInterval(() => {
      setOverlayCountdownSec(prev => (prev > 1 ? prev - 1 : 0));
    }, 1000);
    try {
      await onTriggerOverlayTest('EXPLICIT', 3200, 0);
    } finally {
      clearInterval(interval);
      setOverlayCountdownSec(0);
    }
  };

  const diag = status.bufferDiagnostics;
  const serviceReady =
    status.accessibilityPermissionGranted && status.serviceConnected;

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
      {/* Royal Blue Header */}
      <View style={styles.royalHeader}>
        <View style={styles.headerRow}>
          <View style={styles.titleWrap}>
            <ScreenGuardLogo size={32} />
            <View>
              <Text style={styles.heading}>Protection Check</Text>
              <Text style={styles.subheading}>
                Test the shield and supported apps
              </Text>
            </View>
          </View>
          <StatusBadge
            label={serviceReady ? 'Ready' : 'Setup Needed'}
            tone={serviceReady ? 'success' : 'warning'}
          />
        </View>
      </View>

      <View style={styles.body}>
        {/* Permission Prompt if not ready */}
        {!serviceReady && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Turn On Protection First</Text>
            <Text style={styles.cardSub}>
              Enable Screen Guard in Android Accessibility Settings so it can
              check your screen and show the protective shield.
            </Text>
            <View style={styles.rowButtons}>
              <Pressable
                onPress={onOpenAccessibilitySettings}
                style={styles.goldBtn}>
                <Text style={styles.goldBtnText}>Open Accessibility</Text>
              </Pressable>
              <Pressable onPress={onRefresh} style={styles.softBtn}>
                <Text style={styles.softBtnText}>Re-Check</Text>
              </Pressable>
            </View>
          </View>
        )}

        {/* 1. Test the Protective Shield */}
        <View style={styles.card}>
          <View style={styles.cardHeader}>
            <Text style={styles.cardTitle}>Test Protective Shield</Text>
            <StatusBadge
              label={
                status.protectionSummary?.overlayVisible ? 'Visible' : 'Hidden'
              }
              tone={
                status.protectionSummary?.overlayVisible ? 'danger' : 'success'
              }
            />
          </View>
          <Text style={styles.cardSub}>
            Preview what happens when Screen Guard blocks inappropriate content.
            Use the 3-second timer to switch to Instagram, TikTok, X, YouTube,
            or Chrome.
          </Text>

          <Pressable
            onPress={() => onTriggerOverlayTest('EXPLICIT', 0, 0)}
            style={({pressed}) => [
              styles.goldBtnFull,
              pressed && {backgroundColor: colors.goldPressed},
            ]}>
            <Text style={styles.goldBtnText}>Preview Protective Shield</Text>
          </Pressable>

          <Pressable
            onPress={handleDelayedOverlayTest}
            style={styles.outlineBtn}>
            <Text style={styles.outlineBtnTitle}>
              {overlayCountdownSec > 0
                ? `Showing shield in ${overlayCountdownSec}s...`
                : 'Test Shield on Another App (3s Timer)'}
            </Text>
            <Text style={styles.outlineBtnSub}>
              Tap, then switch to any app before the timer ends
            </Text>
          </Pressable>

          {status.protectionSummary?.overlayVisible && (
            <Pressable
              onPress={() => onDismissOverlay()}
              style={[styles.softBtn, {marginTop: spacing.sm}]}>
              <Text style={styles.softBtnText}>Hide Active Shield</Text>
            </Pressable>
          )}
        </View>

        {/* 2. Quick Screen Check */}
        <View style={styles.card}>
          <View style={styles.cardHeader}>
            <Text style={styles.cardTitle}>Quick Screen Check</Text>
            {isCapturingTest && (
              <ActivityIndicator size="small" color={colors.accent} />
            )}
          </View>
          <Text style={styles.cardSub}>
            Verify that Screen Guard can read and check your screen privately
            without saving any images.
          </Text>

          <View style={styles.rowButtons}>
            <Pressable
              disabled={isCapturingTest}
              onPress={() => onRunManualCapture(0, false)}
              style={[
                styles.primaryBlueBtn,
                isCapturingTest && {opacity: 0.6},
              ]}>
              <Text style={styles.primaryBlueBtnText}>Check Now</Text>
            </Pressable>
            <Pressable
              disabled={isCapturingTest}
              onPress={() => handleDelayedCapture(false)}
              style={[styles.softBtn, isCapturingTest && {opacity: 0.6}]}>
              <Text style={styles.softBtnText}>
                {countdownSec > 0
                  ? `Checking in ${countdownSec}s...`
                  : 'Check Other App (3s)'}
              </Text>
            </Pressable>
          </View>

          {status.lastScreenshotStatus !== 'IDLE' && (
            <View style={styles.checkSummaryBox}>
              <Text style={styles.checkSummaryText}>
                Last Check:{' '}
                <Text style={{fontWeight: '700'}}>
                  {status.lastScreenshotStatus === 'SUCCESS'
                    ? 'Screen checked safely'
                    : 'Skipped (Private screen)'}
                </Text>
                {status.lastScreenshotWidth > 0
                  ? ` · ${status.lastScreenshotDurationMs}ms`
                  : ''}
              </Text>
            </View>
          )}
        </View>

        {/* 3. Supported Apps */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Supported Apps</Text>
          <Text style={styles.cardSub}>
            Apps checked automatically while you browse. Private or incognito
            screens are skipped by Android for your security.
          </Text>

          {compatibilityRecords.map((item, index) => {
            const isLast = index === compatibilityRecords.length - 1;
            const badgeMeta = getFriendlyOutcomeBadge(item.outcome);
            return (
              <View
                key={item.packageName}
                style={[styles.appRow, !isLast && styles.appRowBorder]}>
                <View style={styles.appInfoCol}>
                  <Text style={styles.appName}>{item.displayName}</Text>
                  <Text style={styles.appCategory}>{item.category}</Text>
                </View>
                <Pressable onPress={() => cycleCompatibilityOutcome(item)}>
                  <StatusBadge
                    label={badgeMeta.label}
                    tone={badgeMeta.tone}
                    showDot={false}
                  />
                </Pressable>
              </View>
            );
          })}
        </View>

        {/* 4. Optional Technical Diagnostics (Collapsed by default) */}
        <Pressable
          onPress={() => setShowAdvanced(prev => !prev)}
          style={styles.advancedToggle}>
          <Text style={styles.advancedToggleText}>
            {showAdvanced
              ? 'Hide Technical Diagnostics ▲'
              : 'Show Technical Diagnostics ▼'}
          </Text>
        </Pressable>

        {showAdvanced && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Technical Diagnostics</Text>
            <MetricRow
              label="Overlay Mode"
              value={
                status.serviceConnected
                  ? 'TYPE_ACCESSIBILITY_OVERLAY'
                  : status.overlayPermissionGranted
                  ? 'TYPE_APPLICATION_OVERLAY'
                  : 'NOT READY'
              }
              mono
            />
            <MetricRow
              label="Buffer Cleanup"
              value={`${diag.hardwareBuffersClosed}/${diag.hardwareBuffersAllocated} closed · ${diag.activeInMemoryBuffers} active`}
              mono
            />
            <MetricRow
              label="Last Frame"
              value={
                diag.lastOriginalWidth > 0
                  ? `${diag.lastOriginalWidth}×${diag.lastOriginalHeight} → ${diag.lastProcessedWidth}×${diag.lastProcessedHeight}`
                  : '--'
              }
              mono
            />
            <MetricRow
              label="Debouncer Events"
              value={`${diag.debounceEventsReceived} rx → ${diag.debounceEventsAccepted} ok / ${diag.debounceEventsSuppressed} skip`}
              mono
              isLast={!debounceTestResult}
            />
            {debounceTestResult && (
              <MetricRow
                label="Last Burst Test"
                value={`${debounceTestResult.acceptedCount} ok / ${debounceTestResult.suppressedCount} skip (${debounceTestResult.elapsedMs}ms)`}
                mono
                isLast
              />
            )}

            <View style={[styles.rowButtons, {marginTop: spacing.md}]}>
              <Pressable
                onPress={() => onRunManualCapture(0, true)}
                style={styles.softBtn}>
                <Text style={styles.softBtnText}>Debug Preview</Text>
              </Pressable>
              <Pressable
                onPress={() => onRunDebounceTest(25, 20)}
                style={styles.softBtn}>
                <Text style={styles.softBtnText}>Burst Test</Text>
              </Pressable>
              <Pressable onPress={onRunDisconnectionTest} style={styles.softBtn}>
                <Text style={styles.softBtnText}>Reset State</Text>
              </Pressable>
            </View>

            <Pressable
              onPress={onOpenOverlaySettings}
              style={[styles.outlineBtn, {marginTop: spacing.sm}]}>
              <Text style={styles.outlineBtnTitle}>
                Open Display Over Other Apps Settings
              </Text>
            </Pressable>

            {debugPreview && debugPreview.captured && debugPreview.base64Jpeg ? (
              <View style={styles.previewBox}>
                <View style={styles.previewHeader}>
                  <Text style={styles.previewTitle}>
                    Volatile RAM Debug Preview ({debugPreview.previewWidth}×
                    {debugPreview.previewHeight})
                  </Text>
                  <Pressable onPress={onPurgeInMemoryPreview}>
                    <Text style={styles.purgeLink}>Purge</Text>
                  </Pressable>
                </View>
                <Image
                  source={{
                    uri: `data:image/jpeg;base64,${debugPreview.base64Jpeg}`,
                  }}
                  style={styles.previewImage}
                  resizeMode="contain"
                />
              </View>
            ) : null}
          </View>
        )}
      </View>
    </ScrollView>
  );
}

function getFriendlyOutcomeBadge(outcome: CompatibilityOutcome): {
  label: string;
  tone: 'success' | 'warning' | 'danger' | 'accent' | 'neutral';
} {
  switch (outcome) {
    case 'SUPPORTED_WINDOW_API':
    case 'SUPPORTED_DISPLAY_API':
      return {label: 'Protected', tone: 'success'};
    case 'REJECTED_FLAG_SECURE':
      return {label: 'Private Screen', tone: 'warning'};
    case 'FAILED_OTHER':
      return {label: 'Retry Needed', tone: 'danger'};
    case 'UNTESTED':
    default:
      return {label: 'Ready', tone: 'accent'};
  }
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  content: {
    paddingBottom: spacing.xxl + 16,
  },
  royalHeader: {
    backgroundColor: colors.royalBlue,
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.sm,
    paddingBottom: spacing.lg,
    borderBottomLeftRadius: radius.xl,
    borderBottomRightRadius: radius.xl,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  titleWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm + 2,
  },
  heading: {
    fontSize: 20,
    fontWeight: '800',
    color: colors.textOnBlue,
  },
  subheading: {
    fontSize: 12,
    color: colors.textOnBlueMuted,
    marginTop: 1,
  },
  body: {
    padding: spacing.lg,
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.xl,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.lg,
    marginBottom: spacing.md,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.xs,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: '800',
    color: colors.textPrimary,
    marginBottom: 4,
  },
  cardSub: {
    fontSize: 13,
    color: colors.textSecondary,
    lineHeight: 19,
    marginBottom: spacing.md,
  },
  goldBtnFull: {
    backgroundColor: colors.gold,
    borderRadius: radius.pill,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: spacing.sm,
  },
  goldBtn: {
    flex: 1,
    backgroundColor: colors.gold,
    borderRadius: radius.pill,
    paddingVertical: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  goldBtnText: {
    fontSize: 14.5,
    fontWeight: '800',
    color: colors.goldText,
  },
  outlineBtn: {
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    paddingVertical: 12,
    paddingHorizontal: spacing.md,
    alignItems: 'center',
  },
  outlineBtnTitle: {
    fontSize: 13.5,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  outlineBtnSub: {
    fontSize: 11.5,
    color: colors.textSecondary,
    marginTop: 2,
  },
  rowButtons: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  primaryBlueBtn: {
    flex: 1,
    backgroundColor: colors.royalBlue,
    borderRadius: radius.pill,
    paddingVertical: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  primaryBlueBtnText: {
    fontSize: 13.5,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  softBtn: {
    flex: 1,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
    paddingVertical: 12,
    paddingHorizontal: spacing.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  softBtnText: {
    fontSize: 13,
    fontWeight: '700',
    color: colors.textPrimary,
    textAlign: 'center',
  },
  checkSummaryBox: {
    marginTop: spacing.md,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
  },
  checkSummaryText: {
    fontSize: 12.5,
    color: colors.textSecondary,
    textAlign: 'center',
  },
  appRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: spacing.sm + 4,
  },
  appRowBorder: {
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  appInfoCol: {
    flex: 1,
  },
  appName: {
    fontSize: 14.5,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  appCategory: {
    fontSize: 12,
    color: colors.textSecondary,
    marginTop: 1,
  },
  advancedToggle: {
    alignItems: 'center',
    paddingVertical: spacing.sm,
    marginBottom: spacing.sm,
  },
  advancedToggleText: {
    fontSize: 12.5,
    fontWeight: '700',
    color: colors.textSecondary,
  },
  previewBox: {
    marginTop: spacing.md,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    padding: spacing.sm,
  },
  previewHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.xs,
  },
  previewTitle: {
    fontSize: 11.5,
    fontFamily: typography.mono,
    color: colors.textSecondary,
  },
  purgeLink: {
    fontSize: 12,
    fontWeight: '700',
    color: colors.danger,
  },
  previewImage: {
    width: '100%',
    height: 180,
    borderRadius: radius.sm,
    backgroundColor: '#0F172A',
  },
});
