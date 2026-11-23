import {
  NativeEventEmitter,
  NativeModules,
  Platform,
  EmitterSubscription,
} from 'react-native';
import {
  ActivityLogEntry,
  AppCompatibilityRecord,
  DebounceTestResult,
  DebugPreviewResponse,
  EvalSampleMetadata,
  FullEvaluationReport,
  MonitoringStatus,
  ScreenGuardEvent,
  SingleSampleEvalResult,
  UserPreferences,
} from '../types/screenguard';

interface ScreenGuardNativeModuleSpec {
  isAccessibilityPermissionEnabled(): Promise<boolean>;
  openAccessibilitySettings(): Promise<boolean>;
  getMonitoringStatus(): Promise<MonitoringStatus>;
  setProtectionEnabled(enabled: boolean): Promise<MonitoringStatus>;
  getPreferences(): Promise<UserPreferences>;
  updatePreferences(prefsJson: string): Promise<UserPreferences>;
  getActivityLogs(limit: number): Promise<ActivityLogEntry[]>;
  clearActivityLogs(): Promise<boolean>;
  getCompatibilityRecords(): Promise<AppCompatibilityRecord[]>;
  updateCompatibilityRecord(recordJson: string): Promise<AppCompatibilityRecord[]>;
  triggerManualScreenshot(
    delayMs: number,
    holdInMemoryPreview: boolean,
  ): Promise<DebugPreviewResponse>;
  getInMemoryDebugPreview(): Promise<DebugPreviewResponse>;
  clearInMemoryDebugPreview(): Promise<boolean>;
  runDebounceStressTest(
    burstCount: number,
    intervalMs: number,
  ): Promise<DebounceTestResult>;
  simulateServiceDisconnectionTest(): Promise<MonitoringStatus>;
  initializeModel(): Promise<MonitoringStatus>;
  getEvaluationSamples(): Promise<EvalSampleMetadata[]>;
  classifyEvaluationSample(sampleId: string): Promise<SingleSampleEvalResult>;
  runFullModelEvaluation(
    includeDelegateBenchmark: boolean,
  ): Promise<FullEvaluationReport>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

const RawModule = NativeModules.ScreenGuardModule as
  | ScreenGuardNativeModuleSpec
  | undefined;

const EVENT_CHANNEL = 'ScreenGuardNativeEvent';

let eventEmitter: NativeEventEmitter | null = null;
if (Platform.OS === 'android' && RawModule) {
  eventEmitter = new NativeEventEmitter(NativeModules.ScreenGuardModule);
}

export const DEFAULT_PREFERENCES: UserPreferences = {
  protectionEnabled: true,
  excludedPackages: [
    'com.android.systemui',
    'com.google.android.inputmethod.latin',
    'com.samsung.android.honeyboard',
  ],
  samplingIntervalMs: 2500,
  debounceDelayMs: 650,
  preferWindowCapture: true,
  confidenceThreshold: 0.75,
  confirmationFramesRequired: 3,
  interventionBehavior: 'LOG_ONLY',
  mockClassifierProfile: 'DETERMINISTIC_SAFE',
  allowSelfPackageInDiagnostics: true,
};

export const DEFAULT_STATUS: MonitoringStatus = {
  accessibilityPermissionGranted: false,
  serviceConnected: false,
  protectionEnabled: true,
  monitoringActive: false,
  currentForegroundPackage: 'None',
  currentForegroundAppLabel: 'No active window',
  currentWindowId: -1,
  androidSdkInt: typeof Platform.Version === 'number' ? Platform.Version : 36,
  androidRelease: '16',
  deviceModel: 'Android Device',
  supportsTakeScreenshot: true,
  supportsTakeScreenshotOfWindow: true,
  lastScreenshotStatus: 'IDLE',
  lastScreenshotTimestamp: 0,
  lastScreenshotWidth: 0,
  lastScreenshotHeight: 0,
  lastScreenshotDurationMs: 0,
  lastScreenshotMethod: 'NONE',
  lastScreenshotErrorCode: 'NONE',
  totalScreenshotsCaptured: 0,
  totalScreenshotFailures: 0,
  totalDetections: 0,
  totalConfirmedInterventions: 0,
  modelLoaded: false,
  modelId: 'nsfw-mobilenet-v2-140-224',
  modelVersion: '1.2.0-litert-fp32 (GantMan/nsfw_model MobileNetV2 1.4)',
  modelSizeBytes: 17251928,
  modelInitDurationMs: 0,
  activeDelegate: 'CPU_XNNPACK_4T',
  inputTensorSpec: '[1, 224, 224, 3] FLOAT32 RGB [0.0..1.0]',
  outputTensorSpec: '[1, 5] FLOAT32 (drawings, hentai, neutral, porn, sexy)',
  temporalState: 'SAFE_STABLE',
  smoothedExplicitScore: 0,
  smoothedNudityScore: 0,
  smoothedSafeScore: 1,
  totalFramesClassified: 0,
  explicitDetections: 0,
  nudityDetections: 0,
  suggestiveDetections: 0,
  nonExplicitDetections: 0,
  safeClassifications: 0,
  uncertainClassifications: 0,
  duplicateFramesSkipped: 0,
  obsoleteFramesDropped: 0,
  latestClassification: null,
  bufferDiagnostics: {
    hardwareBuffersAllocated: 0,
    hardwareBuffersClosed: 0,
    bitmapsAllocated: 0,
    bitmapsRecycled: 0,
    activeInMemoryBuffers: 0,
    hasInMemoryDebugPreview: false,
    lastCaptureMethod: 'NONE',
    lastErrorCode: 'NONE',
    lastOriginalWidth: 0,
    lastOriginalHeight: 0,
    lastProcessedWidth: 0,
    lastProcessedHeight: 0,
    lastCaptureDurationMs: 0,
    lastClassificationDurationMs: 0,
    debounceEventsReceived: 0,
    debounceEventsAccepted: 0,
    debounceEventsSuppressed: 0,
    serviceDisconnectCount: 0,
    serviceReconnectCount: 0,
  },
};

export const DEFAULT_COMPATIBILITY_TARGETS: AppCompatibilityRecord[] = [
  {
    packageName: 'com.android.settings',
    displayName: 'Android Settings',
    category: 'System Settings',
    outcome: 'UNTESTED',
    lastTestedAt: null,
    captureMethod: 'N/A',
    resolution: 'N/A',
    durationMs: 0,
    errorCode: 'NONE',
    notes: 'Standard system activities allow accessibility capture; credential/PIN subscreens enforce FLAG_SECURE.',
  },
  {
    packageName: 'com.android.chrome',
    displayName: 'Google Chrome',
    category: 'Browser',
    outcome: 'UNTESTED',
    lastTestedAt: null,
    captureMethod: 'N/A',
    resolution: 'N/A',
    durationMs: 0,
    errorCode: 'NONE',
    notes: 'Normal tabs support takeScreenshotOfWindow/takeScreenshot; Incognito tabs enforce FLAG_SECURE by default.',
  },
  {
    packageName: 'com.google.android.youtube',
    displayName: 'YouTube',
    category: 'Video Streaming',
    outcome: 'UNTESTED',
    lastTestedAt: null,
    captureMethod: 'N/A',
    resolution: 'N/A',
    durationMs: 0,
    errorCode: 'NONE',
    notes: 'Standard feeds and video players are capturable; DRM-protected Movies/TV purchases block surface capture.',
  },
  {
    packageName: 'com.instagram.android',
    displayName: 'Instagram',
    category: 'Social Media',
    outcome: 'UNTESTED',
    lastTestedAt: null,
    captureMethod: 'N/A',
    resolution: 'N/A',
    durationMs: 0,
    errorCode: 'NONE',
    notes: 'Feed, Reels, and Stories windows are capturable; disappearing direct media may use FLAG_SECURE.',
  },
  {
    packageName: 'com.zhiliaoapp.musically',
    displayName: 'TikTok',
    category: 'Short Video',
    outcome: 'UNTESTED',
    lastTestedAt: null,
    captureMethod: 'N/A',
    resolution: 'N/A',
    durationMs: 0,
    errorCode: 'NONE',
    notes: 'Hardware-accelerated video surface composited into window buffer on Android 11+.',
  },
  {
    packageName: 'com.sec.android.app.launcher',
    displayName: 'Home Screen (One UI / System Launcher)',
    category: 'Launcher',
    outcome: 'UNTESTED',
    lastTestedAt: null,
    captureMethod: 'N/A',
    resolution: 'N/A',
    durationMs: 0,
    errorCode: 'NONE',
    notes: 'Home screen workspace and app drawer windows.',
  },
];

function ensureNativeModule(): ScreenGuardNativeModuleSpec {
  if (!RawModule) {
    throw new Error(
      'ScreenGuardModule native bridge is unavailable. Ensure the Android native build is installed.',
    );
  }
  return RawModule;
}

export const ScreenGuardNative = {
  isAvailable(): boolean {
    return Boolean(RawModule);
  },

  async isAccessibilityPermissionEnabled(): Promise<boolean> {
    const mod = ensureNativeModule();
    return mod.isAccessibilityPermissionEnabled();
  },

  async openAccessibilitySettings(): Promise<boolean> {
    const mod = ensureNativeModule();
    return mod.openAccessibilitySettings();
  },

  async getMonitoringStatus(): Promise<MonitoringStatus> {
    const mod = ensureNativeModule();
    return mod.getMonitoringStatus();
  },

  async setProtectionEnabled(enabled: boolean): Promise<MonitoringStatus> {
    const mod = ensureNativeModule();
    return mod.setProtectionEnabled(enabled);
  },

  async getPreferences(): Promise<UserPreferences> {
    const mod = ensureNativeModule();
    return mod.getPreferences();
  },

  async updatePreferences(
    partial: Partial<UserPreferences>,
  ): Promise<UserPreferences> {
    const mod = ensureNativeModule();
    return mod.updatePreferences(JSON.stringify(partial));
  },

  async getActivityLogs(limit = 120): Promise<ActivityLogEntry[]> {
    const mod = ensureNativeModule();
    return mod.getActivityLogs(limit);
  },

  async clearActivityLogs(): Promise<boolean> {
    const mod = ensureNativeModule();
    return mod.clearActivityLogs();
  },

  async getCompatibilityRecords(): Promise<AppCompatibilityRecord[]> {
    const mod = ensureNativeModule();
    return mod.getCompatibilityRecords();
  },

  async updateCompatibilityRecord(
    record: AppCompatibilityRecord,
  ): Promise<AppCompatibilityRecord[]> {
    const mod = ensureNativeModule();
    return mod.updateCompatibilityRecord(JSON.stringify(record));
  },

  async triggerManualScreenshot(
    delayMs = 0,
    holdInMemoryPreview = false,
  ): Promise<DebugPreviewResponse> {
    const mod = ensureNativeModule();
    return mod.triggerManualScreenshot(delayMs, holdInMemoryPreview);
  },

  async getInMemoryDebugPreview(): Promise<DebugPreviewResponse> {
    const mod = ensureNativeModule();
    return mod.getInMemoryDebugPreview();
  },

  async clearInMemoryDebugPreview(): Promise<boolean> {
    const mod = ensureNativeModule();
    return mod.clearInMemoryDebugPreview();
  },

  async runDebounceStressTest(
    burstCount = 25,
    intervalMs = 20,
  ): Promise<DebounceTestResult> {
    const mod = ensureNativeModule();
    return mod.runDebounceStressTest(burstCount, intervalMs);
  },

  async simulateServiceDisconnectionTest(): Promise<MonitoringStatus> {
    const mod = ensureNativeModule();
    return mod.simulateServiceDisconnectionTest();
  },

  async initializeModel(): Promise<MonitoringStatus> {
    const mod = ensureNativeModule();
    return mod.initializeModel();
  },

  async getEvaluationSamples(): Promise<EvalSampleMetadata[]> {
    const mod = ensureNativeModule();
    return mod.getEvaluationSamples();
  },

  async classifyEvaluationSample(
    sampleId: string,
  ): Promise<SingleSampleEvalResult> {
    const mod = ensureNativeModule();
    return mod.classifyEvaluationSample(sampleId);
  },

  async runFullModelEvaluation(
    includeDelegateBenchmark = true,
  ): Promise<FullEvaluationReport> {
    const mod = ensureNativeModule();
    return mod.runFullModelEvaluation(includeDelegateBenchmark);
  },

  subscribeToEvents(listener: (event: ScreenGuardEvent) => void): () => void {
    if (!eventEmitter) {
      return () => {};
    }
    const sub: EmitterSubscription = eventEmitter.addListener(
      EVENT_CHANNEL,
      (rawEvent: unknown) => {
        if (rawEvent && typeof rawEvent === 'object' && 'type' in rawEvent) {
          listener(rawEvent as ScreenGuardEvent);
        }
      },
    );
    return () => {
      sub.remove();
    };
  },
};
