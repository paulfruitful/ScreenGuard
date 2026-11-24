import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {colors, radius, spacing} from '../theme/theme';
import {ActivityLogEntry} from '../types/screenguard';
import {toFriendlyActivity} from '../utils/friendlyFormat';
import {BlockedCircleIcon, CameraBadgeIcon, ShieldCheckIcon} from './AppIcons';

interface ActivityEventRowProps {
  entry: ActivityLogEntry;
  compact?: boolean;
  isLast?: boolean;
}

export function ActivityEventRow({
  entry,
  compact = false,
  isLast = false,
}: ActivityEventRowProps) {
  const friendly = toFriendlyActivity(entry);

  const renderIcon = () => {
    if (friendly.kind === 'blocked') {
      return <BlockedCircleIcon size={28} color={colors.danger} />;
    }
    if (friendly.kind === 'captured') {
      return <CameraBadgeIcon size={28} color="#2563EB" />;
    }
    if (friendly.kind === 'warning') {
      return <BlockedCircleIcon size={28} color={colors.warning} />;
    }
    return <ShieldCheckIcon size={26} color={colors.royalBlue} />;
  };

  if (compact) {
    return (
      <View style={[styles.compactRow, !isLast && styles.compactBorder]}>
        <View style={styles.iconBox}>{renderIcon()}</View>
        <View style={styles.textCol}>
          <Text style={styles.title} numberOfLines={1}>
            {friendly.title}
          </Text>
          <Text style={styles.subtitle} numberOfLines={1}>
            {friendly.subtitle}
          </Text>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.card}>
      <View style={styles.iconBox}>{renderIcon()}</View>
      <View style={styles.textCol}>
        <Text style={styles.title}>{friendly.title}</Text>
        <Text style={styles.subtitle}>{friendly.subtitle}</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.md + 2,
    marginBottom: spacing.sm + 2,
    gap: spacing.md,
  },
  compactRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: spacing.md,
    gap: spacing.md,
  },
  compactBorder: {
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  iconBox: {
    width: 34,
    alignItems: 'center',
    justifyContent: 'center',
  },
  textCol: {
    flex: 1,
  },
  title: {
    fontSize: 14.5,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: 2,
  },
  subtitle: {
    fontSize: 12.5,
    color: colors.textSecondary,
  },
});
