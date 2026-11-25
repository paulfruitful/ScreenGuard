import React, {useEffect, useRef, useState} from 'react';
import {
  AppState,
  AppStateStatus,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  SafeAreaProvider,
  SafeAreaView,
} from 'react-native-safe-area-context';
import {
  BarsIcon,
  CameraBadgeIcon,
  HomeIcon,
  SettingsGearIcon,
  ShieldCheckIcon,
} from '../components/AppIcons';
import {useScreenGuard} from '../hooks/useScreenGuard';
import {ActivityScreen} from '../screens/ActivityScreen';
import {DashboardScreen} from '../screens/DashboardScreen';
import {DnsFilterScreen} from '../screens/DnsFilterScreen';
import {ExcludedAppsScreen} from '../screens/ExcludedAppsScreen';
import {SettingsScreen} from '../screens/SettingsScreen';
import {TestingScreen} from '../screens/TestingScreen';
import {UninstallProtectionScreen} from '../screens/UninstallProtectionScreen';
import {colors, radius, spacing, typography} from '../theme/theme';

export type TabKey = 'HOME' | 'ACTIVITY' | 'CAPTURE' | 'PROTECT' | 'SETTINGS';

const TABS: {key: TabKey; label: string}[] = [
  {key: 'HOME', label: 'Home'},
  {key: 'ACTIVITY', label: 'Activity'},
  {key: 'CAPTURE', label: 'Capture'},
  {key: 'PROTECT', label: 'Protect'},
  {key: 'SETTINGS', label: 'Settings'},
];

