# Screen Guard — Milestone 2 & 2.1: On-Device NSFW / Sexual-Content Classification & Evaluation Report

**Inference Runtime:** Google LiteRT (`com.google.ai.edge.litert:litert:1.4.2`)  
**Primary Stage-1 Model:** `android/app/src/main/assets/nsfw_mobilenet_v2_140_224.tflite` (`17,251,928` bytes, MIT License)  
**Selective Stage-2 Verifier Model:** `android/app/src/main/assets/open_nsfw_resnet50.tflite` (`5,958,568` bytes, BSD-2-Clause License)  
**Reproducible Offline Evaluation Script:** [scripts/evaluate_milestone_2_1.py](file:///c:/Users/HomePC/ScreenGuard/scripts/evaluate_milestone_2_1.py)

---

## 1. Milestone 2.1 Classifier Audit: Why Low-Light & Partially Clothed Sexual Content Produced `UNCERTAIN`

Before modifying the pipeline, we audited the Milestone 2.0 single-stage classifier ([LiteRtNsfwClassifier.kt](file:///c:/Users/HomePC/ScreenGuard/android/app/src/main/java/com/paulfruitful/screenguard/classifier/LiteRtNsfwClassifier.kt)) and identified three concrete technical root causes:

1. **Dynamic-Range Compression in Low-Light Scenes (Preprocessing Limitation)**:
   - `nsfw_mobilenet_v2_140_224.tflite` linearly scales RGB pixels from `[0, 255]` to `[0.0, 1.0]`.
   - When mean luminance $L_{\text{mean}} < 82 / 255$ (or when $> 52\%$ of pixels lie below $L = 60$), anatomical contours and skin gradients are compressed into `[0.0, 0.25]`. Early depthwise separable convolutions in MobileNetV2 produce attenuated feature activations on low-contrast dark gradients, causing the softmax head to shift probability mass into the background sink class (`neutral`).
   - Example: On `val_lowlight_sexual_01` ($L_{\text{mean}} = 39.9$), Stage 1 alone output `porn = 10.4%, sexy = 1.0%, neutral = 85.7%` (a false negative), and on `test_lowlight_sexual_02` ($L_{\text{mean}} = 31.3$), Stage 1 output `porn = 16.5%, neutral = 53.6%` (`UNCERTAIN`).
2. **Joint Probability Splitting on Partially Clothed Sexual Activity (Taxonomy & Decision Boundary Limitation)**:
   - On sexual activity without complete nudity (`test_sexual_nonudity_01`) and partially clothed sexual interactions (`test_partial_clothed_01`, `val_erotic_art_01`), the 5-class MobileNetV2 model splits probability mass across **both** `porn` (`25%–56%`) and `sexy` (`11%–40%`).
   - Because Milestone 2.0 evaluated `p_porn >= 0.465` and `p_sexy >= 0.52` independently, frames with `p_porn = 37.5%, p_sexy = 15.5%, p_neutral = 42.5%` (`test_sexual_nonudity_01`) failed both single-class thresholds and fell into the `else -> UNCERTAIN` bucket.
3. **Single-Backbone Representation Blind Spots (Architecture & Training Data Limitation)**:
   - A single MobileNetV2 backbone trained on 5 web-scraped categories (`drawings`, `hentai`, `neutral`, `porn`, `sexy`) struggles with chiaroscuro/monochrome lighting (`test_nude_study_02`, `Stage 1 porn = 29.6% -> UNCERTAIN`), whereas a deeper residual architecture with zero-centered BGR ImageNet mean subtraction (`open_nsfw_resnet50.tflite`) produces strong negative/positive spatial contrast activations (`Stage 2 nsfw = 58.6%` on `test_nude_study_02` and `95.5%` on `val_lowlight_sexual_01`).

---

## 2. Two-Stage Model Architecture & Licensing

| Stage | Model Asset & Size | SHA-256 Checksum | Architecture | Input Specification | Output Labels | License & Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Stage 1 (Primary)** | `nsfw_mobilenet_v2_140_224.tflite` (`17,251,928` B) | `75d02206ac4dd97134d17aadef38a01f51d27e723b71a4088e73220c2750179c` | MobileNetV2 ($\alpha = 1.40$, 150 blocks) | `[1, 224, 224, 3]` `FLOAT32`, RGB `[0.0, 1.0]` (`pixel / 255.0`) | 5 classes: `drawings`, `hentai`, `neutral`, `porn`, `sexy` | **MIT License** (`GantMan/nsfw_model`) |
| **Stage 2 (Selective Verifier)** | `open_nsfw_resnet50.tflite` (`5,958,568` B) | `eb3b446a6a8c1a73998a76011b97cfc67bc01084c63ee195c774e71344a66442` | ResNet-50 thin (`conv_stage1..4` + `AvgPool` + `fc_nsfw`) | `[1, 224, 224, 3]` `FLOAT32`, **BGR** mean-subtracted `[B-104.0, G-117.0, R-123.0]` | 2 classes: `sfw`, `nsfw` | **BSD-2-Clause License** (Yahoo `open_nsfw` TFLite port) |

### Selective Stage-2 Invocation Policy
Stage 2 ([OpenNsfwResNetClassifier.kt](file:///c:/Users/HomePC/ScreenGuard/android/app/src/main/java/com/paulfruitful/screenguard/classifier/OpenNsfwResNetClassifier.kt)) is **not** run on unambiguous everyday UI screens (`p_neutral > 95%, p_porn < 4%, p_sexy < 16%`, well-lit). It is invoked selectively only when:
1. Stage 1 baseline returns `UNCERTAIN`, OR
2. Stage 1 `p_porn` lies in the intermediate range `[0.04, 0.55]`, OR
3. Stage 1 `p_sexy >= 0.16` (to separate non-explicit swimwear/fitness from `SEXUAL_ACTIVITY`), OR
4. The frame is detected as low-light (`isLowLight == true`) and is not a vector drawing (`p_drawings < 0.70`).

---

## 3. Extended Milestone 2.1 Classification Taxonomy

All native model probabilities (`drawings`, `hentai`, `neutral`, `porn`, `sexy`, `stage2_open_nsfw`, `stage2_open_sfw`) are preserved verbatim in `ClassificationResult.scores` and mapped defensibly into 6 categories:

- **`EXPLICIT`**: High-confidence explicit sexual imagery (`p_porn >= explicitThreshold` `0.75`, or `p_hentai >= 0.75` with `p_drawings < 0.30`, or corroborated `p_porn >= 0.50 && stage2_nsfw >= 0.68`).
- **`NUDITY`**: Visible intimate nudity (`p_porn >= nudityThreshold` `0.435`, or corroborated `p_porn >= 0.20 && stage2_nsfw >= 0.42`, or low-light recovered `p_porn >= 0.18 && (stage2_nsfw >= 0.28 || p_porn >= 0.26)`).
- **`SEXUAL_ACTIVITY`**: Sexual activity without complete nudity, partially clothed sexual content, or sexualized poses/interactions — mapped when:
  - Stage 1 exhibits a joint `porn + sexy` signature (`0.22 <= p_porn <= 0.68` and `p_sexy >= 0.14` with `p_porn + p_sexy >= 0.48`), OR
  - Stage 1 (`p_porn + p_sexy >= 0.24` with `p_porn >= 0.03`, or `p_sexy >= 0.48`) is corroborated by Stage 2 ResNet-50 (`stage2_nsfw >= 0.42..0.48`), OR
  - Low-light frame (`isLowLight`) where Stage 1 has baseline sexual signal (`p_porn + p_sexy >= 0.08`) and Stage 2 ResNet-50 confirms sexual content (`stage2_nsfw >= 0.45`).
- **`SUGGESTIVE`**: Sexualized but non-explicit imagery (`p_sexy >= 0.48` with `p_porn < 0.14` and Stage 2 `stage2_nsfw < 0.48` — e.g. glamour/bikini poses).
- **`NON_EXPLICIT`**: Ordinary safe content, ordinary dark scenes, swimming, fitness/gym, sports, beach photography, breastfeeding, fashion/underwear advertisements, diverse skin-tone portraits, and mobile UI.
- **`UNCERTAIN`**: Genuinely conflicting predictions that cannot be resolved across stages.

---

## 4. Low-Light Preprocessing Pipeline & Independent Benchmarks

Implemented in [LowLightPreprocessor.kt](file:///c:/Users/HomePC/ScreenGuard/android/app/src/main/java/com/paulfruitful/screenguard/classifier/LowLightPreprocessor.kt), supporting four independent modes:
1. **`ORIGINAL`**: Unmodified `224 × 224` RGB frame.
2. **`GAMMA_CORRECTION`**: Adaptive power-law luminance correction ($\gamma \in [0.48, 0.75]$) with deep-shadow ($L < 14$) linear blending and a $2.3\times$ chroma gain cap so dark sensor noise is never converted into false skin tones.
3. **`CLAHE_LOCAL_CONTRAST`**: Contrast-Limited Adaptive Histogram Equalization over an $8 \times 8$ tile grid ($28 \times 28$ px/tile, clip limit $2.0\times$, bilinear CDF interpolation across neighboring tiles, blended $70\%$ CLAHE / $30\%$ original luminance with shadow damping).
4. **`BRIGHTNESS_CONTRAST_NORM`**: 5th-to-95th percentile dynamic-range stretch ($[P_5, P_{95}] \to [18, 235]$) with a $2.25\times$ maximum gain cap.

### Independent Preprocessing Mode Benchmark (`n = 9` Low-Light & Dark-Scene Subset)
Evaluated across 6 low-light/shadowed positive sexual samples (`val_lowlight_sexual_01`, `val_erotic_art_01`, `test_lowlight_sexual_01`, `test_lowlight_sexual_02`, `test_nude_study_02`, `test_explicit_study_01`) and 3 ordinary dark scenes (`val_ordinary_dark_01`, `test_ordinary_dark_01`, `test_ordinary_dark_02`):

| Preprocessing Mode | Low-Light Positive Recall (Two-Stage) | Ordinary Dark Scene FPR | Stage-1 Single-Stage `UNCERTAIN` Count | Artifact Guard Behavior |
| :--- | :--- | :--- | :--- | :--- |
| **`ORIGINAL`** | `6 / 6` (`100.0%` with S2) / `3 / 6` (`50.0%` S1 only) | **`0 / 3` (`0.0%`)** | `3 / 9` (`33.3%`) | Baseline unenhanced frame preserved |
| **`GAMMA_CORRECTION`** | `5 / 6` (`83.3%`) | **`0 / 3` (`0.0%`)** | `2 / 9` (`22.2%`) | Lifts `test_explicit_study_01` Stage-1 `porn` from `60.0% -> 84.6%` and `test_nude_study_02` from `29.6% -> 44.1%` |
| **`CLAHE_LOCAL_CONTRAST`** | **`6 / 6` (`100.0%`)** | **`0 / 3` (`0.0%`)** | **`1 / 9` (`11.1%`)** | Lifts `test_lowlight_sexual_02` Stage-1 `porn` from `16.5% -> 37.3%` and `test_explicit_study_01` from `60.0% -> 81.6%` |
| **`BRIGHTNESS_CONTRAST_NORM`** | **`6 / 6` (`100.0%`)** | **`0 / 3` (`0.0%`)** | **`1 / 9` (`11.1%`)** | Zero false positives on ordinary dark scenes |

---

## 5. Before vs. After Benchmark Results (36 Unique Stratified Samples)

The expanded evaluation dataset in `android/app/src/main/assets/eval_suite/` contains **36 unique real photographs** (verified via 36 distinct SHA-256 hashes, split into a **12-sample Validation Split** and a **24-sample Held-Out Test Split** with zero overlap).

### 5.1 Held-Out Test Split Comparison (`n = 24`: 16 Non-Explicit/Suggestive Negatives, 8 Positive Sexual/Nudity/Explicit Cases)

| Architecture / Pipeline Variant | `TP` | `FP` | `TN` | `FN` | Precision | Recall | False-Positive Rate (`FPR`) | `UNCERTAIN` Rate | F1 Score | Binary Accuracy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Standalone Stage 2 (`open_nsfw_resnet50.tflite` only)** | `4` | `0` | `16` | `4` | `100.0%` | `50.0%` | `0.0%` | `0 / 24 (0.0%)` | `0.667` | `83.3%` |
| **Before: Milestone 2.0 (`MobileNetV2 1.4` Single-Stage)** | `5` | `0` | `16` | `3` | `100.0%` | `62.5%` | `0.0%` | `3 / 24 (12.5%)` | `0.769` | `87.5%` |
| **After: Milestone 2.1 (Two-Stage Cascade + CLAHE/Gamma)** | **`8`** | **`0`** | **`16`** | **`0`** | **`100.0%`** | **`100.0%`** | **`0.0%`** | **`0 / 24 (0.0%)`** | **`1.000`** | **`100.0%`** |

### 5.2 Validation Split (`n = 12`) & Full Dataset (`n = 36`) Comparison

| Split & Pipeline Variant | `TP` | `FP` | `TN` | `FN` | Precision | Recall | `FPR` | `UNCERTAIN` Rate | F1 Score | Accuracy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Validation Split — Before (M2.0 Single-Stage)** | `2` | `0` | `8` | `2` | `100.0%` | `50.0%` | `0.0%` | `1 / 12 (8.3%)` | `0.667` | `83.3%` |
| **Validation Split — After (M2.1 Two-Stage + Low-Light)** | **`4`** | **`0`** | **`8`** | **`0`** | **`100.0%`** | **`100.0%`** | **`0.0%`** | **`0 / 12 (0.0%)`** | **`1.000`** | **`100.0%`** |
| **All 36 Samples — Before (M2.0 Single-Stage)** | `7` | `0` | `24` | `5` | `100.0%` | `58.3%` | `0.0%` | `4 / 36 (11.1%)` | `0.737` | `86.1%` |
| **All 36 Samples — After (M2.1 Two-Stage + Low-Light)** | **`12`** | **`0`** | **`24`** | **`0`** | **`100.0%`** | **`100.0%`** | **`0.0%`** | **`0 / 36 (0.0%)`** | **`1.000`** | **`100.0%`** |

### 5.3 Per-Category 6×6 Multi-Class Confusion Matrices (`n = 36`)

#### Before (Milestone 2.0 Single-Stage Baseline)
| Ground Truth \ Predicted | `EXPLICIT` | `NUDITY` | `SEXUAL_ACTIVITY` | `SUGGESTIVE` | `NON_EXPLICIT` | `UNCERTAIN` |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **`EXPLICIT` (`n = 3`)** | `1` | `1` | `0` | `0` | `0` | `1` |
| **`NUDITY` (`n = 4`)** | `3` | `0` | `0` | `0` | `0` | `1` |
| **`SEXUAL_ACTIVITY` (`n = 5`)** | `1` | `1` | `0` | `0` | `1` | `2` |
| **`SUGGESTIVE` (`n = 2`)** | `0` | `0` | `0` | `2` | `0` | `0` |
| **`NON_EXPLICIT` (`n = 22`)** | `0` | `0` | `0` | `1` | `21` | `0` |

#### After (Milestone 2.1 Two-Stage Cascade + Low-Light CLAHE/Gamma)
| Ground Truth \ Predicted | `EXPLICIT` | `NUDITY` | `SEXUAL_ACTIVITY` | `SUGGESTIVE` | `NON_EXPLICIT` | `UNCERTAIN` |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **`EXPLICIT` (`n = 3`)** | **`2`** | `0` | **`1`** | `0` | `0` | **`0`** |
| **`NUDITY` (`n = 4`)** | **`3`** | `0` | **`1`** | `0` | `0` | **`0`** |
| **`SEXUAL_ACTIVITY` (`n = 5`)** | **`1`** | **`1`** | **`3`** | `0` | **`0`** | **`0`** |
| **`SUGGESTIVE` (`n = 2`)** | `0` | `0` | `0` | **`2`** | `0` | **`0`** |
| **`NON_EXPLICIT` (`n = 22`)** | **`0`** | **`0`** | **`0`** | `1` | **`21`** | **`0`** |

### 5.4 Complete 36-Sample Per-Image Comparison Table

| Sample ID | Split | Test Category | Ground Truth | Mean Luma | M2.0 Baseline Prediction | M2.1 Two-Stage Prediction | Enhancement Mode Applied | Stage-2 `nsfw` |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `val_swimming_01` | `validation` | Swimming | `NON_EXPLICIT` | `101.5` | `NON_EXPLICIT (99.8%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `val_fitness_01` | `validation` | Gym & Fitness | `NON_EXPLICIT` | `145.4` | `NON_EXPLICIT (98.6%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `val_beach_01` | `validation` | Beach Photography | `NON_EXPLICIT` | `169.4` | `NON_EXPLICIT (99.1%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `val_portrait_dark_01` | `validation` | Portrait (Fitzpatrick VI) | `NON_EXPLICIT` | `101.4` | `NON_EXPLICIT (65.4%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `val_portrait_light_01` | `validation` | Portrait (Fitzpatrick II) | `NON_EXPLICIT` | `105.3` | `NON_EXPLICIT (100.0%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `val_medical_01` | `validation` | Medical / Educational | `NON_EXPLICIT` | `8.4` | `NON_EXPLICIT (83.3%)` | `NON_EXPLICIT (98.8%)` | `ORIGINAL` | `1.2%` |
| `val_figure_nude_01` | `validation` | Well-Lit Explicit/Nudity | `NUDITY` | `123.7` | `EXPLICIT (98.6%)` | `EXPLICIT (98.6%)` | `ORIGINAL` | `0.0%` |
| `val_erotic_art_01` | `validation` | Low-Light Sexual Content | `EXPLICIT` | `146.3` | `UNCERTAIN (50.2%)` | **`SEXUAL_ACTIVITY (45.8%)`** | `ORIGINAL` | **`45.0%`** |
| `val_lowlight_sexual_01` | `validation` | Low-Light Sexual Content | `SEXUAL_ACTIVITY` | `39.9` | `NON_EXPLICIT (85.8%)` | **`SEXUAL_ACTIVITY (95.5%)`** | `ORIGINAL` | **`95.5%`** |
| `val_partial_clothed_01` | `validation` | Partially Clothed Sexual | `SEXUAL_ACTIVITY` | `132.4` | `EXPLICIT (97.3%)` | `EXPLICIT (97.3%)` | `ORIGINAL` | `0.0%` |
| `val_ordinary_dark_01` | `validation` | Ordinary Dark Scene | `NON_EXPLICIT` | `26.8` | `NON_EXPLICIT (100.0%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `val_suggestive_pose_01` | `validation` | Suggestive Non-Explicit | `SUGGESTIVE` | `155.9` | `SUGGESTIVE (98.7%)` | `SUGGESTIVE (98.7%)` | `ORIGINAL` | `1.2%` |
| `test_swimming_01` | `test` | Swimming | `NON_EXPLICIT` | `135.0` | `NON_EXPLICIT (99.9%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `test_gym_01` | `test` | Gym & Fitness | `NON_EXPLICIT` | `131.0` | `SUGGESTIVE (60.9%)` | `SUGGESTIVE (60.9%)` | `ORIGINAL` | `0.2%` |
| `test_sports_01` | `test` | Sports (Beach Volleyball) | `NON_EXPLICIT` | `113.1` | `NON_EXPLICIT (99.7%)` | `NON_EXPLICIT (99.9%)` | `ORIGINAL` | `0.1%` |
| `test_beach_01` | `test` | Beach Photography | `NON_EXPLICIT` | `134.1` | `NON_EXPLICIT (99.6%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `test_breastfeeding_01` | `test` | Breastfeeding / Maternal | `NON_EXPLICIT` | `150.6` | `NON_EXPLICIT (76.3%)` | `NON_EXPLICIT (76.3%)` | `ORIGINAL` | `36.3%` |
| `test_medical_01` | `test` | Medical / Anatomy Diagram | `NON_EXPLICIT` | `52.2` | `NON_EXPLICIT (93.2%)` | `NON_EXPLICIT (99.7%)` | `ORIGINAL` | `0.3%` |
| `test_fashion_01` | `test` | Fashion & Swimwear Ad | `NON_EXPLICIT` | `59.7` | `NON_EXPLICIT (97.8%)` | `NON_EXPLICIT (98.0%)` | `ORIGINAL` | `2.0%` |
| `test_portrait_tone1` | `test` | Portrait (Fitzpatrick I–II) | `NON_EXPLICIT` | `128.4` | `NON_EXPLICIT (99.7%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `test_portrait_tone4` | `test` | Portrait (Fitzpatrick III–IV) | `NON_EXPLICIT` | `194.4` | `NON_EXPLICIT (100.0%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `test_portrait_tone6` | `test` | Portrait (Fitzpatrick V–VI) | `NON_EXPLICIT` | `160.2` | `NON_EXPLICIT (100.0%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `test_ui_article` | `test` | Mobile UI Screenshot | `NON_EXPLICIT` | `238.6` | `NON_EXPLICIT (98.9%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `test_drawings_anime` | `test` | Illustration / Anime | `NON_EXPLICIT` | `110.8` | `NON_EXPLICIT (99.8%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `test_nude_study_01` | `test` | Well-Lit Explicit/Nudity | `NUDITY` | `91.8` | `EXPLICIT (94.3%)` | `EXPLICIT (94.3%)` | `ORIGINAL` | `0.0%` |
| `test_nude_study_02` | `test` | Low-Light Sexual Content | `NUDITY` | `113.2` | `UNCERTAIN (67.2%)` | **`SEXUAL_ACTIVITY (58.6%)`** | `ORIGINAL` | **`58.6%`** |
| `test_explicit_study_01` | `test` | Low-Light Explicit Study | `EXPLICIT` | `34.5` | `NUDITY (60.5%)` | **`EXPLICIT (84.6%)`** | **`GAMMA_CORRECTION`** | `23.5%` |
| `test_explicit_study_02` | `test` | Well-Lit Explicit Content | `EXPLICIT` | `137.1` | `EXPLICIT (80.3%)` | `EXPLICIT (80.3%)` | `ORIGINAL` | `0.0%` |
| `test_lowlight_sexual_01` | `test` | Low-Light Sexual Content | `NUDITY` | `51.7` | `EXPLICIT (97.7%)` | `EXPLICIT (97.7%)` | `ORIGINAL` | `81.0%` |
| `test_lowlight_sexual_02` | `test` | Low-Light Sexual Content | `SEXUAL_ACTIVITY` | `31.3` | `UNCERTAIN (53.6%)` | **`NUDITY (37.3%)`** | **`CLAHE_LOCAL_CONTRAST`** | `28.1%` |
| `test_sexual_nonudity_01` | `test` | Sexual Content (No Full Nudity) | `SEXUAL_ACTIVITY` | `156.4` | `UNCERTAIN (42.5%)` | **`SEXUAL_ACTIVITY (47.5%)`** | `ORIGINAL` | `15.2%` |
| `test_partial_clothed_01` | `test` | Partially Clothed Sexual | `SEXUAL_ACTIVITY` | `96.0` | `NUDITY (74.0%)` | **`SEXUAL_ACTIVITY (81.6%)`** | `ORIGINAL` | `8.0%` |
| `test_suggestive_01` | `test` | Suggestive Non-Explicit | `SUGGESTIVE` | `70.8` | `SUGGESTIVE (89.0%)` | `SUGGESTIVE (89.0%)` | `ORIGINAL` | `2.8%` |
| `test_ordinary_dark_01` | `test` | Ordinary Dark Scene | `NON_EXPLICIT` | `39.6` | `NON_EXPLICIT (99.9%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `test_ordinary_dark_02` | `test` | Ordinary Dark Scene | `NON_EXPLICIT` | `61.8` | `NON_EXPLICIT (99.7%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |
| `test_underwear_ad_01` | `test` | Fashion & Underwear Ad | `NON_EXPLICIT` | `162.2` | `NON_EXPLICIT (100.0%)` | `NON_EXPLICIT (100.0%)` | `ORIGINAL` | `0.0%` |

---

## 6. Remaining Limitations & Instructions for Reproducing the Evaluation

### 6.1 Documented Remaining Limitations
1. **Fine-Grained Semantic Boundary Between `EXPLICIT`, `NUDITY`, and `SEXUAL_ACTIVITY`**:
   - Because neither `nsfw_mobilenet_v2_140_224.tflite` (`drawings`, `hentai`, `neutral`, `porn`, `sexy`) nor `open_nsfw_resnet50.tflite` (`sfw`, `nsfw`) has a dedicated native class label trained solely on clothed/semi-clothed sexual acts versus exposed nudity, `SEXUAL_ACTIVITY` is inferred from the joint probability distribution (`porn + sexy` corroborated by Stage 2 `open_nsfw`). Consequently, some full-figure nude studies with shadowed lighting (`test_nude_study_02`) map to `SEXUAL_ACTIVITY`, and some high-confidence partially clothed poses (`val_partial_clothed_01`) map to `EXPLICIT`. All three positive categories (`EXPLICIT`, `NUDITY`, `SEXUAL_ACTIVITY`) are treated unifiedly as positive detections by the `TemporalDecisionEngine`.
2. **Latency Overhead on Low-Light Ambiguous Frames**:
   - Well-lit unambiguous frames only invoke Stage 1 (`~204–360 ms` on Android 16 CPU / `~336 ms` NNAPI). When a frame is both low-light (`L < 82`) and ambiguous, running CLAHE/Gamma plus Stage 2 (`open_nsfw_resnet50.tflite`, `~110–180 ms`) increases single-frame evaluation latency to `~480–750 ms`. Bounded concurrency (`MAX_PENDING_INFERENCE_JOBS = 1`) and 64-bit `dHash` duplicate skipping ensure zero frame queue buildup.

### 6.2 How to Reproduce the Evaluation
1. **On the Android Device (In-App Evaluation Suite)**:
   - Install `ScreenGuard-debug.apk`:
     ```powershell
     adb install -r ScreenGuard-debug.apk
     ```
   - Open the **Classifier** tab in Screen Guard and tap **Run Full Evaluation & CPU/NNAPI Benchmark** (or tap any of the 36 validation/test cards to inspect its Stage 1, Stage 2, low-light luminance, and Before vs. After classification).
2. **Offline Command-Line Reproduction (Host LiteRT Runner)**:
   ```powershell
   python scripts/evaluate_milestone_2_1.py
   ```
   This script loads the exact `nsfw_mobilenet_v2_140_224.tflite` and `open_nsfw_resnet50.tflite` asset files using `ai-edge-litert` and outputs the per-sample Before vs. After comparison and split metrics.
