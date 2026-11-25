import {useCallback, useEffect, useRef, useState} from 'react';
import {AppState, AppStateStatus} from 'react-native';
import {
  DEFAULT_ACCOUNTABILITY_STATUS,
  DEFAULT_COMPATIBILITY_TARGETS,
  DEFAULT_DNS_FILTER_STATUS,
  DEFAULT_PREFERENCES,
  DEFAULT_STATUS,
  ScreenGuardNative,
} from '../services/ScreenGuardNative';
import {
  AccountabilityConfig,
  AccountabilityStatus,
  ActivityLogEntry,
  AppCompatibilityRecord,
  DebounceTestResult,
  DnsFilterConfigInput,
  DnsFilterStatus,
  InstalledApp,
  DebugPreviewResponse,
  EvalSampleMetadata,
  FullEvaluationReport,
  MonitoringStatus,
  ScreenGuardEvent,
  SingleSampleEvalResult,
  UserPreferences,
} from '../types/screenguard';

export function useScreenGuard() {
  const [status, setStatus] = useState<MonitoringStatus>(DEFAULT_STATUS);
  const [accountability, setAccountability] =
    useState<AccountabilityStatus>(DEFAULT_ACCOUNTABILITY_STATUS);
  const [dnsFilterStatus, setDnsFilterStatus] =
    useState<DnsFilterStatus>(DEFAULT_DNS_FILTER_STATUS);
  const [preferences, setPreferences] =
    useState<UserPreferences>(DEFAULT_PREFERENCES);
  const [activityLogs, setActivityLogs] = useState<ActivityLogEntry[]>([]);
  const [compatibilityRecords, setCompatibilityRecords] = useState<
    AppCompatibilityRecord[]
  >(DEFAULT_COMPATIBILITY_TARGETS);
  const [evalSamples, setEvalSamples] = useState<EvalSampleMetadata[]>([]);
  const [selectedSampleResult, setSelectedSampleResult] =
    useState<SingleSampleEvalResult | null>(null);
  const [evaluationReport, setEvaluationReport] =
    useState<FullEvaluationReport | null>(null);
  const [debugPreview, setDebugPreview] =
    useState<DebugPreviewResponse | null>(null);
  const [debounceTestResult, setDebounceTestResult] =
    useState<DebounceTestResult | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isCapturingTest, setIsCapturingTest] = useState<boolean>(false);
  const [isRunningEvaluation, setIsRunningEvaluation] =
    useState<boolean>(false);
  const [lastError, setLastError] = useState<string | null>(null);

  const isMountedRef = useRef<boolean>(true);
  const dnsStartupAttemptedRef = useRef<boolean>(false);

  const refreshAll = useCallback(async () => {
    if (!ScreenGuardNative.isAvailable()) {
      setIsLoading(false);
      return;
    }
    try {
      const [
        latestStatus,
        latestPrefs,
        latestLogs,
        latestCompat,
        samples,
        latestAccountability,
        latestDnsFilter,
      ] = await Promise.all([
        ScreenGuardNative.getMonitoringStatus(),
        ScreenGuardNative.getPreferences(),
        ScreenGuardNative.getActivityLogs(120),
        ScreenGuardNative.getCompatibilityRecords(),
        ScreenGuardNative.getEvaluationSamples(),
        ScreenGuardNative.getAccountabilityStatus(),
        ScreenGuardNative.getDnsFilterStatus(),
      ]);

      if (!isMountedRef.current) {
        return;
      }
      setStatus(latestStatus);
      setAccountability(latestAccountability);
      setDnsFilterStatus(latestDnsFilter);
      setPreferences(latestPrefs);
      setActivityLogs(latestLogs);
      if (Array.isArray(latestCompat) && latestCompat.length > 0) {
        setCompatibilityRecords(latestCompat);
      }
      if (Array.isArray(samples)) {
        setEvalSamples(samples);
      }
      setLastError(null);

      // Activate local DNS filtering right from the start of the app:
      // if VPN consent is not yet approved, prompt Android's official VPN consent dialog
      // once on launch so approval immediately starts the filter.
      if (!dnsStartupAttemptedRef.current && !latestDnsFilter.active) {
        dnsStartupAttemptedRef.current = true;
        if (!latestDnsFilter.prepared) {
          setTimeout(() => {
            if (!isMountedRef.current) {
              return;
            }
            ScreenGuardNative.requestDnsFilterPermission()
              .then(() => {
                if (isMountedRef.current) {
                  ScreenGuardNative.getDnsFilterStatus()
                    .then(updated => {
                      if (isMountedRef.current) {
                        setDnsFilterStatus(updated);
                      }
                    })
                    .catch(() => {});
                }
              })
              .catch(() => {});
          }, 450);
        }
      }
    } catch (err: unknown) {
      if (isMountedRef.current) {
        setLastError(
          err instanceof Error ? err.message : 'Failed to sync native state',
        );
      }
    } finally {
      if (isMountedRef.current) {
        setIsLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    isMountedRef.current = true;
    refreshAll();

    const unsubscribeEvents = ScreenGuardNative.subscribeToEvents(
      (event: ScreenGuardEvent) => {
        if (!isMountedRef.current) {
          return;
        }
        switch (event.type) {
          case 'SERVICE_STATUS':
            setStatus(prev => ({
              ...prev,
              accessibilityPermissionGranted: event.enabled,
              serviceConnected: event.connected ?? event.enabled,
              monitoringActive:
                event.monitoringActive ??
                (event.enabled && prev.protectionEnabled),
            }));
            refreshAll();
            break;

          case 'FOREGROUND_APP_CHANGED':
            setStatus(prev => ({
              ...prev,
              currentForegroundPackage: event.packageName,
              currentForegroundAppLabel:
                event.appLabel || event.packageName,
              currentWindowId: event.windowId ?? prev.currentWindowId,
            }));
            ScreenGuardNative.getActivityLogs(120)
              .then(logs => {
                if (isMountedRef.current) {
                  setActivityLogs(logs);
                }
              })
              .catch(() => {});
            break;

          case 'SCREENSHOT_SUCCESS':
            setStatus(prev => ({
              ...prev,
              lastScreenshotStatus: 'SUCCESS',
              lastScreenshotWidth: event.width,
              lastScreenshotHeight: event.height,
              lastScreenshotDurationMs: event.durationMs,
              lastScreenshotMethod:
                event.captureMethod || prev.lastScreenshotMethod,
              lastScreenshotErrorCode: 'NONE',
              lastScreenshotTimestamp: event.timestamp || Date.now(),
              totalScreenshotsCaptured: prev.totalScreenshotsCaptured + 1,
            }));
            refreshAll();
            break;

          case 'SCREENSHOT_FAILURE':
            setStatus(prev => ({
              ...prev,
              lastScreenshotStatus: 'FAILURE',
              lastScreenshotErrorCode: event.errorCode,
              lastScreenshotTimestamp: event.timestamp || Date.now(),
              totalScreenshotFailures: prev.totalScreenshotFailures + 1,
            }));
            refreshAll();
            break;

          case 'CLASSIFICATION_RESULT':
            setStatus(prev => ({
              ...prev,
              latestClassification: event.result,
              temporalState: event.result.temporalState || prev.temporalState,
              totalDetections: event.result.isExplicit
                ? prev.totalDetections + 1
                : prev.totalDetections,
              totalConfirmedInterventions: event.result.confirmedIntervention
                ? prev.totalConfirmedInterventions + 1
                : prev.totalConfirmedInterventions,
            }));
            refreshAll();
            break;

          case 'PROTECTION_STATE_CHANGED':
            setStatus(prev => ({
              ...prev,
              protectionSummary: event.summary,
            }));
            refreshAll();
            break;

          case 'ACCOUNTABILITY_STATUS_CHANGED':
            setAccountability(event.status);
            refreshAll();
            break;

          case 'DNS_FILTER_STATUS_CHANGED':
            setDnsFilterStatus(event.status);
            break;
        }
      },
    );

    const appStateSub = AppState.addEventListener(
      'change',
      (nextState: AppStateStatus) => {
        if (nextState === 'active') {
          refreshAll();
        }
      },
    );

    return () => {
      isMountedRef.current = false;
      unsubscribeEvents();
      appStateSub.remove();
    };
  }, [refreshAll]);

  const openAccessibilitySettings = useCallback(async () => {
    try {
      await ScreenGuardNative.openAccessibilitySettings();
    } catch (err: unknown) {
      setLastError(
        err instanceof Error
          ? err.message
          : 'Unable to open Accessibility Settings',
      );
    }
  }, []);

  const openOverlaySettings = useCallback(async () => {
    try {
      await ScreenGuardNative.openOverlaySettings();
    } catch (err: unknown) {
      setLastError(
        err instanceof Error
          ? err.message
          : 'Unable to open Display Over Other Apps Settings',
      );
    }
  }, []);

  const triggerOverlayTest = useCallback(
    async (category = 'EXPLICIT', delayMs = 0, autoHideDurationMs = 0) => {
      setLastError(null);
      try {
        const summary = await ScreenGuardNative.triggerOverlayTest(
          category,
          delayMs,
          autoHideDurationMs,
        );
        if (isMountedRef.current) {
          setStatus(prev => ({
            ...prev,
            protectionSummary: summary,
          }));
        }
        await refreshAll();
        return summary;
      } catch (err: unknown) {
        if (isMountedRef.current) {
          setLastError(
            err instanceof Error
              ? err.message
              : 'Failed to trigger protective overlay test',
          );
        }
        return null;
      }
    },
    [refreshAll],
  );

  const dismissActiveOverlay = useCallback(async () => {
    try {
      const summary = await ScreenGuardNative.dismissActiveOverlay();
      if (isMountedRef.current) {
        setStatus(prev => ({
          ...prev,
          protectionSummary: summary,
        }));
      }
      await refreshAll();
      return summary;
    } catch (err: unknown) {
      if (isMountedRef.current) {
        setLastError(
          err instanceof Error
            ? err.message
            : 'Failed to dismiss protective overlay',
        );
      }
      return null;
    }
  }, [refreshAll]);

  const toggleProtection = useCallback(
    async (enabled: boolean) => {
      try {
        const updatedStatus =
          await ScreenGuardNative.setProtectionEnabled(enabled);
        setStatus(updatedStatus);
        setPreferences(prev => ({...prev, protectionEnabled: enabled}));
        const logs = await ScreenGuardNative.getActivityLogs(120);
        setActivityLogs(logs);
      } catch (err: unknown) {
        setLastError(
          err instanceof Error
            ? err.message
            : 'Unable to update protection state',
        );
      }
    },
    [],
  );

  const updatePreferences = useCallback(
    async (partial: Partial<UserPreferences>) => {
      try {
        const updated = await ScreenGuardNative.updatePreferences(partial);
        setPreferences(updated);
        const latestStatus = await ScreenGuardNative.getMonitoringStatus();
        setStatus(latestStatus);
      } catch (err: unknown) {
        setLastError(
          err instanceof Error ? err.message : 'Unable to save preferences',
        );
      }
    },
    [],
  );

  const clearActivityHistory = useCallback(async () => {
    try {
      await ScreenGuardNative.clearActivityLogs();
      setActivityLogs([]);
      await refreshAll();
    } catch (err: unknown) {
      setLastError(
        err instanceof Error ? err.message : 'Unable to clear activity logs',
      );
    }
  }, [refreshAll]);

  const runManualCaptureTest = useCallback(
    async (delayMs: number, holdInMemoryPreview: boolean) => {
      setIsCapturingTest(true);
      setLastError(null);
      try {
        const res = await ScreenGuardNative.triggerManualScreenshot(
          delayMs,
          holdInMemoryPreview,
        );
        if (holdInMemoryPreview && res.captured && res.base64Jpeg) {
          setDebugPreview(res);
        } else if (!holdInMemoryPreview) {
          setDebugPreview(null);
        } else {
          setDebugPreview(res);
        }
        await refreshAll();
        return res;
      } catch (err: unknown) {
        const msg =
          err instanceof Error ? err.message : 'Manual screenshot test failed';
        setLastError(msg);
        await refreshAll();
        return null;
      } finally {
        if (isMountedRef.current) {
          setIsCapturingTest(false);
        }
      }
    },
    [refreshAll],
  );

  const purgeInMemoryPreview = useCallback(async () => {
    try {
      await ScreenGuardNative.clearInMemoryDebugPreview();
      setDebugPreview(null);
      await refreshAll();
    } catch (err: unknown) {
      setLastError(
        err instanceof Error
          ? err.message
          : 'Failed to purge in-memory preview',
      );
    }
  }, [refreshAll]);

  const runDebounceTest = useCallback(
    async (burstCount = 25, intervalMs = 20) => {
      try {
        const res = await ScreenGuardNative.runDebounceStressTest(
          burstCount,
          intervalMs,
        );
        setDebounceTestResult(res);
        await refreshAll();
        return res;
      } catch (err: unknown) {
        setLastError(
          err instanceof Error ? err.message : 'Debounce test failed',
        );
        return null;
      }
    },
    [refreshAll],
  );

  const runDisconnectionTest = useCallback(async () => {
    try {
      const updated =
        await ScreenGuardNative.simulateServiceDisconnectionTest();
      setStatus(updated);
      setDebugPreview(null);
      await refreshAll();
    } catch (err: unknown) {
      setLastError(
        err instanceof Error
          ? err.message
          : 'Disconnection handling test failed',
      );
    }
  }, [refreshAll]);

  const getInstalledApps = useCallback(async (): Promise<InstalledApp[]> => {
    try {
      return await ScreenGuardNative.getInstalledApps();
    } catch (err: unknown) {
      if (isMountedRef.current) {
        setLastError(
          err instanceof Error ? err.message : 'Unable to load installed apps',
        );
      }
      return [];
    }
  }, []);

  const recordCompatibilityResult = useCallback(
    async (record: AppCompatibilityRecord) => {
      try {
        const updatedList =
          await ScreenGuardNative.updateCompatibilityRecord(record);
        setCompatibilityRecords(updatedList);
      } catch (err: unknown) {
        setLastError(
          err instanceof Error
            ? err.message
            : 'Failed to save compatibility record',
        );
      }
    },
    [],
  );

  const initializeLiteRtModel = useCallback(async () => {
    try {
      const updated = await ScreenGuardNative.initializeModel();
      setStatus(updated);
      return updated;
    } catch (err: unknown) {
      setLastError(
        err instanceof Error ? err.message : 'Failed to initialize LiteRT model',
      );
      return null;
    }
  }, []);

  const runSingleSampleClassification = useCallback(
    async (sampleId: string) => {
      setIsRunningEvaluation(true);
      setLastError(null);
      try {
        const res = await ScreenGuardNative.classifyEvaluationSample(sampleId);
        if (isMountedRef.current) {
          setSelectedSampleResult(res);
        }
        await refreshAll();
        return res;
      } catch (err: unknown) {
        if (isMountedRef.current) {
          setLastError(
            err instanceof Error
              ? err.message
              : 'Sample classification failed',
          );
        }
        return null;
      } finally {
        if (isMountedRef.current) {
          setIsRunningEvaluation(false);
        }
      }
    },
    [refreshAll],
  );

  const runFullEvaluationBenchmark = useCallback(
    async (includeDelegateBenchmark = true) => {
      setIsRunningEvaluation(true);
      setLastError(null);
      try {
        const report = await ScreenGuardNative.runFullModelEvaluation(
          includeDelegateBenchmark,
        );
        if (isMountedRef.current) {
          setEvaluationReport(report);
        }
        await refreshAll();
        return report;
      } catch (err: unknown) {
        if (isMountedRef.current) {
          setLastError(
            err instanceof Error
              ? err.message
              : 'Full model evaluation benchmark failed',
          );
        }
        return null;
      } finally {
        if (isMountedRef.current) {
          setIsRunningEvaluation(false);
        }
      }
    },
    [refreshAll],
  );

  const applyAccountabilityResult = useCallback(
    async (next: AccountabilityStatus | null) => {
      if (next && isMountedRef.current) {
        setAccountability(next);
      }
      await refreshAll();
      return next;
    },
    [refreshAll],
  );

  const enableAccountability = useCallback(
    async (config: AccountabilityConfig) => {
      setLastError(null);
      try {
        const result = await ScreenGuardNative.enableAccountability(config);
        await applyAccountabilityResult(result.status);
        return result;
      } catch (err: unknown) {
        if (isMountedRef.current) {
          setLastError(
            err instanceof Error ? err.message : 'Unable to enable accountability',
          );
        }
        return null;
      }
    },
    [applyAccountabilityResult],
  );

  const requestAccountabilityDeactivation = useCallback(async () => {
    try {
      const next = await ScreenGuardNative.requestAccountabilityDeactivation();
      return await applyAccountabilityResult(next);
    } catch (err: unknown) {
      setLastError(
        err instanceof Error ? err.message : 'Unable to request deactivation',
      );
      return null;
    }
  }, [applyAccountabilityResult]);

  const cancelAccountabilityDeactivation = useCallback(async () => {
    try {
      const next = await ScreenGuardNative.cancelAccountabilityDeactivation();
      return await applyAccountabilityResult(next);
    } catch (err: unknown) {
      setLastError(
        err instanceof Error ? err.message : 'Unable to cancel deactivation',
      );
      return null;
    }
  }, [applyAccountabilityResult]);

  const confirmAccountabilityDeactivation = useCallback(
    async (secretInput: string) => {
      try {
        const next =
          await ScreenGuardNative.confirmAccountabilityDeactivation(secretInput);
        return await applyAccountabilityResult(next);
      } catch (err: unknown) {
        setLastError(
          err instanceof Error ? err.message : 'Unable to confirm deactivation',
        );
        return null;
      }
    },
    [applyAccountabilityResult],
  );

  const setAccountabilityPin = useCallback(
    async (pin: string) => {
      try {
        const next = await ScreenGuardNative.setAccountabilityPin(pin);
        return await applyAccountabilityResult(next);
      } catch (err: unknown) {
        setLastError(
          err instanceof Error ? err.message : 'Unable to update PIN',
        );
        return null;
      }
    },
    [applyAccountabilityResult],
  );

  const activateDeviceAdmin = useCallback(async () => {
    try {
      const opened = await ScreenGuardNative.requestDeviceAdminActivation();
      await refreshAll();
      return opened;
    } catch (err: unknown) {
      setLastError(
        err instanceof Error ? err.message : 'Unable to open administrator activation',
      );
      return false;
    }
  }, [refreshAll]);

  const openDeviceAdminSettings = useCallback(async () => {
    try {
      await ScreenGuardNative.openDeviceAdminSettings();
    } catch (err: unknown) {
      setLastError(
        err instanceof Error ? err.message : 'Unable to open administrator settings',
      );
    }
  }, []);

  const deactivateDeviceAdmin = useCallback(async () => {
    try {
      const opened = await ScreenGuardNative.requestDeviceAdminDeactivation();
      await refreshAll();
      return opened;
    } catch (err: unknown) {
      setLastError(
        err instanceof Error ? err.message : 'Unable to start administrator deactivation',
      );
      return false;
    }
  }, [refreshAll]);

  const lockDeviceViaAdmin = useCallback(async () => {
    try {
      const locked = await ScreenGuardNative.lockDeviceViaAdmin();
      if (!locked) {
        setLastError('Activate Screen Guard device administrator before using lock now.');
      }
      return locked;
    } catch (err: unknown) {
      setLastError(err instanceof Error ? err.message : 'Unable to lock device');
      return false;
    }
  }, []);

  const applyDnsFilterResult = useCallback(
    async (next: DnsFilterStatus | null) => {
      if (next && isMountedRef.current) {
        setDnsFilterStatus(next);
      }
      await refreshAll();
      return next;
    },
    [refreshAll],
  );

  const requestDnsFilterPermission = useCallback(async () => {
    setLastError(null);
    try {
      const approved = await ScreenGuardNative.requestDnsFilterPermission();
      await refreshAll();
      return approved;
    } catch (err: unknown) {
      setLastError(
        err instanceof Error ? err.message : 'Unable to open VPN consent screen',
      );
      return false;
    }
  }, [refreshAll]);

  const startDnsFilter = useCallback(async () => {
    setLastError(null);
    try {
      const next = await ScreenGuardNative.startDnsFilter();
      return await applyDnsFilterResult(next);
    } catch (err: unknown) {
      setLastError(
        err instanceof Error ? err.message : 'Unable to start the DNS filter',
      );
      return null;
    }
  }, [applyDnsFilterResult]);

  const stopDnsFilter = useCallback(async () => {
    try {
      const next = await ScreenGuardNative.stopDnsFilter();
      return await applyDnsFilterResult(next);
    } catch (err: unknown) {
      setLastError(
        err instanceof Error ? err.message : 'Unable to stop the DNS filter',
      );
      return null;
    }
  }, [applyDnsFilterResult]);

  const updateDnsFilterConfig = useCallback(
    async (config: DnsFilterConfigInput) => {
      setLastError(null);
      try {
        const next = await ScreenGuardNative.updateDnsFilterConfig(config);
        return await applyDnsFilterResult(next);
      } catch (err: unknown) {
        setLastError(
          err instanceof Error ? err.message : 'Unable to save DNS filter settings',
        );
        return null;
      }
    },
    [applyDnsFilterResult],
  );

  return {
    status,
    accountability,
    dnsFilterStatus,
    preferences,
    activityLogs,
    compatibilityRecords,
    evalSamples,
    selectedSampleResult,
    evaluationReport,
    debugPreview,
    debounceTestResult,
    isLoading,
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
    getInstalledApps,
    recordCompatibilityResult,
    initializeLiteRtModel,
    runSingleSampleClassification,
    runFullEvaluationBenchmark,
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
  };
}