export function MainNavigator() {
  const [activeTab, setActiveTab] = useState<TabKey>('HOME');
  const [showExcludedApps, setShowExcludedApps] = useState(false);
  const [showDnsFilter, setShowDnsFilter] = useState(false);
  const prevReturnClicksRef = useRef<number>(0);
  const {
    status,
    accountability,
    dnsFilterStatus,
    preferences,
    activityLogs,
    compatibilityRecords,
    debugPreview,
    debounceTestResult,
    isCapturingTest,
    lastError,
    refreshAll,
    openAccessibilitySettings,
    openOverlaySettings,
    triggerOverlayTest,
    dismissActiveOverlay,
    toggleProtection,
    updatePreferences,
    clearActivityHistory,
    runManualCaptureTest,
    purgeInMemoryPreview,
    runDebounceTest,
    runDisconnectionTest,
    getInstalledApps,
    recordCompatibilityResult,
    enableAccountability,
    requestAccountabilityDeactivation,
    cancelAccountabilityDeactivation,
    confirmAccountabilityDeactivation,
    setAccountabilityPin,
    activateDeviceAdmin,
    openDeviceAdminSettings,
    deactivateDeviceAdmin,
    lockDeviceViaAdmin,
    requestDnsFilterPermission,
    startDnsFilter,
    stopDnsFilter,
    updateDnsFilterConfig,
  } = useScreenGuard();

  useEffect(() => {
    const clicks = status.protectionSummary?.totalReturnToSafetyClicks || 0;
    if (clicks > prevReturnClicksRef.current) {
      prevReturnClicksRef.current = clicks;
      setActiveTab('HOME');
    }
  }, [status.protectionSummary?.totalReturnToSafetyClicks]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', (next: AppStateStatus) => {
      if (next === 'active') {
        const clicks = status.protectionSummary?.totalReturnToSafetyClicks || 0;
        if (clicks > prevReturnClicksRef.current) {
          prevReturnClicksRef.current = clicks;
          setActiveTab('HOME');
        }
      }
    });
    return () => sub.remove();
  }, [status.protectionSummary?.totalReturnToSafetyClicks]);

  const renderTabIcon = (key: TabKey, isActive: boolean) => {
    const color = isActive ? colors.accent : '#64748B';
    switch (key) {
      case 'HOME':
        return <HomeIcon color={color} size={20} />;
      case 'ACTIVITY':
        return <BarsIcon color={color} size={20} />;
      case 'CAPTURE':
        return <CameraBadgeIcon color={color} size={20} />;
      case 'PROTECT':
        return <ShieldCheckIcon color={color} size={20} />;
      case 'SETTINGS':
        return <SettingsGearIcon color={color} size={21} />;
    }
  };

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.safeArea} edges={['top', 'bottom']}>
        <StatusBar barStyle="light-content" />

        {lastError ? (
          <View style={styles.errorBanner}>
            <Text style={styles.errorText} numberOfLines={2}>
              {lastError}
            </Text>
          </View>
        ) : null}

        {/* Active Screen Viewport */}
        <View
          style={[
            styles.viewport,
            activeTab === 'HOME' && {backgroundColor: colors.royalBlue},
          ]}>
          {showExcludedApps && (
            <ExcludedAppsScreen
              excludedPackages={preferences.excludedPackages}
              onLoadApps={getInstalledApps}
              onUpdateExcludedPackages={packages =>
                updatePreferences({excludedPackages: packages})
              }
              onClose={() => setShowExcludedApps(false)}
            />
          )}

          {showDnsFilter && (
            <DnsFilterScreen
              status={dnsFilterStatus}
              protectionLocked={accountability.active}
              onRequestPermission={requestDnsFilterPermission}
              onStart={startDnsFilter}
              onStop={stopDnsFilter}
              onUpdateConfig={updateDnsFilterConfig}
              onClose={() => setShowDnsFilter(false)}
            />
          )}

          {!showExcludedApps && !showDnsFilter && activeTab === 'HOME' && (
            <DashboardScreen
              status={status}
              accountability={accountability}
              dnsFilterStatus={dnsFilterStatus}
              preferences={preferences}
              activityLogs={activityLogs}
              onOpenAccessibilitySettings={openAccessibilitySettings}
              onToggleProtection={toggleProtection}
              onEnableDnsFilter={async () => {
                if (!dnsFilterStatus.prepared) {
                  await requestDnsFilterPermission();
                } else if (!dnsFilterStatus.active) {
                  await startDnsFilter();
                } else {
                  setShowExcludedApps(false);
                  setShowDnsFilter(true);
                }
              }}
              onNavigateToActivity={() => setActiveTab('ACTIVITY')}
              onNavigateToSettings={() => setActiveTab('SETTINGS')}
              onNavigateToProtection={() => setActiveTab('PROTECT')}
              onRefresh={refreshAll}
            />
          )}

          {!showExcludedApps && !showDnsFilter && activeTab === 'ACTIVITY' && (
            <ActivityScreen
              logs={activityLogs}
              onClearLogs={clearActivityHistory}
              onRefresh={refreshAll}
            />
          )}

          {!showExcludedApps && !showDnsFilter && activeTab === 'CAPTURE' && (
            <TestingScreen
              status={status}
              compatibilityRecords={compatibilityRecords}
              debugPreview={debugPreview}
              debounceTestResult={debounceTestResult}
              isCapturingTest={isCapturingTest}
              onOpenAccessibilitySettings={openAccessibilitySettings}
              onOpenOverlaySettings={openOverlaySettings}
              onTriggerOverlayTest={triggerOverlayTest}
              onDismissOverlay={dismissActiveOverlay}
              onRunManualCapture={runManualCaptureTest}
              onPurgeInMemoryPreview={purgeInMemoryPreview}
              onRunDebounceTest={runDebounceTest}
              onRunDisconnectionTest={runDisconnectionTest}
              onUpdateCompatibilityRecord={recordCompatibilityResult}
              onRefresh={refreshAll}
            />
          )}

          {!showExcludedApps && !showDnsFilter && activeTab === 'PROTECT' && (
            <UninstallProtectionScreen
              status={accountability}
              onEnable={enableAccountability}
              onConfirmDeactivation={confirmAccountabilityDeactivation}
              onActivateAdmin={activateDeviceAdmin}
              onOpenAdminSettings={openDeviceAdminSettings}
              onDeactivateAdmin={deactivateDeviceAdmin}
              onRefresh={refreshAll}
            />
          )}

          {!showExcludedApps && !showDnsFilter && activeTab === 'SETTINGS' && (
            <SettingsScreen
              preferences={preferences}
              protectionLocked={accountability.active}
              onUpdatePreferences={updatePreferences}
              onToggleProtection={toggleProtection}
              onOpenAccessibilitySettings={openAccessibilitySettings}
              onOpenOverlaySettings={openOverlaySettings}
              onNavigateToProtection={() => setActiveTab('PROTECT')}
              onNavigateToExcludedApps={() => {
                setShowDnsFilter(false);
                setShowExcludedApps(true);
              }}
              onNavigateToDnsFilter={() => {
                setShowExcludedApps(false);
                setShowDnsFilter(true);
              }}
              dnsFilterActive={dnsFilterStatus.active}
            />
          )}
        </View>

        {/* White Rounded Bottom Navigation Bar (matching attached design) */}
        <View
          style={[
            styles.bottomBarShell,
            activeTab === 'HOME' && {backgroundColor: colors.royalBlue},
            (showExcludedApps || showDnsFilter) && {display: 'none'},
          ]}>
          <View style={styles.bottomTabBar}>
            {TABS.map(tab => {
              const isActive = activeTab === tab.key;
              return (
                <Pressable
                  key={tab.key}
                  onPress={() => setActiveTab(tab.key)}
                  style={styles.tabItem}>
                  {renderTabIcon(tab.key, isActive)}
                  <Text
                    style={[
                      styles.tabLabel,
                      isActive && styles.tabLabelActive,
                    ]}>
                    {tab.label}
                  </Text>
                  <View
                    style={[
                      styles.tabUnderline,
                      isActive && styles.tabUnderlineActive,
                    ]}
                  />
                </Pressable>
              );
            })}
          </View>
        </View>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: colors.royalBlue,
  },
  errorBanner: {
    backgroundColor: colors.dangerSoft,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.xs + 2,
  },
  errorText: {
    fontSize: 12,
    color: colors.danger,
    fontFamily: typography.mono,
  },
  viewport: {
    flex: 1,
    backgroundColor: colors.background,
  },
  bottomBarShell: {
    backgroundColor: colors.background,
  },
  bottomTabBar: {
    flexDirection: 'row',
    backgroundColor: colors.surface,
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    height: 66,
    paddingTop: 6,
    elevation: 12,
    borderTopWidth: 1,
    borderTopColor: colors.border,
  },
  tabItem: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 3,
  },
  tabLabel: {
    fontSize: 11.5,
    fontWeight: '600',
    color: '#64748B',
  },
  tabLabelActive: {
    color: colors.accent,
    fontWeight: '700',
  },
  tabUnderline: {
    width: 34,
    height: 3,
    borderRadius: 2,
    backgroundColor: 'transparent',
    marginTop: 2,
  },
  tabUnderlineActive: {
    backgroundColor: colors.accent,
  },
});
