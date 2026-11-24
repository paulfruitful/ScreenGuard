import React, {useMemo} from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {ActivityEventRow} from '../components/ActivityEventRow';
import {
  BarsIcon,
  ClockIcon,
  PauseBarsIcon,
  PlayTriangleIcon,
  PulseIcon,
  SettingsGearIcon,
  ShieldCheckIcon,
} from '../components/AppIcons';
import {ScreenGuardLogo} from '../components/ScreenGuardLogo';
import {colors, radius, spacing} from '../theme/theme';
import {
  ActivityLogEntry,
  MonitoringStatus,
  UserPreferences,
} from '../types/screenguard';

interface DashboardScreenProps {
  status: MonitoringStatus;
  preferences: UserPreferences;
  activityLogs: ActivityLogEntry[];
  onOpenAccessibilitySettings: () => void;
  onToggleProtection: (enabled: boolean) => void;
  onNavigateToActivity: () => void;
  onNavigateToSettings: () => void;
  onRefresh: () => void;
}

export function DashboardScreen({
  status,
  preferences,
  activityLogs,
  onOpenAccessibilitySettings,
  onToggleProtection,
  onNavigateToActivity,
  onNavigateToSettings,
  onRefresh,
}: DashboardScreenProps) {
  const permissionGranted = status.accessibilityPermissionGranted;
  const serviceActive = permissionGranted && status.serviceConnected;
  const monitoringActive = serviceActive && status.protectionEnabled;

  const summary = status.protectionSummary;
  const itemsBlocked =
    (summary?.totalProtectionEvents || 0) +
    (status.totalConfirmedInterventions || 0);

  const timeProtectedLabel = useMemo(() => {
    if (!monitoringActive) {
      return 'Paused';
    }
    const timestamps = activityLogs
      .map(l => l.timestamp)
      .filter(t => typeof t === 'number' && t > 0);
    if (timestamps.length === 0) {
      return 'Active';
    }
    const oldest = Math.min(...timestamps);
    const diffMins = Math.max(1, Math.floor((Date.now() - oldest) / 60000));
    if (diffMins < 60) {
      return `${diffMins}m`;
    }
    const hrs = Math.floor(diffMins / 60);
    const mins = diffMins % 60;
    return `${hrs}h ${mins}m`;
  }, [activityLogs, monitoringActive]);

  const protectionRateLabel = useMemo(() => {
    const total = status.totalFramesClassified || 0;
    if (total === 0) {
      return monitoringActive ? '100%' : '--';
    }
    const uncertain = status.uncertainClassifications || 0;
    const rate = Math.max(0, Math.min(100, ((total - uncertain) / total) * 100));
    return `${rate.toFixed(1)}%`;
  }, [
    monitoringActive,
    status.totalFramesClassified,
    status.uncertainClassifications,
  ]);

  const recentItems = useMemo(() => {
    const interesting = activityLogs.filter(
      item =>
        item.type === 'SUSPICIOUS_DETECTED' ||
        item.type === 'SCREENSHOT_SUCCESS' ||
        item.type === 'APP_DETECTED',
    );
    const source = interesting.length > 0 ? interesting : activityLogs;
    return source.slice(0, 3);
  }, [activityLogs]);

  const handlePrimaryAction = () => {
    if (!permissionGranted) {
      onOpenAccessibilitySettings();
      return;
    }
    onToggleProtection(!status.protectionEnabled);
  };

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      showsVerticalScrollIndicator={false}>
      {/* Top Brand Header on Royal Blue */}
      <View style={styles.topBar}>
        <Pressable onPress={onRefresh} style={styles.brandRow}>
          <ScreenGuardLogo size={40} />
          <View>
            <Text style={styles.brandTitle}>Screen Guard</Text>
            <Text style={styles.brandTagline}>Browse Safe. Live Free.</Text>
          </View>
        </Pressable>

        <Pressable
          onPress={onNavigateToSettings}
          style={({pressed}) => [
            styles.settingsCircle,
            pressed && {opacity: 0.75},
          ]}>
          <SettingsGearIcon size={22} color="#FFFFFF" />
        </Pressable>
      </View>

      {/* Hero Emblem & Status */}
      <View style={styles.heroSection}>
        <ScreenGuardLogo size={108} withHalo style={styles.heroLogo} />

        <View
          style={[
            styles.statusPill,
            !permissionGranted
              ? styles.statusPillWarning
              : monitoringActive
              ? styles.statusPillProtected
              : styles.statusPillPaused,
          ]}>
          <View style={styles.statusIconCircle}>
            <Text
              style={[
                styles.statusIconText,
                !permissionGranted
                  ? {color: '#B45309'}
                  : monitoringActive
                  ? {color: colors.success}
                  : {color: colors.textSecondary},
              ]}>
              {monitoringActive ? '✓' : '!'}
            </Text>
          </View>
          <Text style={styles.statusPillText}>
            {!permissionGranted
              ? 'Setup Needed'
              : monitoringActive
              ? 'Protected'
              : 'Paused'}
          </Text>
        </View>

        <Text style={styles.heroTitle}>
          {!permissionGranted
            ? 'Turn on Screen Guard'
            : monitoringActive
            ? 'Your device is safe'
            : 'Protection is paused'}
        </Text>

        <Text style={styles.heroSubtitle}>
          {!permissionGranted
            ? 'Enable Screen Guard in Accessibility Settings to start blocking inappropriate content automatically.'
            : monitoringActive
            ? 'Screen Guard is actively blocking inappropriate content.'
            : 'Tap Resume Protection below whenever you are ready to continue.'}
        </Text>

        {/* Primary Gold Pill Button */}
        <Pressable
          onPress={handlePrimaryAction}
          style={({pressed}) => [
            styles.goldButton,
            pressed && {backgroundColor: colors.goldPressed},
          ]}>
          {permissionGranted ? (
            monitoringActive ? (
              <PauseBarsIcon size={15} color={colors.goldText} />
            ) : (
              <PlayTriangleIcon size={14} color={colors.goldText} />
            )
          ) : null}
          <Text style={styles.goldButtonText}>
            {!permissionGranted
              ? 'Enable Protection'
              : monitoringActive
              ? 'Pause Protection'
              : 'Resume Protection'}
          </Text>
        </Pressable>
      </View>

      {/* 3-Column White Stats Card */}
      <View style={styles.statsCard}>
        <View style={styles.statCol}>
          <View style={styles.statIconWrap}>
            <ShieldCheckIcon size={22} color={colors.royalBlue} />
          </View>
          <Text style={styles.statValue}>{itemsBlocked}</Text>
          <Text style={styles.statLabel}>Items Blocked</Text>
        </View>

        <View style={styles.statDivider} />

        <View style={styles.statCol}>
          <View style={styles.statIconWrap}>
            <ClockIcon size={22} color={colors.royalBlue} />
          </View>
          <Text style={styles.statValue}>{timeProtectedLabel}</Text>
          <Text style={styles.statLabel}>Time Protected</Text>
        </View>

        <View style={styles.statDivider} />

        <View style={styles.statCol}>
          <View style={styles.statIconWrap}>
            <BarsIcon size={22} color={colors.royalBlue} />
          </View>
          <Text style={styles.statValue}>{protectionRateLabel}</Text>
          <Text style={styles.statLabel}>Protection Rate</Text>
        </View>
      </View>

      {/* Recent Activity Card */}
      <View style={styles.activityCard}>
        <View style={styles.activityHeader}>
          <View style={styles.activityTitleRow}>
            <PulseIcon size={20} color={colors.royalBlue} />
            <Text style={styles.activityTitle}>Recent Activity</Text>
          </View>
          <Pressable onPress={onNavigateToActivity} hitSlop={8}>
            <Text style={styles.viewAllText}>View All ›</Text>
          </Pressable>
        </View>

        {recentItems.length > 0 ? (
          recentItems.map((item, idx) => (
            <ActivityEventRow
              key={item.id}
              entry={item}
              compact
              isLast={idx === recentItems.length - 1}
            />
          ))
        ) : (
          <View style={styles.emptyActivity}>
            <Text style={styles.emptyActivityText}>
              No recent alerts — your screen is clear.
            </Text>
          </View>
        )}
      </View>

      {/* Strict Mode Footer */}
      <View style={styles.modeFooter}>
        <Text style={styles.modeFooterText}>
          Protection Mode:{' '}
          <Text style={styles.modeFooterHighlight}>Strict</Text>
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.royalBlue,
  },
  content: {
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.sm,
    paddingBottom: spacing.xxl + 16,
  },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.md,
  },
  brandRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm + 2,
  },
  brandTitle: {
    fontSize: 20,
    fontWeight: '800',
    color: colors.textOnBlue,
    letterSpacing: 0.2,
  },
  brandTagline: {
    fontSize: 12,
    color: colors.textOnBlueMuted,
    marginTop: 1,
  },
  settingsCircle: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: colors.royalBlueGlass,
    alignItems: 'center',
    justifyContent: 'center',
  },
  heroSection: {
    alignItems: 'center',
    paddingVertical: spacing.sm,
    marginBottom: spacing.lg,
  },
  heroLogo: {
    marginBottom: spacing.md,
  },
  statusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 6,
    borderRadius: radius.pill,
    gap: 7,
    marginBottom: spacing.md,
  },
  statusPillProtected: {
    backgroundColor: colors.success,
  },
  statusPillPaused: {
    backgroundColor: '#475569',
  },
  statusPillWarning: {
    backgroundColor: colors.gold,
  },
  statusIconCircle: {
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: '#FFFFFF',
    alignItems: 'center',
    justifyContent: 'center',
  },
  statusIconText: {
    fontSize: 11,
    fontWeight: '900',
  },
  statusPillText: {
    color: '#FFFFFF',
    fontSize: 13.5,
    fontWeight: '700',
  },
  heroTitle: {
    fontSize: 26,
    fontWeight: '800',
    color: colors.textOnBlue,
    textAlign: 'center',
    marginBottom: spacing.xs + 2,
  },
  heroSubtitle: {
    fontSize: 14,
    color: colors.textOnBlueMuted,
    textAlign: 'center',
    lineHeight: 20,
    maxWidth: 280,
    marginBottom: spacing.lg,
  },
  goldButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    backgroundColor: colors.gold,
    borderRadius: radius.pill,
    paddingVertical: 15,
    paddingHorizontal: 32,
    width: '92%',
    elevation: 4,
  },
  goldButtonText: {
    fontSize: 16,
    fontWeight: '800',
    color: colors.goldText,
  },
  statsCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.surface,
    borderRadius: radius.xl,
    paddingVertical: spacing.lg,
    paddingHorizontal: spacing.sm,
    marginBottom: spacing.md + 2,
    elevation: 3,
  },
  statCol: {
    flex: 1,
    alignItems: 'center',
    paddingHorizontal: 4,
  },
  statIconWrap: {
    height: 26,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 6,
  },
  statValue: {
    fontSize: 19,
    fontWeight: '800',
    color: colors.textPrimary,
    marginBottom: 2,
  },
  statLabel: {
    fontSize: 11.5,
    color: colors.textSecondary,
    textAlign: 'center',
  },
  statDivider: {
    width: 1,
    height: 48,
    backgroundColor: colors.border,
  },
  activityCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.xl,
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.lg,
    paddingBottom: spacing.sm,
    elevation: 3,
  },
  activityHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.xs,
  },
  activityTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  activityTitle: {
    fontSize: 16,
    fontWeight: '800',
    color: colors.textPrimary,
  },
  viewAllText: {
    fontSize: 13.5,
    fontWeight: '700',
    color: colors.accent,
  },
  emptyActivity: {
    paddingVertical: spacing.lg,
    alignItems: 'center',
  },
  emptyActivityText: {
    fontSize: 13.5,
    color: colors.textSecondary,
  },
  modeFooter: {
    marginTop: spacing.md,
    alignItems: 'center',
    paddingVertical: spacing.xs,
  },
  modeFooterText: {
    fontSize: 12.5,
    color: colors.textOnBlueMuted,
  },
  modeFooterHighlight: {
    color: colors.gold,
    fontWeight: '700',
  },
});
