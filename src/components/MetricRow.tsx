import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {colors, spacing, typography} from '../theme/theme';

interface MetricRowProps {
  label: string;
  value: string;
  subValue?: string;
  valueColor?: string;
  mono?: boolean;
  isLast?: boolean;
}

export function MetricRow({
  label,
  value,
  subValue,
  valueColor = colors.textPrimary,
  mono = false,
  isLast = false,
}: MetricRowProps) {
  return (
    <View style={[styles.row, !isLast && styles.rowBorder]}>
      <Text style={styles.label}>{label}</Text>
      <View style={styles.valueWrap}>
        <Text
          numberOfLines={2}
          style={[
            styles.value,
            {color: valueColor},
            mono && {fontFamily: typography.mono, fontSize: 12},
          ]}>
          {value}
        </Text>
        {subValue ? <Text style={styles.subValue}>{subValue}</Text> : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: spacing.sm + 3,
    gap: spacing.md,
  },
  rowBorder: {
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  label: {
    fontSize: 13.5,
    color: colors.textSecondary,
    flexShrink: 0,
  },
  valueWrap: {
    flex: 1,
    alignItems: 'flex-end',
  },
  value: {
    fontSize: 13.5,
    fontWeight: '600',
    textAlign: 'right',
  },
  subValue: {
    fontSize: 11.5,
    color: colors.textMuted,
    marginTop: 2,
    textAlign: 'right',
  },
});
