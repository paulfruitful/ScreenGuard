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
import {colors, radius, spacing, typography} from '../theme/theme';
import {
  InterventionBehavior,
  MockClassifierProfile,
  UserPreferences,
} from '../types/screenguard';

interface SettingsScreenProps {
  preferences: UserPreferences;
  onUpdatePreferences: (partial: Partial<UserPreferences>) => Promise<void>;
  onToggleProtection: (enabled: boolean) => void;
  onOpenAccessibilitySettings: () => void;
}

const SAMPLING_OPTIONS = [
  {label: '1.5s (Fast)', value: 1500},
  {label: '2.5s (Balanced)', value: 2500},
  {label: '4.0s (Battery Saver)', value: 4000},
  {label: '6.0s (Low Frequency)', value: 6000},
];

const THRESHOLD_OPTIONS = [
  {label: '60%', value: 0.6},
  {label: '75%', value: 0.75},
  {label: '85%', value: 0.85},
  {label: '92%', value: 0.92},
];

const CONFIRMATION_OPTIONS = [
  {label: '2 Frames', value: 2},
  {label: '3 Frames', value: 3},
  {label: '5 Frames', value: 5},
];

const INTERVENTION_OPTIONS: {
  label: string;
  value: InterventionBehavior;
  desc: string;
}[] = [
  {
    label: 'Log Only (Silent Observation)',
    value: 'LOG_ONLY',
    desc: 'Records confirmed multi-frame detection metadata in Activity Log.',
  },
  {
    label: 'Status Notification Alert',
    value: 'STATUS_NOTIFICATION',
    desc: 'Updates local monitoring notification when confirmed threshold is met.',
  },
  {
    label: 'Shield Warning Banner (Future NSFW Milestone)',
    value: 'WARNING_BANNER_FUTURE',
    desc: 'Reserved for Milestone 2 after real LiteRT/ONNX NSFW model integration.',
  },
];

const MOCK_PROFILES: {
  label: string;
  value: MockClassifierProfile;
  desc: string;
}[] = [
  {
    label: 'Deterministic Safe',
    value: 'DETERMINISTIC_SAFE',
    desc: 'Computes real pixel statistics on downsampled frame; emits SAFE scores.',
  },
  {
    label: 'Synthetic Variation',
    value: 'SYNTHETIC_VARIATION',
    desc: 'Varies scores deterministically from image luminance/warmth for pipeline testing.',
  },
  {
    label: 'Multi-Frame Confirmation Stress Test',
    value: 'STRESS_TEST_SUSPICIOUS',
    desc: 'Simulates elevated mock scores to verify N-frame consecutive confirmation gating.',
  },
];

const QUICK_EXCLUDE_CANDIDATES = [
  {pkg: 'com.android.settings', name: 'Android Settings'},
  {pkg: 'com.google.android.apps.messaging', name: 'Google Messages'},
  {pkg: 'com.whatsapp', name: 'WhatsApp'},
  {pkg: 'com.samsung.android.messaging', name: 'Samsung Messages'},
  {pkg: 'com.google.android.gm', name: 'Gmail'},
];

