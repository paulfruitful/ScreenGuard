import React from 'react';
import {
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
  MonitoringStatus,
  ProtectionActivitySummary,
  ProtectionSensitivityMode,
  UserPreferences,
} from '../types/screenguard';

interface DashboardScreenProps {
  status: MonitoringStatus;
  preferences: UserPreferences;
  onOpenAccessibilitySettings: () => void;
  onOpenOverlaySettings: () => void;
  onToggleProtection: (enabled: boolean) => void;
  onUpdatePreferences: (partial: Partial<UserPreferences>) => Promise<void>;
  onTriggerOverlayTest: (
    category?: string,
    delayMs?: number,
    autoHideDurationMs?: number,
  ) => Promise<ProtectionActivitySummary | null>;
  onDismissOverlay: () => Promise<ProtectionActivitySummary | null>;
  onNavigateToTesting: () => void;
  onNavigateToClassifier: () => void;
  onRefresh: () => void;
}

export function DashboardScreen({
  status,
  preferences,
  onOpenAccessibilitySettings,
  onOpenOverlaySettings,
  onToggleProtection,
  onUpdatePreferences,
  onTriggerOverlayTest,
  onDismissOverlay,
  onNavigateToTesting,
  onNavigateToClassifier,
  onRefresh,
}: DashboardScreenProps) {
  const permissionGranted = status.accessibilityPermissionGranted;
  const overlayPermissionGranted = status.overlayPermissionGranted;
  const serviceActive = permissionGranted && status.serviceConnected;
  const monitoringActive = serviceActive && status.protectionEnabled;
  const overlayEngineReady = serviceActive || overlayPermissionGranted;

  const summary = status.protectionSummary || {
    overlayState: 'HIDDEN',
    overlayVisible: false,
    activeProtectedPackage: 'None',
    activeProtectedAppLabel: 'None',
    activeTriggerCategory: 'NONE',
    activeConfidence: 0,
    sensitivityMode: preferences.sensitivityMode || 'BALANCED',
    totalProtectionEvents: status.totalConfirmedInterventions || 0,
    protectedAppsCount: 0,
    protectedPackageNames: [],
    totalDismissals: 0,
    totalReturnToSafetyClicks: 0,
    totalAutoRecoveries: 0,
    cooldownRemainingMs: 0,
    lastOverlayError: 'NONE',
  };

  const activeSensitivity: ProtectionSensitivityMode =
    preferences.sensitivityMode === 'STRICT' ? 'STRICT' : 'BALANCED';

  const handlePrimaryAction = () => {
    if (!permissionGranted) {
      onOpenAccessibilitySettings();
      return;
    }
    onToggleProtection(!status.protectionEnabled);
  };

  const latest = status.latestClassification;

  const getOverlayStateTone = (
    state: string,
  ): 'success' | 'warning' | 'danger' | 'accent' | 'neutral' => {
    switch (state) {
      case 'PROTECTED':
        return 'danger';
      case 'PENDING':
      case 'RECOVERING':
        return 'warning';
      case 'DISMISSED':
        return 'accent';
      case 'HIDDEN':
      default:
        return 'success';
    }
  };

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      showsVerticalScrollIndicator={false}>
      {/* Primary Protection Hero Card */}
      <View style={styles.heroCard}>
        <View style={styles.heroTopRow}>
          <StatusBadge
            label={
              !permissionGranted
                ? 'Permission Required'
                : monitoringActive
                ? `Protection Active · ${activeSensitivity}`
                : 'Protection Paused'
            }
            tone={
              !permissionGranted
                ? 'warning'
                : monitoringActive
                ? 'success'
                : 'neutral'
            }
          />
          <Pressable onPress={onRefresh} style={styles.refreshBtn}>
            <Text style={styles.refreshText}>Sync State</Text>
          </Pressable>
        </View>

        <Text style={styles.heroTitle}>
          {!permissionGranted
            ? 'Enable Screen Guard Protection'
            : monitoringActive
            ? 'System-Wide Content Filtering Active'
            : 'Protection Paused by User'}
        </Text>

        <Text style={styles.heroSubtitle}>
          {!permissionGranted
            ? 'Screen Guard requires Android Accessibility access to observe active app windows, run on-device LiteRT classification, and display a protective overlay when explicit content is confirmed.'
            : monitoringActive
            ? `Observing foreground applications in ${activeSensitivity} mode (${preferences.confirmationFramesRequired}-frame confirmation, ${preferences.samplingIntervalMs}ms cooldown). Zero screenshots leave the device.`
            : 'Accessibility Service is bound, but window observation and protective overlays are currently paused.'}
        </Text>

        {/* Sensitivity Quick Selector (Balanced vs Strict) */}
        <View style={styles.sensitivityRow}>
          <Text style={styles.sensitivityLabel}>Protection Sensitivity:</Text>
          <View style={styles.sensitivityPills}>
            {(['BALANCED', 'STRICT'] as ProtectionSensitivityMode[]).map(
              mode => {
                const isSelected = activeSensitivity === mode;
                return (
                  <Pressable
                    key={mode}
                    onPress={() => onUpdatePreferences({sensitivityMode: mode})}
                    style={[
                      styles.sensitivityPill,
                      isSelected && styles.sensitivityPillActive,
                    ]}>
                    <Text
                      style={[
                        styles.sensitivityPillText,
                        isSelected && styles.sensitivityPillTextActive,
                      ]}>
                      {mode === 'BALANCED' ? 'Balanced' : 'Strict'}
                    </Text>
                  </Pressable>
                );
              },
            )}
          </View>
        </View>

        <Pressable
          onPress={handlePrimaryAction}
          style={({pressed}) => [
            styles.primaryButton,
            !permissionGranted
              ? styles.primaryButtonWarning
              : monitoringActive
              ? styles.primaryButtonOutline
              : styles.primaryButtonActive,
            pressed && {opacity: 0.88},
          ]}>
          <Text
            style={[
              styles.primaryButtonText,
              monitoringActive &&
                permissionGranted &&
                styles.primaryButtonTextOutline,
            ]}>
            {!permissionGranted
              ? 'Enable Protection in Accessibility Settings'
              : monitoringActive
              ? 'Pause Protection'
              : 'Resume Protection'}
          </Text>
        </Pressable>

        <View style={styles.quickActionRow}>
          <Pressable
            onPress={() => onTriggerOverlayTest('EXPLICIT', 0, 0)}
            disabled={!overlayEngineReady}
            style={[
              styles.secondaryButton,
              !overlayEngineReady && {opacity: 0.5},
            ]}>
            <Text style={styles.secondaryButtonText}>
              Preview Protective Shield
            </Text>
          </Pressable>
          <Pressable
            onPress={onNavigateToClassifier}
            style={styles.secondaryButton}>
            <Text style={styles.secondaryButtonText}>
              Classifier & Eval Suite
            </Text>
          </Pressable>
          <Pressable
            onPress={onNavigateToTesting}
            style={styles.secondaryButton}>
            <Text style={styles.secondaryButtonText}>Overlay & App Tests</Text>
          </Pressable>
        </View>
      </View>

      {/* Milestone 3 Section 10.3: Protection Activity Summary Card */}
      <View style={styles.sectionCard}>
        <View style={styles.cardHeaderInline}>
          <Text style={styles.sectionTitle}>Protection Activity Summary</Text>
          <StatusBadge
            label={`STATE: ${summary.overlayState}`}
            tone={getOverlayStateTone(summary.overlayState)}
            showDot
          />
        </View>

        <View style={styles.counterGrid}>
          <View style={styles.counterBox}>
            <Text
              style={[
                styles.counterValue,
                summary.totalProtectionEvents > 0 && {color: '#60A5FA'},
              ]}>
              {summary.totalProtectionEvents}
            </Text>
            <Text style={styles.counterLabel}>Protection Events</Text>
          </View>
          <View style={styles.counterBox}>
            <Text style={styles.counterValue}>
              {summary.protectedAppsCount}
            </Text>
            <Text style={styles.counterLabel}>Apps Protected</Text>
          </View>
          <View style={styles.counterBox}>
            <Text style={styles.counterValue}>
              {summary.totalReturnToSafetyClicks}
            </Text>
            <Text style={styles.counterLabel}>Safe Returns</Text>
          </View>
          <View style={styles.counterBox}>
            <Text style={styles.counterValue}>
              {summary.totalAutoRecoveries}
            </Text>
            <Text style={styles.counterLabel}>Auto-Recovered</Text>
          </View>
        </View>

        <MetricRow
          label="Current Protection State"
          value={summary.overlayState}
          valueColor={
            summary.overlayState === 'PROTECTED'
              ? colors.danger
              : summary.overlayState === 'PENDING' ||
                summary.overlayState === 'RECOVERING'
              ? colors.warning
              : colors.success
          }
          subValue={
            summary.overlayVisible
              ? `Covering ${summary.activeProtectedAppLabel} (${summary.activeProtectedPackage}) · Trigger: ${summary.activeTriggerCategory}`
              : summary.cooldownRemainingMs > 0
              ? `Dismissal cooldown active (${Math.ceil(
                  summary.cooldownRemainingMs / 1000,
                )}s remaining)`
              : 'No protective overlay currently displayed'
          }
          mono
        />
        <MetricRow
          label="Active Sensitivity Policy"
          value={
            activeSensitivity === 'STRICT'
              ? 'STRICT (Explicit, Nudity, Sexual Activity + High-Conf Suggestive)'
              : 'BALANCED (Explicit, Nudity, Sexual Activity only)'
          }
          subValue={`Manual Dismiss: ${
            preferences.allowManualDismiss
              ? `Allowed (${preferences.dismissCooldownSeconds}s cooldown)`
              : 'Disabled'
          } · User Dismissals: ${summary.totalDismissals}`}
          mono
          isLast={!summary.overlayVisible}
        />

        {summary.overlayVisible && (
          <Pressable
            onPress={() => onDismissOverlay()}
            style={[styles.outlineBtn, {marginTop: spacing.sm}]}>
            <Text style={styles.outlineBtnText}>
              Dismiss Active Protective Overlay
            </Text>
          </Pressable>
        )}
      </View>

      {/* Milestone 3 Section 9 & 10.2: Permission Onboarding & Status Card */}
      <View style={styles.sectionCard}>
        <View style={styles.cardHeaderInline}>
          <Text style={styles.sectionTitle}>
            Permission Onboarding & Status
          </Text>
          <StatusBadge
            label={
              serviceActive
                ? 'ALL REQUIRED GRANTED'
                : permissionGranted
                ? 'WAITING FOR BIND'
                : 'SETUP NEEDED'
            }
            tone={serviceActive ? 'success' : 'warning'}
          />
        </View>

        <View style={styles.onboardingItem}>
          <View style={styles.onboardingHeader}>
            <Text style={styles.onboardingTitle}>
              1. Android Accessibility Service (Required)
            </Text>
            <StatusBadge
              label={
                serviceActive
                  ? 'ACTIVE & BOUND'
                  : permissionGranted
                  ? 'ENABLED'
                  : 'NOT GRANTED'
              }
              tone={serviceActive ? 'success' : 'warning'}
              showDot={false}
            />
          </View>
          <Text style={styles.onboardingDesc}>
            Why it is needed: Allows Screen Guard to detect foreground window
            changes, capture in-memory window screenshots for local LiteRT
            classification, and attach a trusted{' '}
            <Text style={styles.monoInline}>TYPE_ACCESSIBILITY_OVERLAY</Text>{' '}
            shield over explicit content.
          </Text>
          <Pressable
            onPress={onOpenAccessibilitySettings}
            style={styles.onboardingActionBtn}>
            <Text style={styles.onboardingActionText}>
              {permissionGranted
                ? 'Manage Accessibility Service in Android Settings'
                : 'Open Accessibility Settings to Enable Screen Guard'}
            </Text>
          </Pressable>
        </View>

        <View style={[styles.onboardingItem, styles.onboardingItemLast]}>
          <View style={styles.onboardingHeader}>
            <Text style={styles.onboardingTitle}>
              2. Display Over Other Apps (Fallback Overlay)
            </Text>
            <StatusBadge
              label={
                overlayPermissionGranted
                  ? 'GRANTED'
                  : serviceActive
                  ? 'COVERED BY A11Y'
                  : 'OPTIONAL'
              }
              tone={
                overlayPermissionGranted || serviceActive
                  ? 'success'
                  : 'neutral'
              }
              showDot={false}
            />
          </View>
          <Text style={styles.onboardingDesc}>
            Why it is needed: When Accessibility Service is active, Screen Guard
            uses native{' '}
            <Text style={styles.monoInline}>TYPE_ACCESSIBILITY_OVERLAY</Text>{' '}
            automatically. Granting{' '}
            <Text style={styles.monoInline}>SYSTEM_ALERT_WINDOW</Text> provides
            a secondary{' '}
            <Text style={styles.monoInline}>TYPE_APPLICATION_OVERLAY</Text>{' '}
            fallback and allows diagnostic overlay testing even before
            Accessibility is bound.
          </Text>
          <Pressable
            onPress={onOpenOverlaySettings}
            style={styles.onboardingActionBtn}>
            <Text style={styles.onboardingActionText}>
              {overlayPermissionGranted
                ? 'Manage Display Over Other Apps Permission'
                : 'Open Display Over Other Apps Settings'}
            </Text>
          </Pressable>
        </View>
      </View>

      {/* Milestone 2 On-Device LiteRT Model Status Card */}
      <View style={styles.sectionCard}>
        <View style={styles.cardHeaderInline}>
          <Text style={styles.sectionTitle}>On-Device LiteRT NSFW Model</Text>
          <StatusBadge
            label={status.modelLoaded ? 'MODEL LOADED' : 'READY TO INIT'}
            tone={status.modelLoaded ? 'success' : 'accent'}
            showDot
          />
        </View>

        <MetricRow
          label="Model Name & ID"
          value={status.modelId || 'nsfw-mobilenet-v2-140-224'}
          mono
        />
        <MetricRow
          label="Model Version"
          value={status.modelVersion || '2.1.0-litert-2stage'}
          subValue={`Size: ${(
            (status.modelSizeBytes || 17251928) /
            (1024 * 1024)
          ).toFixed(2)} MB · Init: ${status.modelInitDurationMs || 0} ms`}
          mono
        />
        <MetricRow
          label="Hardware Delegate"
          value={status.activeDelegate || 'CPU_XNNPACK_4T'}
          subValue={status.inputTensorSpec}
          mono
        />
        <MetricRow
          label="Temporal Decision State"
          value={status.temporalState || 'SAFE_STABLE'}
          valueColor={
            status.temporalState === 'CONFIRMED_DETECTION'
              ? colors.danger
              : status.temporalState === 'PENDING_CONFIRMATION'
              ? colors.warning
              : colors.success
          }
          subValue={`EWMA Smoothed Explicit: ${(
            (status.smoothedExplicitScore || 0) * 100
          ).toFixed(1)}% · Safe: ${(
            (status.smoothedSafeScore || 1) * 100
          ).toFixed(1)}%`}
          mono
          isLast
        />
      </View>

      {/* Classification Telemetry & Counters */}
      <View style={styles.sectionCard}>
        <View style={styles.cardHeaderInline}>
          <Text style={styles.sectionTitle}>
            Classification Telemetry & Counts
          </Text>
          <StatusBadge
            label={`${status.totalFramesClassified} Frames`}
            tone="accent"
            showDot={false}
          />
        </View>

        <View style={styles.counterGrid}>
          <View style={styles.counterBox}>
            <Text style={styles.counterValue}>
              {status.totalFramesClassified}
            </Text>
            <Text style={styles.counterLabel}>Total Classified</Text>
          </View>
          <View style={styles.counterBox}>
            <Text
              style={[
                styles.counterValue,
                status.explicitDetections > 0 && {color: colors.danger},
              ]}>
              {status.explicitDetections}
            </Text>
            <Text style={styles.counterLabel}>Explicit</Text>
          </View>
          <View style={styles.counterBox}>
            <Text
              style={[
                styles.counterValue,
                status.nudityDetections > 0 && {color: colors.warning},
              ]}>
              {status.nudityDetections}
            </Text>
            <Text style={styles.counterLabel}>Nudity</Text>
          </View>
          <View style={styles.counterBox}>
            <Text style={styles.counterValue}>
              {status.uncertainClassifications}
            </Text>
            <Text style={styles.counterLabel}>Uncertain</Text>
          </View>
        </View>

        <MetricRow
          label="Safe / Non-Explicit / Sexual Activity"
          value={`${status.nonExplicitDetections + status.safeClassifications} Non-Explicit · ${status.sexualActivityDetections || 0} Sexual Activity · ${status.suggestiveDetections} Suggestive`}
          mono
        />
        <MetricRow
          label="dHash Duplicate Frames Skipped"
          value={`${status.duplicateFramesSkipped} static frames skipped`}
          subValue={`Obsolete frames dropped (backpressure): ${status.obsoleteFramesDropped}`}
          mono
          isLast
        />
      </View>

      {/* Latest Classification Result (Metadata only — never displays thumbnails of explicit content) */}
      <View style={styles.sectionCard}>
        <View style={styles.cardHeaderInline}>
          <Text style={styles.sectionTitle}>Latest Classification Output</Text>
          <StatusBadge
            label={`Confirmed: ${status.totalConfirmedInterventions}`}
            tone={
              status.totalConfirmedInterventions > 0 ? 'danger' : 'neutral'
            }
            showDot={false}
          />
        </View>

        {latest ? (
          <>
            <MetricRow
              label="Latest Predicted Category"
              value={latest.predictedCategory || latest.category}
              valueColor={
                latest.predictedCategory === 'EXPLICIT' ||
                latest.predictedCategory === 'NUDITY' ||
                latest.predictedCategory === 'SEXUAL_ACTIVITY'
                  ? colors.danger
                  : latest.predictedCategory === 'SUGGESTIVE' ||
                    latest.predictedCategory === 'UNCERTAIN'
                  ? colors.warning
                  : colors.success
              }
              subValue={
                latest.originalCategory &&
                latest.originalCategory !== latest.predictedCategory
                  ? `Resolved from Stage 1 baseline: ${latest.originalCategory} (${(
                      (latest.originalConfidence || 0) * 100
                    ).toFixed(1)}%)`
                  : undefined
              }
              mono
            />
            <MetricRow
              label="Stage 2 & Low-Light Preprocessing"
              value={`${latest.preprocessingApplied || 'ORIGINAL'}${
                latest.isLowLightFrame
                  ? ' (Low-Light L=' +
                    (latest.meanLuminance || 0).toFixed(0) +
                    ')'
                  : ''
              }`}
              subValue={
                latest.stage2Triggered
                  ? `Stage 2 OpenNSFW ResNet-50: NSFW ${(
                      (latest.stage2NsfwScore || 0) * 100
                    ).toFixed(1)}% (${latest.stage2DurationMs || 0} ms)`
                  : 'Stage 2 verifier not required (Stage 1 high confidence)'
              }
              mono
            />
            <MetricRow
              label="Classification Confidence"
              value={`${((latest.confidence || 0) * 100).toFixed(1)}%`}
              subValue={`Threshold: ${(latest.thresholdUsed * 100).toFixed(
                0,
              )}% · Temporal State: ${latest.temporalState || 'SAFE_STABLE'}`}
              mono
            />
            <MetricRow
              label="Inference Duration"
              value={
                latest.isDuplicateSkipped
                  ? '0 ms (dHash Cache Hit)'
                  : `${latest.processingDurationMs} ms`
              }
              subValue={`Delegate: ${
                latest.delegateUsed || status.activeDelegate
              } · dHash: ${latest.frameHash || 'N/A'}`}
              mono
            />
            <MetricRow
              label="Raw 5-Class Probabilities"
              value={`neutral ${((latest.neutralScore || 0) * 100).toFixed(
                1,
              )}% · porn ${((latest.pornScore || 0) * 100).toFixed(
                1,
              )}% · sexy ${((latest.sexyScore || 0) * 100).toFixed(1)}%`}
              subValue={`drawings ${((latest.drawingsScore || 0) * 100).toFixed(
                1,
              )}% · hentai ${((latest.hentaiScore || 0) * 100).toFixed(1)}%`}
              mono
            />
            <MetricRow
              label="Temporal Confirmation Streak"
              value={`${latest.consecutiveDetections} / ${preferences.confirmationFramesRequired} consecutive frames`}
              subValue={`Evaluated on ${latest.frameWidth}×${latest.frameHeight} buffer for ${latest.packageName}`}
              valueColor={
                latest.confirmedIntervention
                  ? colors.danger
                  : colors.textSecondary
              }
              isLast
            />
          </>
        ) : (
          <View style={styles.emptyResultBox}>
            <Text style={styles.emptyResultTitle}>
              No live frame classified yet in this session
            </Text>
            <Text style={styles.emptyResultSubtitle}>
              Navigate between apps with Protection enabled, or open the
              Classifier tab to run on-device LiteRT inference on the 36-sample
              evaluation suite.
            </Text>
          </View>
        )}
      </View>

      {/* Core System & Capture Status */}
      <View style={styles.sectionCard}>
        <Text style={styles.sectionTitle}>
          Accessibility & Capture Pipeline
        </Text>
        <MetricRow
          label="Accessibility Service"
          value={
            status.serviceConnected ? 'BOUND & CONNECTED' : 'DISCONNECTED'
          }
          valueColor={
            status.serviceConnected ? colors.success : colors.warning
          }
          mono
        />
        <MetricRow
          label="Current Foreground App"
          value={status.currentForegroundPackage || 'None'}
          subValue={`${status.currentForegroundAppLabel} (windowId: ${status.currentWindowId})`}
          mono
        />
        <MetricRow
          label="Last Screenshot Capture"
          value={
            status.lastScreenshotWidth > 0
              ? `${status.lastScreenshotWidth}×${status.lastScreenshotHeight} (${status.lastScreenshotDurationMs} ms)`
              : status.lastScreenshotStatus
          }
          subValue={`Method: ${status.lastScreenshotMethod} · Captures: ${status.totalScreenshotsCaptured} OK / ${status.totalScreenshotFailures} Failed`}
          mono
        />
        <MetricRow
          label="HardwareBuffer Audit"
          value={`${status.bufferDiagnostics.hardwareBuffersClosed}/${status.bufferDiagnostics.hardwareBuffersAllocated} Closed · Active: ${status.bufferDiagnostics.activeInMemoryBuffers}`}
          valueColor={
            status.bufferDiagnostics.activeInMemoryBuffers === 0
              ? colors.success
              : colors.warning
          }
          mono
          isLast
        />
      </View>
    </ScrollView>
  );
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
  heroCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.borderStrong,
    padding: spacing.lg,
    marginBottom: spacing.md,
  },
  heroTopRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.md,
  },
  refreshBtn: {
    paddingHorizontal: spacing.sm + 2,
    paddingVertical: 4,
    borderRadius: radius.sm,
    backgroundColor: colors.surfaceElevated,
    borderWidth: 1,
    borderColor: colors.border,
  },
  refreshText: {
    fontSize: 11.5,
    fontWeight: '600',
    color: colors.textSecondary,
  },
  heroTitle: {
    fontSize: 19,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: spacing.xs + 2,
  },
  heroSubtitle: {
    fontSize: 13.5,
    color: colors.textSecondary,
    lineHeight: 20,
    marginBottom: spacing.md,
  },
  sensitivityRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs + 4,
    marginBottom: spacing.md,
  },
  sensitivityLabel: {
    fontSize: 12.5,
    fontWeight: '600',
    color: colors.textSecondary,
  },
  sensitivityPills: {
    flexDirection: 'row',
    gap: spacing.xs + 2,
  },
  sensitivityPill: {
    paddingHorizontal: 12,
    paddingVertical: 5,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
  },
  sensitivityPillActive: {
    borderColor: colors.accentBorder,
    backgroundColor: colors.accentSoft,
  },
  sensitivityPillText: {
    fontSize: 12,
    fontWeight: '600',
    color: colors.textSecondary,
  },
  sensitivityPillTextActive: {
    color: '#60A5FA',
    fontWeight: '700',
  },
  primaryButton: {
    paddingVertical: 13,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  primaryButtonActive: {
    backgroundColor: colors.accent,
  },
  primaryButtonWarning: {
    backgroundColor: colors.warning,
  },
  primaryButtonOutline: {
    backgroundColor: colors.surfaceElevated,
    borderWidth: 1,
    borderColor: colors.borderStrong,
  },
  primaryButtonText: {
    fontSize: 14,
    fontWeight: '700',
    color: '#FFFFFF',
    letterSpacing: 0.2,
  },
  primaryButtonTextOutline: {
    color: colors.textPrimary,
  },
  quickActionRow: {
    flexDirection: 'row',
    gap: spacing.xs + 2,
    marginTop: spacing.sm + 2,
  },
  secondaryButton: {
    flex: 1,
    paddingVertical: 9,
    paddingHorizontal: spacing.xs + 2,
    borderRadius: radius.sm,
    backgroundColor: colors.surfaceSubtle,
    borderWidth: 1,
    borderColor: colors.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  secondaryButtonText: {
    fontSize: 11.5,
    fontWeight: '600',
    color: colors.textSecondary,
    textAlign: 'center',
  },
  sectionCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    marginBottom: spacing.md,
  },
  cardHeaderInline: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.xs,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: colors.textSecondary,
    textTransform: 'uppercase',
    letterSpacing: 0.6,
    marginBottom: spacing.xs,
  },
  counterGrid: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginVertical: spacing.sm,
  },
  counterBox: {
    flex: 1,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    paddingVertical: spacing.sm + 2,
    alignItems: 'center',
  },
  counterValue: {
    fontSize: 17,
    fontWeight: '700',
    color: colors.textPrimary,
    fontFamily: typography.mono,
  },
  counterLabel: {
    fontSize: 10,
    color: colors.textMuted,
    marginTop: 2,
    textTransform: 'uppercase',
    textAlign: 'center',
  },
  onboardingItem: {
    paddingVertical: spacing.sm + 2,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  onboardingItemLast: {
    borderBottomWidth: 0,
    paddingBottom: 4,
  },
  onboardingHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 4,
    gap: spacing.sm,
  },
  onboardingTitle: {
    fontSize: 13.5,
    fontWeight: '700',
    color: colors.textPrimary,
    flex: 1,
  },
  onboardingDesc: {
    fontSize: 12,
    color: colors.textSecondary,
    lineHeight: 17.5,
    marginBottom: spacing.sm,
  },
  monoInline: {
    fontFamily: typography.mono,
    color: colors.textPrimary,
    fontSize: 11.5,
  },
  onboardingActionBtn: {
    backgroundColor: colors.surfaceElevated,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.borderStrong,
    paddingVertical: 9,
    paddingHorizontal: spacing.md,
    alignItems: 'center',
  },
  onboardingActionText: {
    fontSize: 12,
    fontWeight: '600',
    color: '#60A5FA',
  },
  outlineBtn: {
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.borderStrong,
    paddingVertical: 9,
    alignItems: 'center',
  },
  outlineBtnText: {
    fontSize: 12,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  emptyResultBox: {
    paddingVertical: spacing.md,
  },
  emptyResultTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.textPrimary,
    marginBottom: 4,
  },
  emptyResultSubtitle: {
    fontSize: 12.5,
    color: colors.textSecondary,
    lineHeight: 18,
  },
});
