export type ClassificationCategory =
  | 'EXPLICIT'
  | 'NUDITY'
  | 'SEXUAL_ACTIVITY'
  | 'SUGGESTIVE'
  | 'NON_EXPLICIT'
  | 'SAFE'
  | 'UNCERTAIN';

export type MockClassifierProfile =
  | 'DETERMINISTIC_SAFE'
  | 'SYNTHETIC_VARIATION'
  | 'STRESS_TEST_SUSPICIOUS';

export type InterventionBehavior =
  | 'PROTECTIVE_OVERLAY'
  | 'STATUS_NOTIFICATION'
  | 'LOG_ONLY';

export type ProtectionSensitivityMode = 'STRICT';

export type OverlayState =
  | 'HIDDEN'
  | 'PENDING'
  | 'PROTECTED'
  | 'RECOVERING'
  | 'DISMISSED';

export interface ProtectionActivitySummary {
  overlayState: OverlayState | string;
  overlayVisible: boolean;
  activeProtectedPackage: string;
  activeProtectedAppLabel: string;
  activeTriggerCategory: string;
  activeConfidence: number;
  sensitivityMode: ProtectionSensitivityMode | string;
  totalProtectionEvents: number;
  protectedAppsCount: number;
  protectedPackageNames: string[];
  totalDismissals: number;
  totalReturnToSafetyClicks: number;
  totalAutoRecoveries: number;
  cooldownRemainingMs: number;
  lastOverlayError: string;
}

export interface ClassificationResult {
  scores: Record<string, number>;
  predictedCategory: ClassificationCategory | string;
  category: ClassificationCategory | string;
  confidence: number;
  processingDurationMs: number;
  modelVersion: string;
  successful: boolean;
  error: string | null;
  isExplicit: boolean;
  nudityScore: number;
  explicitScore: number;
  sexualActivityScore?: number;
  suggestiveScore: number;
  safeScore: number;
  drawingsScore: number;
  hentaiScore: number;
  neutralScore: number;
  pornScore: number;
  sexyScore: number;
  stage2Triggered?: boolean;
  stage2NsfwScore?: number;
  stage2SfwScore?: number;
  stage2DurationMs?: number;
  preprocessingApplied?: string;
  isLowLightFrame?: boolean;
  meanLuminance?: number;
  originalCategory?: string;
  originalConfidence?: number;
  consecutiveDetections: number;
  confirmedIntervention: boolean;
  temporalState: string;
  smoothedExplicitScore: number;
  isDuplicateSkipped: boolean;
  frameHash: string;
  delegateUsed: string;
  thresholdUsed: number;
  modelId: string;
  isMockModel: boolean;
  frameWidth: number;
  frameHeight: number;
  packageName: string;
  windowId: number;
  timestamp: number;
}

export interface AppUpdateInfo {
  currentVersion: string;
  latestVersion: string;
  updateAvailable: boolean;
  releaseTitle: string;
  releaseNotes: string;
  downloadUrl: string;
  assetName: string;
  assetSizeBytes: number;
  releaseUrl: string;
  publishedAt: string;
}

export interface UpdateInstallOutcome {
  status: 'INSTALLER_LAUNCHED' | 'NEEDS_INSTALL_PERMISSION' | string;
  message: string;
  filePath: string;
}

export interface CoolOffConfig {
  triggerThreshold: number;
  detectionWindowMinutes: number;
  defaultDurationMinutes: number;
  audioCategory: string;
  audioMuted: boolean;
  audioVolume: number;
  isEscalated: boolean;
  recentActivationCount: number;
}

export interface DoomscrollConfig {
  enabled: boolean;
  tiktokEnabled: boolean;
  instagramEnabled: boolean;
  facebookEnabled: boolean;
  xEnabled: boolean;
  intendedDurationMinutes: number;
  strictTimeLimitEnabled: boolean;
  meaningfulBreakSeconds: number;
  gentleReminderCooldownSeconds: number;
  strongInterventionCooldownSeconds: number;
}

export interface DoomscrollLiveStatus {
  enabled: boolean;
  activeAppPackage: string;
  activeAppName: string;
  feedContextType: string;
  isInConfirmedFeed: boolean;
  feedConfidence: number;
  activeDurationSeconds: number;
  videoTransitionCount: number;
  averageDwellSeconds: number;
  transitionsPerMinute: number;
  reopeningCount: number;
  riskScore: number;
  riskLevel: string;
  lastInterventionLevel: string;
  intendedDurationMinutes: number;
  totalSessionsToday: number;
  totalActiveMinutesToday: number;
  signalExplanation: string;
}

