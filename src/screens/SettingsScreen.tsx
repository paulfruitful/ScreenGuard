import React, {useEffect, useState} from 'react';
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
import {ScreenGuardNative} from '../services/ScreenGuardNative';
import {colors, radius, spacing} from '../theme/theme';
import {
  AppUpdateInfo,
  CoolOffConfig,
  DoomscrollConfig,
  DoomscrollLiveStatus,
  UserPreferences,
} from '../types/screenguard';
import {getFriendlyAppName} from '../utils/friendlyFormat';

interface SettingsScreenProps {
  preferences: UserPreferences;
  protectionLocked: boolean;
  appUpdate: AppUpdateInfo | null;
  isCheckingUpdate: boolean;
  isDownloadingUpdate: boolean;
  updateDownloadProgress: number;
  updateStatusMessage: string | null;
  onCheckForUpdate: () => void;
  onInstallUpdate: () => void;
  onUpdatePreferences: (partial: Partial<UserPreferences>) => Promise<void>;
  onToggleProtection: (enabled: boolean) => void;
  onOpenAccessibilitySettings: () => void;
  onOpenOverlaySettings: () => void;
  onNavigateToProtection: () => void;
  onNavigateToExcludedApps: () => void;
  onNavigateToDnsFilter: () => void;
  dnsFilterActive: boolean;
}