export function SettingsScreen({
  preferences,
  onUpdatePreferences,
  onToggleProtection,
  onOpenAccessibilitySettings,
}: SettingsScreenProps) {
  const [customPackage, setCustomPackage] = useState('');

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
      {/* Protection Control */}
      <View style={styles.sectionCard}>
        <Text style={styles.sectionTitle}>Protection & Capture Engine</Text>

        <View style={styles.switchRow}>
          <View style={styles.switchTextCol}>
            <Text style={styles.rowTitle}>Screen Guard Protection</Text>
            <Text style={styles.rowSubtitle}>
              Observe active window transitions and run on-device screenshot
              pipeline.
            </Text>
          </View>
          <Switch
            value={preferences.protectionEnabled}
            onValueChange={val => onToggleProtection(val)}
            trackColor={{false: colors.borderStrong, true: colors.accent}}
            thumbColor="#FFFFFF"
          />
        </View>

        <View style={[styles.switchRow, styles.rowBorderTop]}>
          <View style={styles.switchTextCol}>
            <Text style={styles.rowTitle}>
              Prefer Per-Window Capture (Android 14+)
            </Text>
            <Text style={styles.rowSubtitle}>
              Use AccessibilityService.takeScreenshotOfWindow(windowId) when
              available before falling back to display capture.
            </Text>
          </View>
          <Switch
            value={preferences.preferWindowCapture}
            onValueChange={val =>
              onUpdatePreferences({preferWindowCapture: val})
            }
            trackColor={{false: colors.borderStrong, true: colors.accent}}
            thumbColor="#FFFFFF"
          />
        </View>

        <Pressable
          onPress={onOpenAccessibilitySettings}
          style={styles.outlineButton}>
          <Text style={styles.outlineButtonText}>
            Manage or Revoke Android Accessibility Access
          </Text>
        </Pressable>
      </View>

      {/* Sampling Frequency & Cooldown */}
      <View style={styles.sectionCard}>
        <Text style={styles.sectionTitle}>Screenshot Sampling Policy</Text>
        <Text style={styles.helperText}>
          Screen Guard uses event-driven sampling with debouncing rather than
          continuous polling to preserve battery and respect Android’s 1-second
          accessibility screenshot interval limit.
        </Text>

        <Text style={styles.fieldLabel}>Minimum Cooldown Between Captures</Text>
        <View style={styles.pillGrid}>
          {SAMPLING_OPTIONS.map(opt => {
            const active = preferences.samplingIntervalMs === opt.value;
            return (
              <Pressable
                key={opt.value}
                onPress={() =>
                  onUpdatePreferences({samplingIntervalMs: opt.value})
                }
                style={[styles.choicePill, active && styles.choicePillActive]}>
                <Text
                  style={[
                    styles.choicePillText,
                    active && styles.choicePillTextActive,
                  ]}>
                  {opt.label}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </View>

      {/* Detection Threshold & False-Positive Reduction */}
      <View style={styles.sectionCard}>
        <Text style={styles.sectionTitle}>
          Detection Confidence & Multi-Frame Confirmation
        </Text>
        <Text style={styles.helperText}>
          A single suspicious frame never triggers an automatic intervention.
          Consecutive detections across sampled frames are required.
        </Text>

        <Text style={styles.fieldLabel}>Confidence Threshold</Text>
        <View style={styles.pillGrid}>
          {THRESHOLD_OPTIONS.map(opt => {
            const active =
              Math.abs(preferences.confidenceThreshold - opt.value) < 0.01;
            return (
              <Pressable
                key={opt.value}
                onPress={() =>
                  onUpdatePreferences({confidenceThreshold: opt.value})
                }
                style={[styles.choicePill, active && styles.choicePillActive]}>
                <Text
                  style={[
                    styles.choicePillText,
                    active && styles.choicePillTextActive,
                  ]}>
                  {opt.label}
                </Text>
              </Pressable>
            );
          })}
        </View>

        <Text style={[styles.fieldLabel, {marginTop: spacing.md}]}>
          Consecutive Frames Required for Confirmation
        </Text>
        <View style={styles.pillGrid}>
          {CONFIRMATION_OPTIONS.map(opt => {
            const active = preferences.confirmationFramesRequired === opt.value;
            return (
              <Pressable
                key={opt.value}
                onPress={() =>
                  onUpdatePreferences({confirmationFramesRequired: opt.value})
                }
                style={[styles.choicePill, active && styles.choicePillActive]}>
                <Text
                  style={[
                    styles.choicePillText,
                    active && styles.choicePillTextActive,
                  ]}>
                  {opt.label}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </View>

      {/* Intervention Behavior & Mock Classifier Profile */}
      <View style={styles.sectionCard}>
        <Text style={styles.sectionTitle}>
          Intervention Behavior (Future NSFW Model Milestone)
        </Text>
        {INTERVENTION_OPTIONS.map(opt => {
          const active = preferences.interventionBehavior === opt.value;
          return (
            <Pressable
              key={opt.value}
              onPress={() =>
                onUpdatePreferences({interventionBehavior: opt.value})
              }
              style={[styles.radioCard, active && styles.radioCardActive]}>
              <Text style={styles.radioTitle}>{opt.label}</Text>
              <Text style={styles.radioDesc}>{opt.desc}</Text>
            </Pressable>
          );
        })}

        <Text
          style={[
            styles.sectionTitle,
            {marginTop: spacing.lg, marginBottom: spacing.xs},
          ]}>
          Mock Classifier Simulation Profile (Dev / Testing)
        </Text>
        {MOCK_PROFILES.map(opt => {
          const active = preferences.mockClassifierProfile === opt.value;
          return (
            <Pressable
              key={opt.value}
              onPress={() =>
                onUpdatePreferences({mockClassifierProfile: opt.value})
              }
              style={[styles.radioCard, active && styles.radioCardActive]}>
              <Text style={styles.radioTitle}>{opt.label}</Text>
              <Text style={styles.radioDesc}>{opt.desc}</Text>
            </Pressable>
          );
        })}
      </View>

      {/* Excluded Applications */}
      <View style={styles.sectionCard}>
        <Text style={styles.sectionTitle}>Excluded Applications</Text>
        <Text style={styles.helperText}>
          Screen Guard will never request screenshots when an excluded package
          is in the foreground.
        </Text>

        <View style={styles.inputRow}>
          <TextInput
            value={customPackage}
            onChangeText={setCustomPackage}
            placeholder="e.g. com.whatsapp"
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

        <View style={styles.chipWrap}>
          {preferences.excludedPackages.map(pkg => (
            <View key={pkg} style={styles.pkgChip}>
              <Text style={styles.pkgChipText}>{pkg}</Text>
              <Pressable
                onPress={() => removeExcludedPackage(pkg)}
                hitSlop={8}>
                <Text style={styles.pkgChipRemove}>✕</Text>
              </Pressable>
            </View>
          ))}
        </View>

        <Text style={[styles.fieldLabel, {marginTop: spacing.md}]}>
          Quick-Exclude Common Personal Apps
        </Text>
        <View style={styles.chipWrap}>
          {QUICK_EXCLUDE_CANDIDATES.map(item => {
            const already = preferences.excludedPackages.includes(item.pkg);
            return (
              <Pressable
                key={item.pkg}
                disabled={already}
                onPress={() => addExcludedPackage(item.pkg)}
                style={[
                  styles.quickAddChip,
                  already && styles.quickAddChipDisabled,
                ]}>
                <Text style={styles.quickAddText}>
                  {already ? `✓ ${item.name}` : `+ ${item.name}`}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </View>

      {/* Transparency: What Screen Guard Can and Cannot Inspect */}
      <View style={styles.sectionCard}>
        <Text style={styles.sectionTitle}>
          Transparency: What Screen Guard Can & Cannot Inspect
        </Text>

        <View style={styles.infoBlock}>
          <Text style={styles.infoHeading}>What Screen Guard Can Inspect</Text>
          <Text style={styles.infoBody}>
            • Standard foreground application surfaces on Android 11+ via
            AccessibilityService.takeScreenshot() and Android 14+ via
            AccessibilityService.takeScreenshotOfWindow().{'\n'}• Browser pages
            (normal tabs), social media feeds, and non-DRM video surfaces after
            debounced window/content/scroll settles.
          </Text>
        </View>

        <View style={styles.infoBlock}>
          <Text style={styles.infoHeading}>
            What Screen Guard Cannot & Will Not Inspect
          </Text>
          <Text style={styles.infoBody}>
            • Protected Windows (FLAG_SECURE): Banking apps, password managers,
            Chrome Incognito tabs, and private messaging screens that set
            FLAG_SECURE return ERROR_TAKE_SCREENSHOT_SECURE_WINDOW. Screen Guard
            never attempts to bypass FLAG_SECURE.{'\n'}• DRM-Protected Video:
            Widevine L1 hardware-protected video overlays cannot be read by
            accessibility screenshot APIs.{'\n'}• Passwords & Typed Text: Screen
            Guard does not subscribe to text-input events, ignores password
            nodes, and never reads clipboard or private messages.{'\n'}• Zero
            Disk or Cloud Persistence: Screenshots are downsampled in RAM,
            HardwareBuffers are closed immediately, and images are never saved
            to storage or uploaded.
          </Text>
        </View>
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
  sectionCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.lg,
    marginBottom: spacing.md,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: colors.textSecondary,
    textTransform: 'uppercase',
    letterSpacing: 0.6,
    marginBottom: spacing.sm,
  },
  helperText: {
    fontSize: 12.5,
    color: colors.textSecondary,
    lineHeight: 18,
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
    fontWeight: '600',
    color: colors.textPrimary,
    marginBottom: 2,
  },
  rowSubtitle: {
    fontSize: 12,
    color: colors.textSecondary,
    lineHeight: 17,
  },
  outlineButton: {
    marginTop: spacing.md,
    paddingVertical: 11,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.borderStrong,
    backgroundColor: colors.surfaceSubtle,
    alignItems: 'center',
  },
  outlineButtonText: {
    fontSize: 12.5,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  fieldLabel: {
    fontSize: 12,
    fontWeight: '600',
    color: colors.textSecondary,
    marginBottom: spacing.sm,
  },
  pillGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  choicePill: {
    paddingHorizontal: spacing.md,
    paddingVertical: 8,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surfaceSubtle,
  },
  choicePillActive: {
    borderColor: colors.accentBorder,
    backgroundColor: colors.accentSoft,
  },
  choicePillText: {
    fontSize: 12.5,
    fontWeight: '600',
    color: colors.textSecondary,
  },
  choicePillTextActive: {
    color: '#60A5FA',
  },
  radioCard: {
    padding: spacing.md,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surfaceSubtle,
    marginBottom: spacing.sm,
  },
  radioCardActive: {
    borderColor: colors.accentBorder,
    backgroundColor: colors.accentSoft,
  },
  radioTitle: {
    fontSize: 13.5,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: 2,
  },
  radioDesc: {
    fontSize: 12,
    color: colors.textSecondary,
    lineHeight: 17,
  },
  inputRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginBottom: spacing.md,
  },
  textInput: {
    flex: 1,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.borderStrong,
    paddingHorizontal: spacing.md,
    paddingVertical: 9,
    color: colors.textPrimary,
    fontSize: 13,
    fontFamily: typography.mono,
  },
  addBtn: {
    backgroundColor: colors.accent,
    borderRadius: radius.md,
    paddingHorizontal: spacing.lg,
    justifyContent: 'center',
    alignItems: 'center',
  },
  addBtnText: {
    color: '#FFFFFF',
    fontSize: 13,
    fontWeight: '700',
  },
  chipWrap: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs + 2,
  },
  pkgChip: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.surfaceElevated,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.borderStrong,
    paddingHorizontal: 9,
    paddingVertical: 5,
    gap: 8,
  },
  pkgChipText: {
    fontSize: 11.5,
    color: colors.textPrimary,
    fontFamily: typography.mono,
  },
  pkgChipRemove: {
    fontSize: 12,
    color: colors.danger,
    fontWeight: '700',
  },
  quickAddChip: {
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: 10,
    paddingVertical: 6,
  },
  quickAddChipDisabled: {
    opacity: 0.45,
  },
  quickAddText: {
    fontSize: 11.5,
    color: colors.textSecondary,
    fontWeight: '600',
  },
  infoBlock: {
    marginBottom: spacing.md,
  },
  infoHeading: {
    fontSize: 13.5,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: 4,
  },
  infoBody: {
    fontSize: 12.5,
    color: colors.textSecondary,
    lineHeight: 19,
  },
});