export type ScreenGuardEvent =
  | {
      type: 'UPDATE_DOWNLOAD_PROGRESS';
      progressPercent: number;
      downloadedBytes: number;
      totalBytes: number;
      timestamp?: number;
    }
  | {
      type: 'SERVICE_STATUS';
      enabled: boolean;
      connected?: boolean;
      monitoringActive?: boolean;
      timestamp?: number;
    }
  | {
      type: 'FOREGROUND_APP_CHANGED';
      packageName: string;
      appLabel?: string;
      windowId?: number;
      isExcluded?: boolean;
      timestamp?: number;
    }
  | {
      type: 'SCREENSHOT_SUCCESS';
      width: number;
      height: number;
      durationMs: number;
      captureMethod?: string;
      packageName?: string;
      windowId?: number;
      buffersReleased?: boolean;
      timestamp?: number;
    }
  | {
      type: 'SCREENSHOT_FAILURE';
      errorCode: string;
      errorMessage?: string;
      packageName?: string;
      windowId?: number;
      captureMethod?: string;
      timestamp?: number;
    }
  | {
      type: 'CLASSIFICATION_RESULT';
      result: ClassificationResult;
    }
  | {
      type: 'PROTECTION_STATE_CHANGED';
      summary: ProtectionActivitySummary;
      directive?: string;
      reasonSummary?: string;
      timestamp?: number;
    }
  | {
      type: 'ACCOUNTABILITY_STATUS_CHANGED';
      status: AccountabilityStatus;
      timestamp?: number;
    }
  | {
      type: 'DNS_FILTER_STATUS_CHANGED';
      status: DnsFilterStatus;
      timestamp?: number;
    };

export type ActivityEventType =
  | 'APP_DETECTED'
  | 'SCREENSHOT_SUCCESS'
  | 'SCREENSHOT_FAILURE'
  | 'CLASSIFICATION_COMPLETED'
  | 'SUSPICIOUS_DETECTED'
  | 'SERVICE_STATE'
  | 'ACCOUNTABILITY'
  | 'DEVICE_ADMIN_ENABLED'
  | 'DEVICE_ADMIN_DISABLED'
  | 'DEVICE_ADMIN_DISABLE_REQUESTED';

export interface ActivityLogEntry {
  id: string;
  type: ActivityEventType;
  title: string;
  detail: string;
  packageName: string;
  timestamp: number;
  durationMs?: number;
  width?: number;
  height?: number;
  errorCode?: string;
  captureMethod?: string;
  category?: string;
  nudityScore?: number;
  explicitScore?: number;
  suggestiveScore?: number;
  safeScore?: number;
  confirmedIntervention?: boolean;
}

/** An installed app offered in the exclusion picker. */
export interface InstalledApp {
  packageName: string;
  label: string;
  isSystem: boolean;
}

export interface UserPreferences {
  protectionEnabled: boolean;
  excludedPackages: string[];
  samplingIntervalMs: number;
  debounceDelayMs: number;
  preferWindowCapture: boolean;
  confidenceThreshold: number;
  confirmationFramesRequired: number;
  interventionBehavior: InterventionBehavior;
  sensitivityMode: ProtectionSensitivityMode;
  interveneOnSuggestiveInStrict: boolean;
  allowManualDismiss: boolean;
  dismissCooldownSeconds: number;
  mockClassifierProfile: MockClassifierProfile;
  allowSelfPackageInDiagnostics: boolean;
}

export type AccountabilityMode =
  | 'OFF'
  | 'ACCOUNTABILITY'
  | 'ACCOUNTABILITY_DEVICE_ADMIN'
  | 'MANAGED_DEVICE_OBSERVED';

export type CommitmentDurationType =
  | 'ONE_DAY'
  | 'SEVEN_DAYS'
  | 'THIRTY_DAYS'
  | 'CUSTOM'
  | 'NONE';

export interface AccountabilityConfig {
  durationType: CommitmentDurationType;
  customDays: number;
  pin: string;
  coolingOffMinutes: number;
}

export interface EnableAccountabilityResult {
  status: AccountabilityStatus;
  recoveryCode: string;
}

/**
 * Mirrors the native AccountabilityStatus snapshot exactly.
 * `uninstallEnforcementActive` is true only when a real Device Owner policy is observed;
 * a voluntary commitment or ordinary device administrator never sets it to true.
 */
