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
import {MonitoringStatus, UserPreferences} from '../types/screenguard';

interface DashboardScreenProps {
  status: MonitoringStatus;
  preferences: UserPreferences;
  onOpenAccessibilitySettings: () => void;
  onToggleProtection: (enabled: boolean) => void;
  onNavigateToTesting: () => void;
  onNavigateToClassifier: () => void;
  onRefresh: () => void;
}

export function DashboardScreen({
  status,
  preferences,
  onOpenAccessibilitySettings,
  onToggleProtection,
  onNavigateToTesting,
  onNavigateToClassifier,
  onRefresh,
}: DashboardScreenProps) {
  const permissionGranted = status.accessibilityPermissionGranted;
  const serviceActive = permissionGranted && status.serviceConnected;
  const monitoringActive = serviceActive && status.protectionEnabled;

  const handlePrimaryAction = () => {
    if (!permissionGranted) {
      onOpenAccessibilitySettings();
      return;
    }
    onToggleProtection(!status.protectionEnabled);
  };

  const latest = status.latestClassification;

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
                ? 'Protection Active'
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
            ? 'Accessibility Access Needed'
            : monitoringActive
            ? 'Screen Guard is Observing Active Windows'
            : 'Observation Paused by User'}
        </Text>

        <Text style={styles.heroSubtitle}>
          {!permissionGranted
            ? 'Enable Screen Guard in Android Accessibility Settings to allow event-driven on-device window observation and LiteRT NSFW classification.'
            : monitoringActive
            ? `On-device LiteRT inference active (${preferences.samplingIntervalMs}ms cooldown, ${preferences.debounceDelayMs}ms debounce). HardwareBuffers are released immediately after 224×224 downsampling.`
            : 'Accessibility Service is bound, but screenshot capture and LiteRT classification are paused.'}
        </Text>

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
              : 'Enable Protection'}
          </Text>
        </Pressable>

        <View style={styles.quickActionRow}>
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
            <Text style={styles.secondaryButtonText}>
              Capture Diagnostics
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
          value={status.modelVersion || '1.2.0-litert-fp32'}
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

      {/* Latest Classification Result */}
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
                latest.isLowLightFrame ? ' (Low-Light L=' + (latest.meanLuminance || 0).toFixed(0) + ')' : ''
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
              Classifier tab to run on-device LiteRT inference on the 24-sample
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
    marginBottom: spacing.lg,
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
    gap: spacing.sm,
    marginTop: spacing.sm + 2,
  },
  secondaryButton: {
    flex: 1,
    paddingVertical: 9,
    paddingHorizontal: spacing.sm,
    borderRadius: radius.sm,
    backgroundColor: colors.surfaceSubtle,
    borderWidth: 1,
    borderColor: colors.border,
    alignItems: 'center',
  },
  secondaryButtonText: {
    fontSize: 12,
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
    fontSize: 10.5,
    color: colors.textMuted,
    marginTop: 2,
    textTransform: 'uppercase',
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
