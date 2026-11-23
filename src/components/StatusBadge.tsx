import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {colors, radius, spacing} from '../theme/theme';

export type BadgeTone = 'success' | 'warning' | 'danger' | 'accent' | 'neutral';

interface StatusBadgeProps {
  label: string;
  tone?: BadgeTone;
  showDot?: boolean;
}

export function StatusBadge({
  label,
  tone = 'neutral',
  showDot = true,
}: StatusBadgeProps) {
  const palette = getPalette(tone);

  return (
    <View
      style={[
        styles.container,
        {
          backgroundColor: palette.bg,
          borderColor: palette.border,
        },
      ]}>
      {showDot && (
        <View style={[styles.dot, {backgroundColor: palette.text}]} />
      )}
      <Text style={[styles.label, {color: palette.text}]}>{label}</Text>
    </View>
  );
}

function getPalette(tone: BadgeTone) {
  switch (tone) {
    case 'success':
      return {
        bg: colors.successSoft,
        border: colors.successBorder,
        text: colors.success,
      };
    case 'warning':
      return {
        bg: colors.warningSoft,
        border: colors.warningBorder,
        text: colors.warning,
      };
    case 'danger':
      return {
        bg: colors.dangerSoft,
        border: colors.dangerBorder,
        text: colors.danger,
      };
    case 'accent':
      return {
        bg: colors.accentSoft,
        border: colors.accentBorder,
        text: '#60A5FA',
      };
    case 'neutral':
    default:
      return {
        bg: colors.neutralBadge,
        border: colors.borderStrong,
        text: colors.textSecondary,
      };
  }
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.sm + 2,
    paddingVertical: 4,
    borderRadius: radius.pill,
    borderWidth: 1,
    alignSelf: 'flex-start',
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    marginRight: 6,
  },
  label: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.4,
    textTransform: 'uppercase',
  },
});
