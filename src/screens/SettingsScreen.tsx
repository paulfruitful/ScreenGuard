import React, {useState} from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import {ScreenGuardLogo} from '../components/ScreenGuardLogo';
import {colors, radius, spacing, typography} from '../theme/theme';
import {UserPreferences} from '../types/screenguard';
import {getFriendlyAppName} from '../utils/friendlyFormat';

interface SettingsScreenProps {
  preferences: UserPreferences;
  onUpdatePreferences: (partial: Partial<UserPreferences>) => Promise<void>;
  onToggleProtection: (enabled: boolean) => void;
  onOpenAccessibilitySettings: () => void;
  onOpenOverlaySettings: () => void;
}

const QUICK_EXCLUDE_CANDIDATES = [
  {pkg: 'com.whatsapp', name: 'WhatsApp'},
  {pkg: 'com.google.android.apps.messaging', name: 'Messages'},
  {pkg: 'com.google.android.gm', name: 'Gmail'},
  {pkg: 'com.android.settings', name: 'Settings'},
];

export function SettingsScreen({
  preferences,
  onUpdatePreferences,
  onToggleProtection,
  onOpenAccessibilitySettings,
  onOpenOverlaySettings,
}: SettingsScreenProps) {
  const [customPackage, setCustomPackage] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);

  const addExcludedPackage = (pkg: string) => {
    const trimmed = pkg.trim().toLowerCase();
    if (!trimmed) {
      return;
    }
    if (preferences.excludedPackages.includes(trimmed)) {
      setCustomPackage('');
      return;
    }
    onUpdatePreferences({
      excludedPackages: [...preferences.excludedPackages, trimmed],
    });
    setCustomPackage('');
  };

  const removeExcludedPackage = (pkg: string) => {
    onUpdatePreferences({
      excludedPackages: preferences.excludedPackages.filter(p => p !== pkg),
    });
  };

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      showsVerticalScrollIndicator={false}>
      {/* Royal Blue Header */}
      <View style={styles.royalHeader}>
        <View style={styles.titleWrap}>
          <ScreenGuardLogo size={32} />
          <View>
            <Text style={styles.heading}>Settings</Text>
            <Text style={styles.subheading}>
              Manage your protection & privacy preferences
            </Text>
          </View>
        </View>
      </View>

      <View style={styles.body}>
        {/* 1. Protection Basics */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Protection</Text>

          <View style={styles.switchRow}>
            <View style={styles.switchTextCol}>
              <Text style={styles.rowTitle}>Screen Guard Protection</Text>
              <Text style={styles.rowSubtitle}>
                Automatically hide explicit and inappropriate content across all
                your apps in Strict mode.
              </Text>
            </View>
            <Switch
              value={preferences.protectionEnabled}
              onValueChange={val => onToggleProtection(val)}
              trackColor={{false: colors.borderStrong, true: colors.royalBlue}}
              thumbColor="#FFFFFF"
            />
          </View>
        </View>

        {/* 2. Trusted / Excluded Apps */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Trusted Apps (Excluded)</Text>
          <Text style={styles.cardSub}>
            Screen Guard will never check or cover apps you add here.
          </Text>

          <View style={styles.chipWrap}>
            {preferences.excludedPackages.map(pkg => (
              <View key={pkg} style={styles.pkgChip}>
                <Text style={styles.pkgChipText}>
                  {getFriendlyAppName(pkg)}
                </Text>
                <Pressable
                  onPress={() => removeExcludedPackage(pkg)}
                  hitSlop={8}>
                  <Text style={styles.pkgChipRemove}>✕</Text>
                </Pressable>
              </View>
            ))}
          </View>

          <Text style={[styles.fieldLabel, {marginTop: spacing.md}]}>
            Quick Add
          </Text>
          <View style={styles.chipWrap}>
            {QUICK_EXCLUDE_CANDIDATES.map(item => {
              const already = preferences.excludedPackages.includes(item.pkg);
              return (
                <Pressable
                  key={item.pkg}
                  disabled={already}
                  onPress={() => addExcludedPackage(item.pkg)}
                  style={[styles.quickChip, already && {opacity: 0.45}]}>
                  <Text style={styles.quickChipText}>
                    {already ? `✓ ${item.name}` : `+ ${item.name}`}
                  </Text>
                </Pressable>
              );
            })}
          </View>

          <View style={[styles.inputRow, {marginTop: spacing.md}]}>
            <TextInput
              value={customPackage}
              onChangeText={setCustomPackage}
              placeholder="Add app package (e.g. com.whatsapp)"
              placeholderTextColor={colors.textMuted}
              autoCapitalize="none"
              autoCorrect={false}
              style={styles.textInput}
            />
            <Pressable
              onPress={() => addExcludedPackage(customPackage)}
              style={styles.addBtn}>
              <Text style={styles.addBtnText}>Add</Text>
            </Pressable>
          </View>
        </View>

        {/* 4. Permissions & Privacy Promise */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Permissions & Privacy</Text>
          <Text style={styles.cardSub}>
            100% Private on Your Device. Screen Guard checks your screen locally
            and never saves screenshots or uploads anything.
          </Text>

          <Pressable
            onPress={onOpenAccessibilitySettings}
            style={styles.outlineBtn}>
            <Text style={styles.outlineBtnText}>
              Manage Accessibility Access
            </Text>
          </Pressable>

          <Pressable
            onPress={onOpenOverlaySettings}
            style={[styles.outlineBtn, {marginTop: spacing.sm}]}>
            <Text style={styles.outlineBtnText}>
              Manage Display Over Other Apps
            </Text>
          </Pressable>
        </View>

        {/* 5. Optional Advanced Options */}
        <Pressable
          onPress={() => setShowAdvanced(prev => !prev)}
          style={styles.advancedToggle}>
          <Text style={styles.advancedToggleText}>
            {showAdvanced
              ? 'Hide Advanced Options ▲'
              : 'Show Advanced Options ▼'}
          </Text>
        </Pressable>

        {showAdvanced && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Advanced Options</Text>
            <View style={styles.switchRow}>
              <View style={styles.switchTextCol}>
                <Text style={styles.rowTitle}>Per-Window Capture</Text>
                <Text style={styles.rowSubtitle}>
                  Prefer Android 14+ window-level capture when available.
                </Text>
              </View>
              <Switch
                value={preferences.preferWindowCapture}
                onValueChange={val =>
                  onUpdatePreferences({preferWindowCapture: val})
                }
                trackColor={{
                  false: colors.borderStrong,
                  true: colors.royalBlue,
                }}
                thumbColor="#FFFFFF"
              />
            </View>
          </View>
        )}
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
  switchRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: spacing.sm,
    gap: spacing.md,
  },
  rowBorderTop: {
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
    marginTop: spacing.xs,
    paddingTop: spacing.md,
  },
  switchTextCol: {
    flex: 1,
  },
  rowTitle: {
    fontSize: 14.5,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: 2,
  },
  rowSubtitle: {
    fontSize: 12.5,
    color: colors.textSecondary,
    lineHeight: 17,
  },
  cooldownWrap: {
    marginTop: spacing.sm,
    paddingTop: spacing.sm,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  fieldLabel: {
    fontSize: 12.5,
    fontWeight: '700',
    color: colors.textSecondary,
    marginBottom: spacing.sm,
  },
  pillRow: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  pill: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: radius.pill,
    backgroundColor: colors.surfaceSubtle,
    borderWidth: 1,
    borderColor: colors.border,
  },
  pillActive: {
    backgroundColor: colors.royalBlue,
    borderColor: colors.royalBlue,
  },
  pillText: {
    fontSize: 12.5,
    fontWeight: '700',
    color: colors.textSecondary,
  },
  pillTextActive: {
    color: '#FFFFFF',
  },
  radioCard: {
    padding: spacing.md,
    borderRadius: radius.lg,
    borderWidth: 1.5,
    borderColor: colors.border,
    backgroundColor: colors.surfaceSubtle,
    marginBottom: spacing.sm,
  },
  radioCardActive: {
    borderColor: colors.royalBlue,
    backgroundColor: 'rgba(12, 45, 132, 0.05)',
  },
  radioTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 4,
  },
  radioTitle: {
    fontSize: 14.5,
    fontWeight: '800',
    color: colors.textPrimary,
  },
  radioDotOuter: {
    width: 18,
    height: 18,
    borderRadius: 9,
    borderWidth: 2,
    borderColor: colors.borderStrong,
    alignItems: 'center',
    justifyContent: 'center',
  },
  radioDotOuterActive: {
    borderColor: colors.royalBlue,
  },
  radioDotInner: {
    width: 9,
    height: 9,
    borderRadius: 4.5,
    backgroundColor: colors.royalBlue,
  },
  radioDesc: {
    fontSize: 12.5,
    color: colors.textSecondary,
    lineHeight: 18,
  },
  chipWrap: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs + 2,
  },
  pkgChip: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: 12,
    paddingVertical: 6,
    gap: 8,
  },
  pkgChipText: {
    fontSize: 12.5,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  pkgChipRemove: {
    fontSize: 12,
    color: colors.danger,
    fontWeight: '700',
  },
  quickChip: {
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  quickChipText: {
    fontSize: 12,
    fontWeight: '600',
    color: colors.royalBlue,
  },
  inputRow: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  textInput: {
    flex: 1,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: spacing.md,
    paddingVertical: 9,
    color: colors.textPrimary,
    fontSize: 12.5,
    fontFamily: typography.mono,
  },
  addBtn: {
    backgroundColor: colors.royalBlue,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.lg,
    justifyContent: 'center',
    alignItems: 'center',
  },
  addBtnText: {
    color: '#FFFFFF',
    fontSize: 13,
    fontWeight: '700',
  },
  outlineBtn: {
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
    paddingVertical: 12,
    alignItems: 'center',
  },
  outlineBtnText: {
    fontSize: 13.5,
    fontWeight: '700',
    color: colors.royalBlue,
  },
  advancedToggle: {
    alignItems: 'center',
    paddingVertical: spacing.sm,
  },
  advancedToggleText: {
    fontSize: 12.5,
    fontWeight: '700',
    color: colors.textSecondary,
  },
});