export interface AccountabilityStatus {
  active: boolean;
  mode: AccountabilityMode | string;
  durationType: CommitmentDurationType | string;
  commitmentDays: number;
  commitmentDurationMs: number;
  startedAtMs: number;
  endsAtMs: number;
  remainingMs: number;
  expired: boolean;
  pinConfigured: boolean;
  coolingOffMinutes: number;
  deactivationRequested: boolean;
  deactivationEligibleAtMs: number;
  coolingOffRemainingMs: number;
  recoveryCodeHint: string;
  clockTrusted: boolean;
  clockRollbackDetected: boolean;
  timingNote: string;
  deviceAdminActive: boolean;
  deviceAdminComponent: string;
  deviceOwnerActive: boolean;
  profileOwnerActive: boolean;
  managedUninstallPolicyActive: boolean;
  technicalEnforcement: string;
  uninstallEnforcementActive: boolean;
  enforcementSummary: string;
  lastEvent: string;
  lastEventTimestampMs: number;
}

/** Milestone 5 — user-approved local DNS content filter. */
export type DnsFilterProfile =
  | 'CLOUDFLARE_FAMILY'
  | 'ADGUARD_FAMILY'
  | 'CLEANBROWSING_ADULT'
  | 'OPENDNS_FAMILYSHIELD'
  | 'CUSTOM'
  | string;

export interface DnsFilterStatus {
  active: boolean;
  prepared: boolean;
  profile: DnsFilterProfile;
  profileLabel: string;
  upstream: string;
  customDomains: string[];
  customDomainCount: number;
  whitelistedDomains: string[];
  whitelistedDomainCount: number;
  useStarterList: boolean;
  blockedQueries: number;
  allowedQueries: number;
  failedQueries: number;
  lastEvent: string;
  lastEventTimestampMs: number;
  note: string;
}

export interface DnsFilterConfigInput {
  profile: DnsFilterProfile;
  customHost: string;
  customDomains: string[];
  whitelistedDomains: string[];
  useStarterList: boolean;
}

export interface ManagedDeviceStatus {
  deviceOwnerActive: boolean;
  profileOwnerActive: boolean;
  deviceAdminActive: boolean;
  managedUninstallPolicyActive: boolean;
  provisioningState: 'DEVICE_OWNER_ACTIVE' | 'PROFILE_OWNER_ACTIVE' | 'NOT_ENROLLED' | string;
}

export interface BufferDiagnostics {
  hardwareBuffersAllocated: number;
  hardwareBuffersClosed: number;
  bitmapsAllocated: number;
  bitmapsRecycled: number;
  activeInMemoryBuffers: number;
  hasInMemoryDebugPreview: boolean;
  lastCaptureMethod: string;
  lastErrorCode: string;
  lastOriginalWidth: number;
  lastOriginalHeight: number;
  lastProcessedWidth: number;
  lastProcessedHeight: number;
  lastCaptureDurationMs: number;
  lastClassificationDurationMs: number;
  debounceEventsReceived: number;
  debounceEventsAccepted: number;
  debounceEventsSuppressed: number;
  serviceDisconnectCount: number;
  serviceReconnectCount: number;
}

export interface MonitoringStatus {
  accessibilityPermissionGranted: boolean;
  overlayPermissionGranted: boolean;
  batteryOptimizationIgnored?: boolean;
  isOemAutostartRecommended?: boolean;
  autostartEnabled?: boolean;
  serviceConnected: boolean;
  protectionEnabled: boolean;
  monitoringActive: boolean;
  sensitivityMode?: ProtectionSensitivityMode;
  protectionSummary?: ProtectionActivitySummary;
  currentForegroundPackage: string;
  currentForegroundAppLabel: string;
  currentWindowId: number;
  androidSdkInt: number;
  androidRelease: string;
  deviceModel: string;
  supportsTakeScreenshot: boolean;
  supportsTakeScreenshotOfWindow: boolean;
  lastScreenshotStatus: 'IDLE' | 'SUCCESS' | 'FAILURE';
  lastScreenshotTimestamp: number;
  lastScreenshotWidth: number;
  lastScreenshotHeight: number;
  lastScreenshotDurationMs: number;
  lastScreenshotMethod: string;
  lastScreenshotErrorCode: string;
  totalScreenshotsCaptured: number;
  totalScreenshotFailures: number;
  totalDetections: number;
  totalConfirmedInterventions: number;
  // Milestone 2 Classifier & Temporal Decision Telemetry
  modelLoaded: boolean;
  modelId: string;
  modelVersion: string;
  modelSizeBytes: number;
  modelInitDurationMs: number;
  activeDelegate: string;
  inputTensorSpec: string;
  outputTensorSpec: string;
  temporalState: string;
  smoothedExplicitScore: number;
  smoothedNudityScore: number;
  smoothedSafeScore: number;
  totalFramesClassified: number;
  explicitDetections: number;
  nudityDetections: number;
  sexualActivityDetections?: number;
  suggestiveDetections: number;
  nonExplicitDetections: number;
  safeClassifications: number;
  uncertainClassifications: number;
  duplicateFramesSkipped: number;
  obsoleteFramesDropped: number;
  latestClassification: ClassificationResult | null;
  bufferDiagnostics: BufferDiagnostics;
}

