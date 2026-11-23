import React, {useState} from 'react';
import {
  ActivityIndicator,
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {MetricRow} from '../components/MetricRow';
import {StatusBadge} from '../components/StatusBadge';
import {colors, radius, spacing, typography} from '../theme/theme';
import {
  EvalSampleMetadata,
  FullEvaluationReport,
  MonitoringStatus,
  SingleSampleEvalResult,
} from '../types/screenguard';

interface ClassifierScreenProps {
  status: MonitoringStatus;
  evalSamples: EvalSampleMetadata[];
  selectedSampleResult: SingleSampleEvalResult | null;
  evaluationReport: FullEvaluationReport | null;
  isRunningEvaluation: boolean;
  onInitializeModel: () => Promise<MonitoringStatus | null>;
  onClassifySample: (sampleId: string) => Promise<SingleSampleEvalResult | null>;
  onRunFullEvaluation: (
    includeDelegateBenchmark: boolean,
  ) => Promise<FullEvaluationReport | null>;
}

const RAW_CLASS_KEYS = ['neutral', 'drawings', 'sexy', 'porn', 'hentai'];

export function ClassifierScreen({
  status,
  evalSamples,
  selectedSampleResult,
  evaluationReport,
  isRunningEvaluation,
  onInitializeModel,
  onClassifySample,
  onRunFullEvaluation,
}: ClassifierScreenProps) {
  const [splitFilter, setSplitFilter] = useState<'ALL' | 'test' | 'validation'>(
    'test',
  );

  const visibleSamples = evalSamples.filter(s =>
    splitFilter === 'ALL' ? true : s.split === splitFilter,
  );

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      showsVerticalScrollIndicator={false}>
      {/* 1. Model Initialization & Runtime Verification */}
      <View style={styles.sectionCard}>
        <View style={styles.headerRow}>
          <Text style={styles.sectionTitle}>
            1. LiteRT Model & Tensor Verification
          </Text>
          <StatusBadge
            label={status.modelLoaded ? 'LOADED IN RAM' : 'UNINITIALIZED'}
            tone={status.modelLoaded ? 'success' : 'warning'}
          />
        </View>

        <MetricRow
          label="Model Identifier"
          value={status.modelId}
          mono
        />
        <MetricRow
          label="Model Version & Origin"
          value={status.modelVersion}
          mono
        />
        <MetricRow
          label="Model Asset Size & Init Time"
          value={`${((status.modelSizeBytes || 17251928) / (1024 * 1024)).toFixed(
            2,
          )} MB · ${status.modelInitDurationMs} ms`}
          mono
        />
        <MetricRow
          label="Active Inference Delegate"
          value={status.activeDelegate}
          mono
        />
        <MetricRow
          label="Input Tensor Specification"
          value={status.inputTensorSpec}
          mono
        />
        <MetricRow
          label="Output Tensor Specification"
          value={status.outputTensorSpec}
          mono
          isLast
        />

        <Pressable
          disabled={isRunningEvaluation}
          onPress={onInitializeModel}
          style={[
            styles.outlineBtn,
            isRunningEvaluation && styles.buttonDisabled,
          ]}>
          <Text style={styles.outlineBtnText}>
            Verify / Re-Check LiteRT Model Initialization
          </Text>
        </Pressable>
      </View>

      {/* 2. Single Test Image Classification Inspector */}
      <View style={styles.sectionCard}>
        <View style={styles.headerRow}>
          <Text style={styles.sectionTitle}>
            2. Interactive Test Image Classifier
          </Text>
          {isRunningEvaluation && (
            <ActivityIndicator size="small" color={colors.accent} />
          )}
        </View>
        <Text style={styles.helperText}>
          Select any sample from the bundled validation or held-out test splits
          (including difficult non-explicit swimming, gym/fitness, sports,
          beach, breastfeeding, medical anatomy, fashion, diverse skin tones
          Fitzpatrick I–VI, and positive nudity/explicit test samples) to run
          live on-device LiteRT inference.
        </Text>

        {/* Split Filter Pills */}
        <View style={styles.filterRow}>
          {(['test', 'validation', 'ALL'] as const).map(key => {
            const active = splitFilter === key;
            return (
              <Pressable
                key={key}
                onPress={() => setSplitFilter(key)}
                style={[styles.filterPill, active && styles.filterPillActive]}>
                <Text
                  style={[
                    styles.filterPillText,
                    active && styles.filterPillTextActive,
                  ]}>
                  {key === 'test'
                    ? 'Held-Out Test Split (16)'
                    : key === 'validation'
                    ? 'Validation Split (8)'
                    : 'All Samples (24)'}
                </Text>
              </Pressable>
            );
          })}
        </View>

        {/* Sample Selector List */}
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.sampleHorizontalList}>
          {visibleSamples.map(sample => {
            const isSelected = selectedSampleResult?.sample.id === sample.id;
            return (
              <Pressable
                key={sample.id}
                disabled={isRunningEvaluation}
                onPress={() => onClassifySample(sample.id)}
                style={[
                  styles.sampleCard,
                  isSelected && styles.sampleCardActive,
                ]}>
                <View style={styles.sampleCardTop}>
                  <StatusBadge
                    label={sample.expectedGroup}
                    tone={
                      sample.isPositiveGroundTruth ? 'danger' : 'success'
                    }
                    showDot={false}
                  />
                  <Text style={styles.sampleSplitTag}>{sample.split}</Text>
                </View>
                <Text style={styles.sampleTitle} numberOfLines={1}>
                  {sample.categoryLabel}
                </Text>
                <Text style={styles.sampleDesc} numberOfLines={2}>
                  {sample.description}
                </Text>
                <Text style={styles.sampleAction}>Tap to Run Inference →</Text>
              </Pressable>
            );
          })}
        </ScrollView>

        {/* Selected Sample Result Output */}
        {selectedSampleResult && (
          <View style={styles.resultInspector}>
            <View style={styles.inspectorHeader}>
              <View style={{flex: 1}}>
                <Text style={styles.inspectorTitle}>
                  {selectedSampleResult.sample.categoryLabel} (
                  {selectedSampleResult.sample.id})
                </Text>
                <Text style={styles.inspectorSubtitle}>
                  Ground Truth: {selectedSampleResult.sample.expectedGroup} (
                  {selectedSampleResult.sample.isPositiveGroundTruth
                    ? 'Positive'
                    : 'Non-Explicit / Safe'}
                  )
                </Text>
              </View>
              <StatusBadge
                label={
                  selectedSampleResult.isCorrectBinary
                    ? 'MATCH (CORRECT)'
                    : selectedSampleResult.isFalsePositive
                    ? 'FALSE POSITIVE'
                    : 'FALSE NEGATIVE'
                }
                tone={
                  selectedSampleResult.isCorrectBinary ? 'success' : 'danger'
                }
                showDot={false}
              />
            </View>

            {selectedSampleResult.previewBase64 && (
              <Image
                source={{
                  uri: `data:image/jpeg;base64,${selectedSampleResult.previewBase64}`,
                }}
                style={styles.sampleImage}
                resizeMode="contain"
              />
            )}

            <MetricRow
              label="Predicted Taxonomy Category"
              value={selectedSampleResult.classification.predictedCategory}
              valueColor={
                selectedSampleResult.predictedPositive
                  ? colors.danger
                  : colors.success
              }
              mono
            />
            <MetricRow
              label="Category Confidence"
              value={`${(
                selectedSampleResult.classification.confidence * 100
              ).toFixed(2)}%`}
              mono
            />
            <MetricRow
              label="Inference Latency"
              value={`${selectedSampleResult.classification.processingDurationMs} ms`}
              subValue={`Delegate: ${selectedSampleResult.classification.delegateUsed}`}
              mono
            />

            <Text style={styles.probHeading}>
              Raw 5-Class Softmax Probabilities
            </Text>
            {RAW_CLASS_KEYS.map(key => {
              const prob =
                selectedSampleResult.classification.scores?.[key] ?? 0;
              const pct = Math.min(100, Math.max(0, prob * 100));
              const isHighRiskKey = key === 'porn' || key === 'hentai';
              const isSuggestiveKey = key === 'sexy';
              return (
                <View key={key} style={styles.probRow}>
                  <View style={styles.probLabelRow}>
                    <Text style={styles.probKey}>{key}</Text>
                    <Text style={styles.probVal}>{pct.toFixed(2)}%</Text>
                  </View>
                  <View style={styles.probTrack}>
                    <View
                      style={[
                        styles.probFill,
                        {
                          width: `${pct}%`,
                          backgroundColor: isHighRiskKey
                            ? colors.danger
                            : isSuggestiveKey
                            ? colors.warning
                            : colors.success,
                        },
                      ]}
                    />
                  </View>
                </View>
              );
            })}
          </View>
        )}
      </View>

      {/* 3. Full On-Device Evaluation & False-Positive Benchmark Suite */}
      <View style={styles.sectionCard}>
        <Text style={styles.sectionTitle}>
          3. On-Device Evaluation & False-Positive Benchmark
        </Text>
        <Text style={styles.helperText}>
          Runs all 24 stratified validation and held-out test samples through
          the live LiteRT interpreter on this device, measuring Precision,
          Recall, False-Positive Rate (FPR), F1 Score, Confusion Matrix,
          Avg/P50/P95 Latency, and CPU vs NNAPI hardware acceleration.
        </Text>

        <Pressable
          disabled={isRunningEvaluation}
          onPress={() => onRunFullEvaluation(true)}
          style={[
            styles.primaryEvalBtn,
            isRunningEvaluation && styles.buttonDisabled,
          ]}>
          <Text style={styles.primaryEvalBtnText}>
            {isRunningEvaluation
              ? 'Running 24-Sample LiteRT Benchmark...'
              : 'Run Full Evaluation & CPU/NNAPI Benchmark'}
          </Text>
        </Pressable>

        {evaluationReport && (
          <View style={styles.reportBlock}>
            <Text style={styles.reportSubheading}>
              Held-Out Test Split Metrics (n ={' '}
              {evaluationReport.testSplitMetrics.totalSamples})
            </Text>
            <MetricRow
              label="Confusion Matrix (TP / FP / TN / FN)"
              value={`TP ${evaluationReport.testSplitMetrics.truePositives} · FP ${evaluationReport.testSplitMetrics.falsePositives} · TN ${evaluationReport.testSplitMetrics.trueNegatives} · FN ${evaluationReport.testSplitMetrics.falseNegatives}`}
              mono
            />
            <MetricRow
              label="Precision / Recall"
              value={`Precision ${(
                evaluationReport.testSplitMetrics.precision * 100
              ).toFixed(1)}% · Recall ${(
                evaluationReport.testSplitMetrics.recall * 100
              ).toFixed(1)}%`}
              mono
            />
            <MetricRow
              label="False-Positive Rate (FPR)"
              value={`${(
                evaluationReport.testSplitMetrics.falsePositiveRate * 100
              ).toFixed(1)}% (${
                evaluationReport.testSplitMetrics.falsePositives
              } / ${
                evaluationReport.testSplitMetrics.falsePositives +
                evaluationReport.testSplitMetrics.trueNegatives
              } non-explicit)`}
              valueColor={
                evaluationReport.testSplitMetrics.falsePositiveRate === 0
                  ? colors.success
                  : colors.warning
              }
              mono
            />
            <MetricRow
              label="False-Negative Rate (FNR)"
              value={`${(
                evaluationReport.testSplitMetrics.falseNegativeRate * 100
              ).toFixed(1)}%`}
              mono
            />
            <MetricRow
              label="F1 Score / Overall Accuracy"
              value={`F1 ${evaluationReport.testSplitMetrics.f1Score.toFixed(
                3,
              )} · Acc ${(
                evaluationReport.testSplitMetrics.accuracy * 100
              ).toFixed(1)}%`}
              mono
            />

            <Text style={[styles.reportSubheading, {marginTop: spacing.md}]}>
              Validation Split (n ={' '}
              {evaluationReport.validationSplitMetrics.totalSamples}) & Latency
            </Text>
            <MetricRow
              label="Validation Split (TP / FP / TN / FN)"
              value={`TP ${evaluationReport.validationSplitMetrics.truePositives} · FP ${evaluationReport.validationSplitMetrics.falsePositives} · TN ${evaluationReport.validationSplitMetrics.trueNegatives} · FN ${evaluationReport.validationSplitMetrics.falseNegatives} (F1 ${evaluationReport.validationSplitMetrics.f1Score.toFixed(
                3,
              )})`}
              mono
            />
            <MetricRow
              label="Inference Latency (Avg / P50 / P95)"
              value={`Avg ${evaluationReport.avgLatencyMs.toFixed(
                1,
              )} ms · P50 ${evaluationReport.p50LatencyMs} ms · P95 ${
                evaluationReport.p95LatencyMs
              } ms`}
              subValue={`Min: ${evaluationReport.minLatencyMs} ms · Max: ${evaluationReport.maxLatencyMs} ms`}
              mono
            />
            <MetricRow
              label="Memory Footprint (Native / Java Heap)"
              value={`Native ${(
                evaluationReport.nativeHeapAllocatedKb / 1024
              ).toFixed(1)} MB · Java ${(
                evaluationReport.javaHeapAllocatedKb / 1024
              ).toFixed(1)} MB`}
              mono
            />

            {evaluationReport.cpuBenchmark && (
              <MetricRow
                label={`Delegate: ${evaluationReport.cpuBenchmark.delegateName}`}
                value={`Avg ${evaluationReport.cpuBenchmark.avgLatencyMs.toFixed(
                  1,
                )} ms · P50 ${
                  evaluationReport.cpuBenchmark.p50LatencyMs
                } ms · P95 ${evaluationReport.cpuBenchmark.p95LatencyMs} ms`}
                subValue={`Init: ${evaluationReport.cpuBenchmark.initDurationMs} ms`}
                mono
              />
            )}

            {evaluationReport.nnapiBenchmark && (
              <MetricRow
                label={`Delegate: ${evaluationReport.nnapiBenchmark.delegateName}`}
                value={
                  evaluationReport.nnapiBenchmark.supported
                    ? `Avg ${evaluationReport.nnapiBenchmark.avgLatencyMs.toFixed(
                        1,
                      )} ms · P50 ${
                        evaluationReport.nnapiBenchmark.p50LatencyMs
                      } ms · P95 ${
                        evaluationReport.nnapiBenchmark.p95LatencyMs
                      } ms`
                    : `Unsupported (${evaluationReport.nnapiBenchmark.error})`
                }
                subValue={
                  evaluationReport.nnapiBenchmark.supported
                    ? `Init: ${evaluationReport.nnapiBenchmark.initDurationMs} ms`
                    : undefined
                }
                mono
                isLast
              />
            )}
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
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.sm,
    gap: spacing.sm,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: colors.textSecondary,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    flex: 1,
  },
  helperText: {
    fontSize: 12.5,
    color: colors.textSecondary,
    lineHeight: 18,
    marginBottom: spacing.md,
  },
  outlineBtn: {
    marginTop: spacing.md,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.borderStrong,
    paddingVertical: 11,
    alignItems: 'center',
  },
  outlineBtnText: {
    fontSize: 12.5,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  buttonDisabled: {
    opacity: 0.55,
  },
  filterRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs + 2,
    marginBottom: spacing.md,
  },
  filterPill: {
    paddingHorizontal: spacing.sm + 4,
    paddingVertical: 6,
    borderRadius: radius.pill,
    backgroundColor: colors.surfaceSubtle,
    borderWidth: 1,
    borderColor: colors.border,
  },
  filterPillActive: {
    backgroundColor: colors.accentSoft,
    borderColor: colors.accentBorder,
  },
  filterPillText: {
    fontSize: 11.5,
    fontWeight: '600',
    color: colors.textSecondary,
  },
  filterPillTextActive: {
    color: '#60A5FA',
  },
  sampleHorizontalList: {
    gap: spacing.sm,
    paddingBottom: spacing.sm,
  },
  sampleCard: {
    width: 190,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.md,
  },
  sampleCardActive: {
    borderColor: colors.accentBorder,
    backgroundColor: colors.accentSoft,
  },
  sampleCardTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 6,
  },
  sampleSplitTag: {
    fontSize: 10,
    color: colors.textMuted,
    fontFamily: typography.mono,
    textTransform: 'uppercase',
  },
  sampleTitle: {
    fontSize: 13.5,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: 3,
  },
  sampleDesc: {
    fontSize: 11.5,
    color: colors.textSecondary,
    lineHeight: 16,
    height: 32,
  },
  sampleAction: {
    fontSize: 11,
    fontWeight: '700',
    color: '#60A5FA',
    marginTop: spacing.sm,
  },
  resultInspector: {
    marginTop: spacing.md,
    backgroundColor: colors.surfaceSubtle,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.accentBorder,
    padding: spacing.md,
  },
  inspectorHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: spacing.sm,
    gap: spacing.sm,
  },
  inspectorTitle: {
    fontSize: 14,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  inspectorSubtitle: {
    fontSize: 11.5,
    color: colors.textSecondary,
    marginTop: 2,
  },
  sampleImage: {
    width: '100%',
    height: 170,
    borderRadius: radius.sm,
    backgroundColor: '#05070A',
    marginVertical: spacing.sm,
  },
  probHeading: {
    fontSize: 12,
    fontWeight: '700',
    color: colors.textSecondary,
    textTransform: 'uppercase',
    marginTop: spacing.md,
    marginBottom: spacing.sm,
  },
  probRow: {
    marginBottom: spacing.sm,
  },
  probLabelRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 3,
  },
  probKey: {
    fontSize: 12,
    fontWeight: '600',
    color: colors.textPrimary,
    fontFamily: typography.mono,
  },
  probVal: {
    fontSize: 12,
    color: colors.textSecondary,
    fontFamily: typography.mono,
  },
  probTrack: {
    height: 7,
    backgroundColor: colors.border,
    borderRadius: 4,
    overflow: 'hidden',
  },
  probFill: {
    height: '100%',
    borderRadius: 4,
  },
  primaryEvalBtn: {
    backgroundColor: colors.accent,
    borderRadius: radius.md,
    paddingVertical: 13,
    paddingHorizontal: spacing.md,
    alignItems: 'center',
  },
  primaryEvalBtnText: {
    fontSize: 13.5,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  reportBlock: {
    marginTop: spacing.md,
    paddingTop: spacing.sm,
    borderTopWidth: 1,
    borderTopColor: colors.border,
  },
  reportSubheading: {
    fontSize: 12,
    fontWeight: '700',
    color: '#60A5FA',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    marginBottom: spacing.xs,
  },
});
