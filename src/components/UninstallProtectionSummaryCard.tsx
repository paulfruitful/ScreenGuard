import React from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import {ShieldCheckIcon} from './AppIcons';
import {colors, radius, spacing} from '../theme/theme';
import {AccountabilityStatus} from '../types/screenguard';

interface Props {
  status: AccountabilityStatus;
  onOpenProtection: () => void;
}

export function UninstallProtectionSummaryCard({
  status,
  onOpenProtection,
}: Props) {
  const isLocked = status.active;
  const isAdminActive = status.deviceAdminActive;

  const summaryText =
    isLocked && isAdminActive
      ? 'Pause, DNS stop, and quick uninstall are locked.'
      : isLocked
        ? 'Pause and DNS stop are locked. Enable Device Admin for uninstall protection.'
        : 'Lock pausing, DNS stop, and quick uninstall with a PIN to prevent relapse.';

  return (
    <View style={styles.card}>
      <View style={styles.headerRow}>
        <View style={styles.titleLeft}>
          <ShieldCheckIcon size={20} color={colors.royalBlue} />
          <Text style={styles.cardTitle}>Uninstall Protection</Text>
        </View>
        <View
          style={[
            styles.badge,
            isLocked ? styles.badgeLocked : styles.badgeOff,
          ]}>
          <Text
            style={[
              styles.badgeText,
              isLocked ? styles.badgeTextLocked : styles.badgeTextOff,
            ]}>
            {isLocked ? 'LOCKED' : 'OFF'}
          </Text>
        </View>
      </View>

      <Text style={styles.bodyText}>{summaryText}</Text>

      <Pressable onPress={onOpenProtection} style={styles.openButton}>
        <Text style={styles.openButtonText}>
          {isLocked ? 'Manage Uninstall Protection' : 'Enable Uninstall Protection'}
        </Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.xl,
    padding: spacing.lg,
    marginBottom: spacing.md + 2,
    elevation: 3,
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.xs + 2,
  },
  titleLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: '800',
    color: colors.textPrimary,
  },
  badge: {
    paddingHorizontal: 10,
    paddingVertical: 3,
    borderRadius: radius.pill,
  },
  badgeLocked: {
    backgroundColor: colors.successSoft,
  },
  badgeOff: {
    backgroundColor: colors.warningSoft,
  },
  badgeText: {
    fontSize: 11,
    fontWeight: '800',
  },
  badgeTextLocked: {
    color: colors.success,
  },
  badgeTextOff: {
    color: '#92400E',
  },
  bodyText: {
    fontSize: 13,
    lineHeight: 19,
    color: colors.textSecondary,
  },
  openButton: {
    marginTop: spacing.md,
    alignItems: 'center',
    borderRadius: radius.pill,
    backgroundColor: colors.royalBlue,
    paddingVertical: 12,
  },
  openButtonText: {
    color: '#FFFFFF',
    fontSize: 13.5,
    fontWeight: '800',
  },
});