export type CompatibilityOutcome =
  | 'UNTESTED'
  | 'SUPPORTED_WINDOW_API'
  | 'SUPPORTED_DISPLAY_API'
  | 'REJECTED_FLAG_SECURE'
  | 'FAILED_OTHER';

export interface AppCompatibilityRecord {
  packageName: string;
  displayName: string;
  category: string;
  outcome: CompatibilityOutcome;
  lastTestedAt: number | null;
  captureMethod: string;
  resolution: string;
  durationMs: number;
  errorCode: string;
  notes: string;
}

export interface DebounceTestResult {
  burstCount: number;
  debounceWindowMs: number;
  acceptedCount: number;
  suppressedCount: number;
  elapsedMs: number;
}

export interface DebugPreviewResponse {
  captured: boolean;
  base64Jpeg: string | null;
  originalWidth: number;
  originalHeight: number;
  previewWidth: number;
  previewHeight: number;
  captureDurationMs: number;
  processingDurationMs: number;
  captureMethod: string;
  packageName: string;
  windowId: number;
  errorCode: string;
  buffersReleased: boolean;
}

export interface EvalSampleMetadata {
  id: string;
  fileName: string;
  split: 'validation' | 'test' | string;
  expectedGroup: string;
  isPositiveGroundTruth: boolean;
  categoryLabel: string;
  description: string;
}

export interface SingleSampleEvalResult {
  sample: EvalSampleMetadata;
  classification: ClassificationResult;
  baselineClassification?: ClassificationResult | null;
  predictedPositive: boolean;
  baselinePredictedPositive?: boolean;
  isCorrectBinary: boolean;
  isFalsePositive: boolean;
  isFalseNegative: boolean;
  previewBase64: string | null;
}

export interface SplitEvaluationMetrics {
  splitName: string;
  totalSamples: number;
  truePositives: number;
  falsePositives: number;
  trueNegatives: number;
  falseNegatives: number;
  uncertainCount?: number;
  uncertainRate?: number;
  precision: number;
  recall: number;
  falsePositiveRate: number;
  falseNegativeRate: number;
  f1Score: number;
  accuracy: number;
}

export interface PreprocessingModeBenchmark {
  modeName: string;
  lowLightSamplesEvaluated: number;
  recallOnLowLightPositives: number;
  fprOnOrdinaryDarkScenes: number;
  uncertainCount: number;
  avgLatencyMs: number;
}

export interface DelegateBenchmarkResult {
  delegateName: string;
  supported: boolean;
  initDurationMs: number;
  avgLatencyMs: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  minLatencyMs: number;
  maxLatencyMs: number;
  error: string | null;
}

export interface FullEvaluationReport {
  modelId: string;
  modelVersion: string;
  modelSizeBytes: number;
  initDurationMs: number;
  inputTensorSpec: string;
  outputTensorSpec: string;
  activeDelegate: string;
  thresholdUsed: number;
  testSplitMetrics: SplitEvaluationMetrics;
  baselineTestSplitMetrics?: SplitEvaluationMetrics;
  validationSplitMetrics: SplitEvaluationMetrics;
  baselineValidationSplitMetrics?: SplitEvaluationMetrics;
  overallMetrics: SplitEvaluationMetrics;
  baselineOverallMetrics?: SplitEvaluationMetrics;
  preprocessingBenchmarks?: PreprocessingModeBenchmark[];
  avgLatencyMs: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  minLatencyMs: number;
  maxLatencyMs: number;
  nativeHeapAllocatedKb: number;
  javaHeapAllocatedKb: number;
  cpuBenchmark: DelegateBenchmarkResult | null;
  nnapiBenchmark: DelegateBenchmarkResult | null;
  sampleResults: SingleSampleEvalResult[];
  timestamp: number;
}
