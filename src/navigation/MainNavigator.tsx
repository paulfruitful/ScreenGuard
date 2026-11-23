import React, {useState} from 'react';
import {
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
import {StatusBadge} from '../components/StatusBadge';
import {useScreenGuard} from '../hooks/useScreenGuard';
import {ActivityScreen} from '../screens/ActivityScreen';
import {ClassifierScreen} from '../screens/ClassifierScreen';
import {DashboardScreen} from '../screens/DashboardScreen';
import {SettingsScreen} from '../screens/SettingsScreen';
import {TestingScreen} from '../screens/TestingScreen';
import {colors, spacing, typography} from '../theme/theme';

export type TabKey =
  | 'DASHBOARD'
  | 'CLASSIFIER'
  | 'ACTIVITY'
  | 'TESTING'
  | 'SETTINGS';

const TABS: {key: TabKey; label: string}[] = [
  {key: 'DASHBOARD', label: 'Dashboard'},
  {key: 'CLASSIFIER', label: 'Classifier'},
  {key: 'ACTIVITY', label: 'Activity'},
  {key: 'TESTING', label: 'Capture'},
  {key: 'SETTINGS', label: 'Settings'},
];

export function MainNavigator() {
  const [activeTab, setActiveTab] = useState<TabKey>('DASHBOARD');
  const {
    status,
    preferences,
    activityLogs,
    compatibilityRecords,
    evalSamples,
    selectedSampleResult,
    evaluationReport,
    debugPreview,
    debounceTestResult,
    isCapturingTest,
    isRunningEvaluation,
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
    recordCompatibilityResult,
    initializeLiteRtModel,
    runSingleSampleClassification,
    runFullEvaluationBenchmark,
  } = useScreenGuard();

  const monitoringActive =
    status.accessibilityPermissionGranted &&
    status.serviceConnected &&
    status.protectionEnabled;

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.safeArea} edges={['top', 'bottom']}>
        <StatusBar barStyle="light-content" />

        {/* Top Application Header with Persistent Monitoring Indicator */}
        <View style={styles.topHeader}>
          <View>
            <Text style={styles.brandTitle}>Screen Guard</Text>
            <Text style={styles.brandSubtitle}>
              System-Wide Content Filtering & Protective Overlay
            </Text>
          </View>
          <StatusBadge
            label={
              monitoringActive
                ? 'Observing'
                : status.accessibilityPermissionGranted
                ? 'Paused'
                : 'Permission Needed'
            }
            tone={
              monitoringActive
                ? 'success'
                : status.accessibilityPermissionGranted
                ? 'neutral'
                : 'warning'
            }
          />
        </View>

        {lastError ? (
          <View style={styles.errorBanner}>
            <Text style={styles.errorText} numberOfLines={2}>
              {lastError}
            </Text>
          </View>
        ) : null}

        {/* Active Screen Viewport */}
        <View style={styles.viewport}>
          {activeTab === 'DASHBOARD' && (
            <DashboardScreen
              status={status}
              preferences={preferences}
              onOpenAccessibilitySettings={openAccessibilitySettings}
              onOpenOverlaySettings={openOverlaySettings}
              onToggleProtection={toggleProtection}
              onUpdatePreferences={updatePreferences}
              onTriggerOverlayTest={triggerOverlayTest}
              onDismissOverlay={dismissActiveOverlay}
              onNavigateToTesting={() => setActiveTab('TESTING')}
              onNavigateToClassifier={() => setActiveTab('CLASSIFIER')}
              onRefresh={refreshAll}
            />
          )}

          {activeTab === 'CLASSIFIER' && (
            <ClassifierScreen
              status={status}
              evalSamples={evalSamples}
              selectedSampleResult={selectedSampleResult}
              evaluationReport={evaluationReport}
              isRunningEvaluation={isRunningEvaluation}
              onInitializeModel={initializeLiteRtModel}
              onClassifySample={runSingleSampleClassification}
              onRunFullEvaluation={runFullEvaluationBenchmark}
            />
          )}

          {activeTab === 'ACTIVITY' && (
            <ActivityScreen
              logs={activityLogs}
              onClearLogs={clearActivityHistory}
              onRefresh={refreshAll}
            />
          )}

          {activeTab === 'TESTING' && (
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

          {activeTab === 'SETTINGS' && (
            <SettingsScreen
              preferences={preferences}
              onUpdatePreferences={updatePreferences}
              onToggleProtection={toggleProtection}
              onOpenAccessibilitySettings={openAccessibilitySettings}
              onOpenOverlaySettings={openOverlaySettings}
            />
          )}
        </View>

        {/* Bottom Navigation Bar */}
        <View style={styles.bottomTabBar}>
          {TABS.map(tab => {
            const isActive = activeTab === tab.key;
            return (
              <Pressable
                key={tab.key}
                onPress={() => setActiveTab(tab.key)}
                style={styles.tabItem}>
                <View
                  style={[
                    styles.tabIndicator,
                    isActive && styles.tabIndicatorActive,
                  ]}
                />
                <Text
                  style={[
                    styles.tabLabel,
                    isActive && styles.tabLabelActive,
                  ]}>
                  {tab.label}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: colors.surface,
  },
  topHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    backgroundColor: colors.surface,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  brandTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: colors.textPrimary,
    letterSpacing: 0.2,
  },
  brandSubtitle: {
    fontSize: 11.5,
    color: colors.textMuted,
    marginTop: 1,
  },
  errorBanner: {
    backgroundColor: colors.dangerSoft,
    borderBottomWidth: 1,
    borderBottomColor: colors.dangerBorder,
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
  bottomTabBar: {
    flexDirection: 'row',
    backgroundColor: colors.surface,
    borderTopWidth: 1,
    borderTopColor: colors.border,
    height: 56,
  },
  tabItem: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    position: 'relative',
  },
  tabIndicator: {
    position: 'absolute',
    top: 0,
    width: 28,
    height: 2.5,
    backgroundColor: 'transparent',
    borderBottomLeftRadius: 2,
    borderBottomRightRadius: 2,
  },
  tabIndicatorActive: {
    backgroundColor: colors.accent,
  },
  tabLabel: {
    fontSize: 11.5,
    fontWeight: '600',
    color: colors.textMuted,
  },
  tabLabelActive: {
    color: colors.textPrimary,
    fontWeight: '700',
  },
});
