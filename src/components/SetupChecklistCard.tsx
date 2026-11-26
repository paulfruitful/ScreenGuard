import React, {useState} from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import {ScreenGuardNative} from '../services/ScreenGuardNative';
import {colors, radius, spacing} from '../theme/theme';
import {MonitoringStatus} from '../types/screenguard';

interface SetupChecklistCardProps {
  status: MonitoringStatus;
  onOpenAccessibilitySettings: () => void;
  onRefresh: () => void;
}

interface SetupStep {
  key: string;
  title: string;
  subtitle: string;
  done: boolean;
  actionLabel: string;
  onPress: () => void;
}

export function SetupChecklistCard({
  status,
  onOpenAccessibilitySettings,
  onRefresh,
}: SetupChecklistCardProps) {
  const [autostartTapped, setAutostartTapped] = useState(false);
  const [dismissed, setDismissed] = useState(false);

  const accessibilityDone =
    status.accessibilityPermissionGranted && status.serviceConnected;
  const overlayDone = Boolean(status.overlayPermissionGranted);
  const batteryDone = status.batteryOptimizationIgnored === true;
  const showAutostart = Boolean(status.isOemAutostartRecommended);

  const steps: SetupStep[] = [
    {
      key: 'a11y',
      title: 'Accessibility Protection',
      subtitle:
        status.accessibilityPermissionGranted && !status.serviceConnected
          ? 'Stopped by phone — tap to toggle Screen Guard OFF then back ON.'
          : 'Required to detect and shield explicit content across apps.',
      done: accessibilityDone,
      actionLabel:
        status.accessibilityPermissionGranted && !status.serviceConnected
          ? 'Reset'
          : 'Enable',
      onPress: onOpenAccessibilitySettings,
    },
    {
      key: 'overlay',
      title: 'Urgent Shield Alerts (Display Over Apps)',
      subtitle:
        'Allows the full-screen reconnect shield to alert you if Android stops the service.',
      done: overlayDone,
      actionLabel: 'Allow',
      onPress: () => {
        ScreenGuardNative.openOverlaySettings()
          .then(() => setTimeout(onRefresh, 800))
          .catch(() => {});
      },
    },
    {
      key: 'battery',
      title: 'Disable Battery Kill (No Restrictions)',
      subtitle:
        'Stops Android from sleeping or killing Screen Guard in the background.',
      done: batteryDone,
      actionLabel: 'Allow',
      onPress: () => {
        ScreenGuardNative.requestIgnoreBatteryOptimizations()
          .then(() => setTimeout(onRefresh, 800))
          .catch(() => {});
      },
    },
  ];

  if (showAutostart) {
    steps.push({
      key: 'autostart',
      title: 'Background Autostart (Xiaomi / OEM)',
      subtitle:
        'Keeps Screen Guard alive across cleanups and reboots. Toggle Screen Guard ON.',
      done: autostartTapped,
      actionLabel: 'Enable',
      onPress: () => {
        setAutostartTapped(true);
        ScreenGuardNative.openAutostartSettings().catch(() => {});
      },
    });
  }

  const completedCount = steps.filter(s => s.done).length;
  const allComplete = completedCount === steps.length;

  if (allComplete || (dismissed && accessibilityDone)) {
    return null;
  }

  return (
    <View style={styles.card}>
      <View style={styles.headerRow}>
        <View style={styles.headerLeft}>
          <Text style={styles.title}>One-Tap Protection Setup</Text>
          <Text style={styles.progressText}>
            {completedCount} of {steps.length} active · Makes protection rock-solid
          </Text>
        </View>
        {accessibilityDone ? (
          <Pressable onPress={() => setDismissed(true)} hitSlop={8}>
            <Text style={styles.hideText}>Hide</Text>
          </Pressable>
        ) : null}
      </View>

      {steps.map((step, idx) => (
        <View
          key={step.key}
          style={[styles.stepRow, idx > 0 && styles.stepDivider]}>
          <View
            style={[
              styles.statusDot,
              step.done ? styles.statusDotDone : styles.statusDotPending,
            ]}>
            <Text style={styles.statusDotText}>{step.done ? '✓' : '!'}</Text>
          </View>

          <View style={styles.stepTextCol}>
            <Text style={styles.stepTitle}>{step.title}</Text>
            <Text style={styles.stepSub}>{step.subtitle}</Text>
          </View>

          {step.done ? (
            <View style={styles.donePill}>
              <Text style={styles.donePillText}>Done</Text>
            </View>
          ) : (
            <Pressable
              onPress={step.onPress}
              style={({pressed}) => [
                styles.actionBtn,
                pressed && {opacity: 0.8},
              ]}>
              <Text style={styles.actionBtnText}>{step.actionLabel}</Text>
            </Pressable>
          )}
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.xl,
    padding: spacing.lg,
    marginBottom: spacing.md + 2,
    borderWidth: 1.5,
    borderColor: colors.gold,
    elevation: 4,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: spacing.sm,
  },
  headerLeft: {
    flex: 1,
    paddingRight: spacing.sm,
  },
  title: {
    fontSize: 15.5,
    fontWeight: '800',
    color: colors.royalBlue,
  },
  progressText: {
    fontSize: 12,
    color: colors.textSecondary,
    marginTop: 2,
  },
  hideText: {
    fontSize: 12.5,
    fontWeight: '700',
    color: colors.textSecondary,
  },
  stepRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: spacing.sm + 2,
    gap: 10,
  },
  stepDivider: {
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  statusDot: {
    width: 22,
    height: 22,
    borderRadius: 11,
    alignItems: 'center',
    justifyContent: 'center',
  },
  statusDotDone: {
    backgroundColor: colors.success,
  },
  statusDotPending: {
    backgroundColor: colors.gold,
  },
  statusDotText: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '900',
  },
  stepTextCol: {
    flex: 1,
  },
  stepTitle: {
    fontSize: 13.5,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  stepSub: {
    fontSize: 11.5,
    color: colors.textSecondary,
    lineHeight: 16,
    marginTop: 1,
  },
  actionBtn: {
    backgroundColor: colors.royalBlue,
    borderRadius: radius.pill,
    paddingHorizontal: 14,
    paddingVertical: 7,
  },
  actionBtnText: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '800',
  },
  donePill: {
    backgroundColor: 'rgba(27, 181, 92, 0.12)',
    borderRadius: radius.pill,
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  donePillText: {
    color: colors.success,
    fontSize: 11.5,
    fontWeight: '800',
  },
});
