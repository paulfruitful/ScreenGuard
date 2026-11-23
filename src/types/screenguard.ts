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
  | 'LOG_ONLY'
  | 'STATUS_NOTIFICATION'
  | 'WARNING_BANNER_FUTURE';

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

export type ScreenGuardEvent =
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
    };

export type ActivityEventType =
  | 'APP_DETECTED'
  | 'SCREENSHOT_SUCCESS'
  | 'SCREENSHOT_FAILURE'
  | 'CLASSIFICATION_COMPLETED'
  | 'SUSPICIOUS_DETECTED'
  | 'SERVICE_STATE';

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

export interface UserPreferences {
  protectionEnabled: boolean;
  excludedPackages: string[];
  samplingIntervalMs: number;
  debounceDelayMs: number;
  preferWindowCapture: boolean;
  confidenceThreshold: number;
  confirmationFramesRequired: number;
  interventionBehavior: InterventionBehavior;
  mockClassifierProfile: MockClassifierProfile;
  allowSelfPackageInDiagnostics: boolean;
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
  serviceConnected: boolean;
  protectionEnabled: boolean;
  monitoringActive: boolean;
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
