import React, {useState} from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {ShieldCheckIcon} from '../components/AppIcons';
import {colors, radius, spacing} from '../theme/theme';
import {
  AccountabilityConfig,
  AccountabilityStatus,
  EnableAccountabilityResult,
} from '../types/screenguard';

interface Props {
  status: AccountabilityStatus;
  onEnable: (
    config: AccountabilityConfig,
  ) => Promise<EnableAccountabilityResult | null>;
  onConfirmDeactivation: (
    secret: string,
  ) => Promise<AccountabilityStatus | null>;
  onActivateAdmin: () => Promise<boolean>;
  onOpenAdminSettings: () => void;
  onDeactivateAdmin: () => Promise<boolean>;
  onRefresh: () => void;
}

function GuardItem({active, label}: {active: boolean; label: string}) {
  return (
    <View style={styles.guardRow}>
      <View
        style={[
          styles.guardDot,
          active ? styles.guardDotOn : styles.guardDotOff,
        ]}>
        <Text style={styles.guardDotText}>{active ? '✓' : '•'}</Text>
      </View>
      <Text style={styles.guardLabel}>{label}</Text>
    </View>
  );
}

export function UninstallProtectionScreen({
  status,
  onEnable,
  onConfirmDeactivation,
  onActivateAdmin,
  onOpenAdminSettings,
  onDeactivateAdmin,
  onRefresh,
}: Props) {
  const [pinInput, setPinInput] = useState('');
  const [pinConfirm, setPinConfirm] = useState('');
  const [unlockInput, setUnlockInput] = useState('');
  const [savedCode, setSavedCode] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const isLocked = status.active;
  const isAdminActive = status.deviceAdminActive;

  const runAction = async <T,>(action: () => Promise<T>): Promise<T | null> => {
    setBusy(true);
    setFormError(null);
    try {
      return await action();
    } catch (err: unknown) {
      setFormError(err instanceof Error ? err.message : 'Action failed.');
      return null;
    } finally {
      setBusy(false);
    }
  };

  const handleEnableLock = async () => {
    const trimmed = pinInput.trim();
    if (trimmed.length < 4 || !/^\d+$/.test(trimmed)) {
      setFormError('Enter a 4–8 digit Lock PIN.');
      return;
    }
    if (trimmed !== pinConfirm.trim()) {
      setFormError('PIN entries do not match.');
      return;
    }

    const result = await runAction(() =>
      onEnable({
        durationType: 'CUSTOM',
        customDays: 365,
        pin: trimmed,
        coolingOffMinutes: 0,
      }),
    );

    if (result) {
      setSavedCode(result.recoveryCode);
      setPinInput('');
      setPinConfirm('');
      onRefresh();
      if (!isAdminActive) {
        await onActivateAdmin();
      }
    }
  };

  const handleUnlock = async () => {
    const trimmed = unlockInput.trim();
    if (!trimmed) {
      setFormError('Enter your Lock PIN or recovery code.');
      return;
    }
    const result = await runAction(() => onConfirmDeactivation(trimmed));
    if (result) {
      setUnlockInput('');
      setSavedCode(null);
      onRefresh();
    }
  };

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled"
      showsVerticalScrollIndicator={false}>
      <View style={styles.royalHeader}>
        <View style={styles.headerRow}>
          <ShieldCheckIcon size={26} color="#FFFFFF" />
          <View style={styles.headerTextWrap}>
            <Text style={styles.headerTitle}>Uninstall Protection</Text>
            <Text style={styles.headerSubtitle}>
              Lock pausing, DNS stop, and uninstall against relapse
            </Text>
          </View>
        </View>
      </View>

      <View style={styles.body}>
        {/* Main Protection Lock Card */}
        <View style={styles.card}>
          <View style={styles.statusHeader}>
            <Text style={styles.cardTitle}>Protection Lock</Text>
            <View
              style={[
                styles.statusBadge,
                isLocked ? styles.badgeLocked : styles.badgeUnlocked,
              ]}>
              <Text
                style={[
                  styles.statusBadgeText,
                  isLocked ? styles.badgeTextLocked : styles.badgeTextUnlocked,
                ]}>
                {isLocked ? 'LOCKED' : 'OFF'}
              </Text>
            </View>
          </View>

          <GuardItem
            active={isLocked}
            label="Visual protection cannot be paused without PIN"
          />
          <GuardItem
            active={isLocked}
            label="DNS content filter cannot be stopped without PIN"
          />
          <GuardItem
            active={isAdminActive}
            label="Android Device Admin blocks quick uninstall"
          />

          {!isLocked ? (
            <View style={styles.formSection}>
              <Text style={styles.tipText}>
                Tip: For the strongest relapse protection, let a trusted friend
                or accountability partner choose this PIN for you.
              </Text>

              <Text style={styles.fieldLabel}>Lock PIN (4–8 digits)</Text>
              <TextInput
                value={pinInput}
                onChangeText={setPinInput}
                keyboardType="number-pad"
                maxLength={8}
                secureTextEntry
                placeholder="Enter Lock PIN"
                placeholderTextColor={colors.textMuted}
                style={styles.textInput}
              />

              <Text style={styles.fieldLabel}>Confirm Lock PIN</Text>
              <TextInput
                value={pinConfirm}
                onChangeText={setPinConfirm}
                keyboardType="number-pad"
                maxLength={8}
                secureTextEntry
                placeholder="Re-enter Lock PIN"
                placeholderTextColor={colors.textMuted}
                style={styles.textInput}
              />

              {formError ? (
                <Text style={styles.errorText}>{formError}</Text>
              ) : null}

              <Pressable
                disabled={busy}
                onPress={handleEnableLock}
                style={[styles.primaryButton, busy && styles.disabled]}>
                <Text style={styles.primaryButtonText}>
                  Enable Uninstall Protection
                </Text>
              </Pressable>
            </View>
          ) : (
            <View style={styles.formSection}>
              {savedCode ? (
                <View style={styles.recoveryBox}>
                  <Text style={styles.recoveryTitle}>
                    Backup Recovery Code (shown once)
                  </Text>
                  <Text style={styles.recoveryCode}>{savedCode}</Text>
                  <Pressable
                    onPress={() => setSavedCode(null)}
                    style={styles.secondaryButton}>
                    <Text style={styles.secondaryButtonText}>
                      I saved this code
                    </Text>
                  </Pressable>
                </View>
              ) : null}

              <Text style={styles.fieldLabel}>
                Enter Lock PIN to unlock protection
              </Text>
              <TextInput
                value={unlockInput}
                onChangeText={setUnlockInput}
                secureTextEntry
                placeholder="Lock PIN or recovery code"
                placeholderTextColor={colors.textMuted}
                style={styles.textInput}
              />

              {formError ? (
                <Text style={styles.errorText}>{formError}</Text>
              ) : null}

              <Pressable
                disabled={busy}
                onPress={handleUnlock}
                style={[styles.unlockButton, busy && styles.disabled]}>
                <Text style={styles.unlockButtonText}>Unlock Protection</Text>
              </Pressable>
            </View>
          )}
        </View>

        {/* Android Device Admin Card */}
        <View style={styles.card}>
          <View style={styles.statusHeader}>
            <Text style={styles.cardTitle}>Android Uninstall Guard</Text>
            <View
              style={[
                styles.statusBadge,
                isAdminActive ? styles.badgeLocked : styles.badgeUnlocked,
              ]}>
              <Text
                style={[
                  styles.statusBadgeText,
                  isAdminActive
                    ? styles.badgeTextLocked
                    : styles.badgeTextUnlocked,
                ]}>
                {isAdminActive ? 'ACTIVE' : 'NOT ENABLED'}
              </Text>
            </View>
          </View>

          <Text style={styles.bodyText}>
            {isAdminActive
              ? 'Device Administrator is active. Android blocks direct one-tap uninstallation of Screen Guard from the home screen, Play Store, and App Info.'
              : 'Activate Android Device Administrator so Screen Guard cannot be uninstalled with one tap during a moment of temptation.'}
          </Text>

          {!isAdminActive ? (
            <Pressable
              disabled={busy}
              onPress={() => void runAction(() => onActivateAdmin())}
              style={[styles.primaryButton, busy && styles.disabled]}>
              <Text style={styles.primaryButtonText}>
                Enable Device Admin Guard
              </Text>
            </Pressable>
          ) : !isLocked ? (
            <View style={styles.rowButtons}>
              <Pressable
                disabled={busy}
                onPress={() => void runAction(() => onDeactivateAdmin())}
                style={[styles.secondaryButton, {flex: 1}]}>
                <Text style={styles.secondaryButtonText}>
                  Remove Device Admin
                </Text>
              </Pressable>
              <Pressable
                onPress={onOpenAdminSettings}
                style={[styles.secondaryButton, {flex: 1}]}>
                <Text style={styles.secondaryButtonText}>Admin Settings</Text>
              </Pressable>
            </View>
          ) : (
            <Text style={styles.lockedHint}>
              Unlock protection with your PIN above before managing Device
              Admin.
            </Text>
          )}
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
    paddingBottom: spacing.xxl + 24,
  },
  royalHeader: {
    backgroundColor: colors.royalBlue,
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.lg,
    paddingBottom: spacing.xl,
    borderBottomLeftRadius: radius.xl,
    borderBottomRightRadius: radius.xl,
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
  },
  headerTextWrap: {
    flex: 1,
  },
  headerTitle: {
    fontSize: 22,
    fontWeight: '800',
    color: '#FFFFFF',
  },
  headerSubtitle: {
    fontSize: 13,
    color: 'rgba(255,255,255,0.82)',
    marginTop: 2,
  },
  body: {
    padding: spacing.lg,
    gap: spacing.md,
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.xl,
    padding: spacing.lg,
    elevation: 2,
  },
  statusHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.md,
  },
  cardTitle: {
    fontSize: 17,
    fontWeight: '800',
    color: colors.textPrimary,
  },
  statusBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: radius.pill,
  },
  badgeLocked: {
    backgroundColor: colors.successSoft,
  },
  badgeUnlocked: {
    backgroundColor: colors.warningSoft,
  },
  statusBadgeText: {
    fontSize: 11.5,
    fontWeight: '800',
  },
  badgeTextLocked: {
    color: colors.success,
  },
  badgeTextUnlocked: {
    color: '#92400E',
  },
  guardRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginBottom: 8,
  },
  guardDot: {
    width: 22,
    height: 22,
    borderRadius: 11,
    alignItems: 'center',
    justifyContent: 'center',
  },
  guardDotOn: {
    backgroundColor: colors.success,
  },
  guardDotOff: {
    backgroundColor: colors.borderStrong,
  },
  guardDotText: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '800',
  },
  guardLabel: {
    flex: 1,
    fontSize: 13.5,
    color: colors.textPrimary,
    fontWeight: '600',
  },
  formSection: {
    marginTop: spacing.md,
    paddingTop: spacing.md,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  tipText: {
    fontSize: 12.5,
    lineHeight: 18,
    color: colors.textSecondary,
    backgroundColor: colors.surfaceSubtle,
    padding: spacing.sm + 2,
    borderRadius: radius.md,
    marginBottom: spacing.md,
  },
  fieldLabel: {
    fontSize: 13,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: 6,
    marginTop: spacing.xs,
  },
  textInput: {
    borderWidth: 1,
    borderColor: colors.borderStrong,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: 11,
    fontSize: 15,
    color: colors.textPrimary,
    backgroundColor: colors.surface,
    marginBottom: spacing.sm,
  },
  bodyText: {
    fontSize: 13.5,
    lineHeight: 20,
    color: colors.textSecondary,
    marginBottom: spacing.md,
  },
  lockedHint: {
    fontSize: 12.5,
    color: colors.textMuted,
    fontWeight: '600',
  },
  errorText: {
    fontSize: 12.5,
    color: colors.danger,
    fontWeight: '700',
    marginBottom: spacing.sm,
  },
  primaryButton: {
    backgroundColor: colors.royalBlue,
    borderRadius: radius.pill,
    paddingVertical: 13,
    alignItems: 'center',
    marginTop: spacing.xs,
  },
  primaryButtonText: {
    color: '#FFFFFF',
    fontSize: 14.5,
    fontWeight: '800',
  },
  unlockButton: {
    backgroundColor: colors.surfaceSubtle,
    borderWidth: 1,
    borderColor: colors.borderStrong,
    borderRadius: radius.pill,
    paddingVertical: 12,
    alignItems: 'center',
    marginTop: spacing.xs,
  },
  unlockButtonText: {
    color: colors.textPrimary,
    fontSize: 14,
    fontWeight: '800',
  },
  secondaryButton: {
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.pill,
    paddingVertical: 11,
    paddingHorizontal: spacing.md,
    alignItems: 'center',
  },
  secondaryButtonText: {
    color: colors.royalBlue,
    fontSize: 13,
    fontWeight: '700',
  },
  rowButtons: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  recoveryBox: {
    backgroundColor: colors.warningSoft,
    borderRadius: radius.md,
    padding: spacing.md,
    marginBottom: spacing.md,
    gap: spacing.xs,
  },
  recoveryTitle: {
    fontSize: 12.5,
    fontWeight: '800',
    color: '#92400E',
  },
  recoveryCode: {
    fontSize: 18,
    fontWeight: '900',
    letterSpacing: 1.5,
    color: colors.textPrimary,
    marginVertical: 4,
  },
  disabled: {
    opacity: 0.55,
  },
});
