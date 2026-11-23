import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {colors, radius, spacing, typography} from '../theme/theme';
import {ActivityEventType, ActivityLogEntry} from '../types/screenguard';
import {BadgeTone, StatusBadge} from './StatusBadge';

interface ActivityEventRowProps {
  entry: ActivityLogEntry;
}

export function ActivityEventRow({entry}: ActivityEventRowProps) {
  const meta = getEventMeta(entry.type);
  const formattedTime = formatTimestamp(entry.timestamp);

  return (
    <View style={styles.card}>
      <View style={styles.topRow}>
        <StatusBadge label={meta.badge} tone={meta.tone} showDot={false} />
        <Text style={styles.timestamp}>{formattedTime}</Text>
      </View>

      <Text style={styles.title}>{entry.title}</Text>
      <Text style={styles.detail}>{entry.detail}</Text>

      <View style={styles.metaRow}>
        {entry.packageName && entry.packageName !== 'none' ? (
          <View style={styles.metaChip}>
            <Text style={styles.metaChipText}>{entry.packageName}</Text>
          </View>
        ) : null}

        {typeof entry.durationMs === 'number' && entry.durationMs > 0 ? (
          <View style={styles.metaChip}>
            <Text style={styles.metaChipText}>{entry.durationMs} ms</Text>
          </View>
        ) : null}

        {entry.width && entry.height ? (
          <View style={styles.metaChip}>
            <Text style={styles.metaChipText}>
              {entry.width}×{entry.height}
            </Text>
          </View>
        ) : null}

        {entry.captureMethod ? (
          <View style={styles.metaChip}>
            <Text style={styles.metaChipText}>{entry.captureMethod}</Text>
          </View>
        ) : null}

        {entry.errorCode && entry.errorCode !== 'NONE' ? (
          <View style={[styles.metaChip, styles.errorChip]}>
            <Text style={[styles.metaChipText, {color: colors.danger}]}>
              {entry.errorCode}
            </Text>
          </View>
        ) : null}
      </View>
    </View>
  );
}

function getEventMeta(type: ActivityEventType): {
  badge: string;
  tone: BadgeTone;
} {
  switch (type) {
    case 'APP_DETECTED':
      return {badge: 'App Detected', tone: 'accent'};
    case 'SCREENSHOT_SUCCESS':
      return {badge: 'Capture OK', tone: 'success'};
    case 'SCREENSHOT_FAILURE':
      return {badge: 'Capture Failed', tone: 'warning'};
    case 'CLASSIFICATION_COMPLETED':
      return {badge: 'LiteRT Classified', tone: 'neutral'};
    case 'SUSPICIOUS_DETECTED':
      return {badge: 'Suspicious Flagged', tone: 'danger'};
    case 'SERVICE_STATE':
    default:
      return {badge: 'Service State', tone: 'neutral'};
  }
}

function formatTimestamp(epochMs: number): string {
  if (!epochMs) {
    return '--:--:--';
  }
  const d = new Date(epochMs);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  const ms = String(d.getMilliseconds()).padStart(3, '0');
  return `${hh}:${mm}:${ss}.${ms}`;
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.md,
    marginBottom: spacing.sm,
  },
  topRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.xs + 2,
  },
  timestamp: {
    fontSize: 11.5,
    color: colors.textMuted,
    fontFamily: typography.mono,
  },
  title: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.textPrimary,
    marginBottom: 2,
  },
  detail: {
    fontSize: 12.5,
    color: colors.textSecondary,
    lineHeight: 18,
  },
  metaRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
    marginTop: spacing.sm,
  },
  metaChip: {
    backgroundColor: colors.surfaceSubtle,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.sm,
    paddingHorizontal: 7,
    paddingVertical: 2,
  },
  errorChip: {
    borderColor: colors.dangerBorder,
    backgroundColor: colors.dangerSoft,
  },
  metaChipText: {
    fontSize: 11,
    color: colors.textSecondary,
    fontFamily: typography.mono,
  },
});