export function SettingsScreen({
  preferences,
  protectionLocked,
  appUpdate,
  isCheckingUpdate,
  isDownloadingUpdate,
  updateDownloadProgress,
  updateStatusMessage,
  onCheckForUpdate,
  onInstallUpdate,
  onUpdatePreferences,
  onToggleProtection,
  onOpenAccessibilitySettings,
  onOpenOverlaySettings,
  onNavigateToProtection,
  onNavigateToExcludedApps,
  onNavigateToDnsFilter,
  dnsFilterActive,
}: SettingsScreenProps) {
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [coolOffConfig, setCoolOffConfig] = useState<CoolOffConfig>({
    triggerThreshold: 3,
    detectionWindowMinutes: 3,
    defaultDurationMinutes: 3,
    audioCategory: 'SOFT_PIANO',
    audioMuted: false,
    audioVolume: 0.55,
    isEscalated: false,
    recentActivationCount: 0,
  });
  const [doomscrollConfig, setDoomscrollConfig] = useState<DoomscrollConfig>({
    enabled: false,
    tiktokEnabled: true,
    instagramEnabled: true,
    facebookEnabled: true,
    xEnabled: true,
    intendedDurationMinutes: 10,
    strictTimeLimitEnabled: false,
    meaningfulBreakSeconds: 60,
    gentleReminderCooldownSeconds: 300,
    strongInterventionCooldownSeconds: 600,
  });
  const [doomscrollStatus, setDoomscrollStatus] =
    useState<DoomscrollLiveStatus | null>(null);
  const [customBlockedPages, setCustomBlockedPages] = useState<string[]>([]);
  const [newBlockedInput, setNewBlockedInput] = useState('');

  useEffect(() => {
    ScreenGuardNative.getCoolOffConfig()
      .then(cfg => setCoolOffConfig(cfg))
      .catch(() => {});
    ScreenGuardNative.getDoomscrollConfig()
      .then(cfg => setDoomscrollConfig(cfg))
      .catch(() => {});
    ScreenGuardNative.getDoomscrollStatus()
      .then(st => setDoomscrollStatus(st))
      .catch(() => {});
    ScreenGuardNative.getCustomBlockedPages()
      .then(items => setCustomBlockedPages(items))
      .catch(() => {});
  }, []);

  const handleAddBlockedPage = () => {
    const trimmed = newBlockedInput.trim();
    if (trimmed.length < 2) return;
    setNewBlockedInput('');
    ScreenGuardNative.addCustomBlockedPage(trimmed)
      .then(updated => setCustomBlockedPages(updated))
      .catch(() => {});
  };

  const handleRemoveBlockedPage = (entry: string) => {
    if (protectionLocked) return;
    ScreenGuardNative.removeCustomBlockedPage(entry)
      .then(updated => setCustomBlockedPages(updated))
      .catch(() => {});
  };

  const updateCoolOff = (patch: Partial<CoolOffConfig>) => {
    setCoolOffConfig(prev => ({...prev, ...patch}));
    ScreenGuardNative.updateCoolOffConfig(patch)
      .then(updated => setCoolOffConfig(updated))
      .catch(() => {});
  };

  const updateDoomscroll = (patch: Partial<DoomscrollConfig>) => {
    setDoomscrollConfig(prev => ({...prev, ...patch}));
    ScreenGuardNative.updateDoomscrollConfig(patch)
      .then(updated => {
        setDoomscrollConfig(updated);
        return ScreenGuardNative.getDoomscrollStatus();
      })
      .then(st => setDoomscrollStatus(st))
      .catch(() => {});
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
                {protectionLocked
                  ? 'Locked by Uninstall Protection. Unlock with your PIN in the Protect tab to change.'
                  : 'Automatically hide explicit and inappropriate content across all your apps in Strict mode.'}
              </Text>
            </View>
            <Switch
              value={preferences.protectionEnabled}
              disabled={protectionLocked && preferences.protectionEnabled}
              onValueChange={val => onToggleProtection(val)}
              trackColor={{false: colors.borderStrong, true: colors.royalBlue}}
              thumbColor="#FFFFFF"
            />
          </View>
        </View>

        {/* Uninstall Protection */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Uninstall Protection</Text>
          <Text style={styles.cardSub}>
            Lock pausing, DNS stop, and quick uninstallation with a PIN and
            Android Device Admin so you stay protected against relapse.
          </Text>
          <Pressable onPress={onNavigateToProtection} style={styles.outlineBtn}>
            <Text style={styles.outlineBtnText}>
              {protectionLocked
                ? 'Manage Uninstall Protection (Locked)'
                : 'Enable Uninstall Protection'}
            </Text>
          </Pressable>
        </View>

        {/* Cool-Off Room Settings */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Cool-Off Room</Text>
          <Text style={styles.cardSub}>
            Guided psychological exercises, offline scripture meditation, and
            calming instrumentals when repeated triggers occur.
            {coolOffConfig.isEscalated ? ' (Cool-Off currently required)' : ''}
          </Text>

          <Text style={[styles.rowTitle, {marginBottom: 6}]}>
            Default Session Duration
          </Text>
          <View style={[styles.pillRow, {marginBottom: spacing.md}]}>
            {[1, 3, 5].map(min => {
              const active = coolOffConfig.defaultDurationMinutes === min;
              return (
                <Pressable
                  key={min}
                  onPress={() => updateCoolOff({defaultDurationMinutes: min})}
                  style={[styles.pill, active && styles.pillActive]}>
                  <Text style={[styles.pillText, active && styles.pillTextActive]}>
                    {min} min
                  </Text>
                </Pressable>
              );
            })}
          </View>

          <Text style={[styles.rowTitle, {marginBottom: 6}]}>
            Escalation Threshold (Triggers)
          </Text>
          <View style={[styles.pillRow, {marginBottom: spacing.md}]}>
            {[2, 3, 4, 5].map(count => {
              const active = coolOffConfig.triggerThreshold === count;
              return (
                <Pressable
                  key={count}
                  onPress={() => updateCoolOff({triggerThreshold: count})}
                  style={[styles.pill, active && styles.pillActive]}>
                  <Text style={[styles.pillText, active && styles.pillTextActive]}>
                    {count}×
                  </Text>
                </Pressable>
              );
            })}
          </View>

          <Text style={[styles.rowTitle, {marginBottom: 6}]}>
            Rolling Detection Window
          </Text>
          <View style={[styles.pillRow, {marginBottom: spacing.md}]}>
            {[2, 3, 4, 5].map(win => {
              const active = coolOffConfig.detectionWindowMinutes === win;
              return (
                <Pressable
                  key={win}
                  onPress={() => updateCoolOff({detectionWindowMinutes: win})}
                  style={[styles.pill, active && styles.pillActive]}>
                  <Text style={[styles.pillText, active && styles.pillTextActive]}>
                    {win}m
                  </Text>
                </Pressable>
              );
            })}
          </View>

          <Pressable
            onPress={() => {
              ScreenGuardNative.openCoolOffRoom(
                coolOffConfig.defaultDurationMinutes,
              ).catch(() => {});
            }}
            style={styles.outlineBtn}>
            <Text style={styles.outlineBtnText}>Open Cool-Off Room</Text>
          </Pressable>
        </View>

        {/* Optional Doomscroll Protection (Behavioural Protection Engine) */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Doomscroll Protection (Optional)</Text>
          <Text style={styles.cardSub}>
            Detects continuous short-form video scrolling across TikTok,
            Instagram Reels, Facebook Reels, and X Video — helping you pause
            with gentle reminders and Cool-Off sessions. 100% local on-device.
          </Text>

          <View style={styles.switchRow}>
            <View style={styles.switchTextCol}>
              <Text style={styles.rowTitle}>Enable Doomscroll Protection</Text>
              <Text style={styles.rowSubtitle}>
                {doomscrollConfig.enabled
                  ? 'Active on selected short-form video feeds (DMs, profiles & regular posts excluded).'
                  : 'Off by default. Turn on anytime to monitor short-form video feeds.'}
              </Text>
            </View>
            <Switch
              value={doomscrollConfig.enabled}
              onValueChange={val => updateDoomscroll({enabled: val})}
              trackColor={{false: colors.borderStrong, true: colors.royalBlue}}
              thumbColor="#FFFFFF"
            />
          </View>

          {doomscrollConfig.enabled ? (
            <View style={styles.rowBorderTop}>
              <Text style={[styles.rowTitle, {marginBottom: 6}]}>
                Intended Feed Session Time
              </Text>
              <View style={[styles.pillRow, {marginBottom: spacing.md}]}>
                {[5, 10, 15, 30].map(min => {
                  const active = doomscrollConfig.intendedDurationMinutes === min;
                  return (
                    <Pressable
                      key={min}
                      onPress={() =>
                        updateDoomscroll({intendedDurationMinutes: min})
                      }
                      style={[styles.pill, active && styles.pillActive]}>
                      <Text
                        style={[
                          styles.pillText,
                          active && styles.pillTextActive,
                        ]}>
                        {min} min
                      </Text>
                    </Pressable>
                  );
                })}
              </View>

              <Text style={[styles.rowTitle, {marginBottom: 6}]}>
                Monitored Short-Form Feeds
              </Text>
              <View
                style={[
                  styles.pillRow,
                  {flexWrap: 'wrap', marginBottom: spacing.md},
                ]}>
                {(
                  [
                    ['tiktokEnabled', 'TikTok'],
                    ['instagramEnabled', 'IG Reels'],
                    ['facebookEnabled', 'FB Reels'],
                    ['xEnabled', 'X Video'],
                  ] as const
                ).map(([key, label]) => {
                  const active = doomscrollConfig[key];
                  return (
                    <Pressable
                      key={key}
                      onPress={() => updateDoomscroll({[key]: !active})}
                      style={[styles.pill, active && styles.pillActive]}>
                      <Text
                        style={[
                          styles.pillText,
                          active && styles.pillTextActive,
                        ]}>
                        {active ? `✓ ${label}` : label}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>

              <View style={styles.switchRow}>
                <View style={styles.switchTextCol}>
                  <Text style={styles.rowTitle}>Strict Session Limit</Text>
                  <Text style={styles.rowSubtitle}>
                    Trigger a Cool-Off session automatically when your intended
                    feed time runs out.
                  </Text>
                </View>
                <Switch
                  value={doomscrollConfig.strictTimeLimitEnabled}
                  onValueChange={val =>
                    updateDoomscroll({strictTimeLimitEnabled: val})
                  }
                  trackColor={{
                    false: colors.borderStrong,
                    true: colors.royalBlue,
                  }}
                  thumbColor="#FFFFFF"
                />
              </View>

              {doomscrollStatus ? (
                <Text
                  style={[
                    styles.rowSubtitle,
                    {marginTop: spacing.xs, color: colors.royalBlue},
                  ]}>
                  Today: {doomscrollStatus.totalActiveMinutesToday}m across{' '}
                  {doomscrollStatus.totalSessionsToday} feed session
                  {doomscrollStatus.totalSessionsToday === 1 ? '' : 's'} · Risk:{' '}
                  {doomscrollStatus.riskLevel} ({doomscrollStatus.riskScore}/100)
                </Text>
              ) : null}
            </View>
          ) : null}
        </View>

        {/* Custom Blocked Porn Pages & Handles */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Blocked Pages & Accounts</Text>
          <Text style={styles.cardSub}>
            Screen Guard instantly blocks 420+ built-in adult pages whenever
            opened or resumed. Add extra @handles, creator names, or profile
            links below.
          </Text>

          <View style={styles.blockInputRow}>
            <TextInput
              value={newBlockedInput}
              onChangeText={setNewBlockedInput}
              placeholder="e.g. @handle, creator name, or x.com/..."
              placeholderTextColor={colors.textMuted}
              autoCapitalize="none"
              autoCorrect={false}
              returnKeyType="done"
              onSubmitEditing={handleAddBlockedPage}
              style={styles.blockInput}
            />
            <Pressable
              onPress={handleAddBlockedPage}
              style={styles.blockAddBtn}>
              <Text style={styles.blockAddBtnText}>Add</Text>
            </Pressable>
          </View>

          {customBlockedPages.length > 0 ? (
            <View style={styles.chipWrap}>
              {customBlockedPages.map(entry => (
                <View key={entry} style={styles.pkgChip}>
                  <Text style={styles.pkgChipText}>{entry}</Text>
                  {!protectionLocked ? (
                    <Pressable
                      onPress={() => handleRemoveBlockedPage(entry)}
                      hitSlop={8}>
                      <Text style={styles.pkgChipRemove}>✕</Text>
                    </Pressable>
                  ) : null}
                </View>
              ))}
            </View>
          ) : (
            <Text style={styles.emptyHint}>
              No custom pages added yet. Built-in blacklist is active.
            </Text>
          )}
        </View>

        {/* 2. Trusted / Excluded Apps */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Trusted Apps (Excluded)</Text>
          <Text style={styles.cardSub}>
            Screen Guard will never check or cover apps you exclude. Pick from a
            searchable list of your installed apps — no package names to type.
          </Text>

          <Pressable
            onPress={onNavigateToExcludedApps}
            style={styles.pickAppsButton}>
            <Text style={styles.pickAppsButtonText}>
              Choose apps to exclude
              {preferences.excludedPackages.length > 0
                ? ` · ${preferences.excludedPackages.length} selected`
                : ''}
            </Text>
            <Text style={styles.pickAppsArrow}>›</Text>
          </Pressable>

          {preferences.excludedPackages.length > 0 ? (
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
          ) : (
            <Text style={styles.emptyHint}>No apps excluded yet.</Text>
          )}
        </View>

        {/* DNS content filter (Milestone 5) */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>DNS Content Filter</Text>
          <Text style={styles.cardSub}>
            Block explicit sites with a family-safe DNS resolver plus your own domain list.
            Requires Android VPN approval; only DNS is routed, never your other traffic.
          </Text>
          <View style={styles.dnsStatusRow}>
            <Text style={styles.dnsStatusLabel}>Filter status</Text>
            <Text
              style={[
                styles.dnsStatusValue,
                dnsFilterActive ? styles.dnsActive : styles.dnsOff,
              ]}>
              {dnsFilterActive ? 'ACTIVE' : 'OFF'}
            </Text>
          </View>
          <Pressable onPress={onNavigateToDnsFilter} style={styles.outlineBtn}>
            <Text style={styles.outlineBtnText}>Open DNS Filter</Text>
          </Pressable>
        </View>

        {/* App Updates (GitHub Releases OTA) */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>App Updates</Text>
          <Text style={styles.cardSub}>
            Screen Guard checks GitHub Releases automatically for new versions.
            {appUpdate
              ? ` Installed: v${appUpdate.currentVersion} · Latest: v${appUpdate.latestVersion}`
              : ''}
          </Text>
          {updateStatusMessage ? (
            <Text
              style={[
                styles.cardSub,
                {color: colors.royalBlue, fontWeight: '700', marginBottom: spacing.sm},
              ]}>
              {updateStatusMessage}
            </Text>
          ) : null}
          {appUpdate?.updateAvailable ? (
            <Pressable
              disabled={isDownloadingUpdate}
              onPress={onInstallUpdate}
              style={[
                styles.outlineBtn,
                {
                  backgroundColor: colors.royalBlue,
                  borderColor: colors.royalBlue,
                  marginBottom: spacing.sm,
                },
                isDownloadingUpdate && {opacity: 0.65},
              ]}>
              <Text style={[styles.outlineBtnText, {color: '#FFFFFF'}]}>
                {isDownloadingUpdate
                  ? `Downloading Update (${updateDownloadProgress}%)...`
                  : `Download & Update to v${appUpdate.latestVersion}`}
              </Text>
            </Pressable>
          ) : null}
          <Pressable
            disabled={isCheckingUpdate || isDownloadingUpdate}
            onPress={onCheckForUpdate}
            style={[
              styles.outlineBtn,
              (isCheckingUpdate || isDownloadingUpdate) && {opacity: 0.6},
            ]}>
            <Text style={styles.outlineBtnText}>
              {isCheckingUpdate ? 'Checking GitHub Releases...' : 'Check for Updates'}
            </Text>
          </Pressable>
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

          <Pressable
            onPress={() => {
              ScreenGuardNative.openAutostartSettings().catch(() => {});
            }}
            style={[styles.outlineBtn, {marginTop: spacing.sm}]}>
            <Text style={styles.outlineBtnText}>
              Background Autostart (Prevent Phone Kill)
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
  pickAppsButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: colors.royalBlue,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.lg,
    paddingVertical: 13,
    marginBottom: spacing.md,
  },
  pickAppsButtonText: {
    flex: 1,
    color: '#FFFFFF',
    fontSize: 13.5,
    fontWeight: '800',
  },
  pickAppsArrow: {color: '#FFFFFF', fontSize: 20, fontWeight: '700'},
  blockInputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginBottom: spacing.md,
  },
  blockInput: {
    flex: 1,
    backgroundColor: colors.surfaceSubtle,
    borderWidth: 1,
    borderColor: colors.borderStrong,
    borderRadius: radius.lg,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    fontSize: 13.5,
    color: colors.textPrimary,
  },
  blockAddBtn: {
    backgroundColor: colors.royalBlue,
    borderRadius: radius.lg,
    paddingHorizontal: spacing.lg,
    paddingVertical: 11,
  },
  blockAddBtnText: {
    color: '#FFFFFF',
    fontSize: 13.5,
    fontWeight: '800',
  },
  emptyHint: {
    fontSize: 12,
    color: colors.textMuted,
    fontStyle: 'italic',
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
  dnsStatusRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.md,
  },
  dnsStatusLabel: {fontSize: 12.5, color: colors.textSecondary},
  dnsStatusValue: {fontSize: 11, fontWeight: '800', letterSpacing: 1},
  dnsActive: {color: colors.success},
  dnsOff: {color: colors.textMuted},
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
