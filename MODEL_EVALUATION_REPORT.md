# Screen Guard — Milestone 2: On-Device NSFW Model Evaluation & Performance Report

**Target Device Tested:** Physical Android 16 (API 36) Device (`24117RN76G`, ADB ID `r4qclfdyxge6uosc`)  
**Inference Runtime:** Google LiteRT (`com.google.ai.edge.litert:litert:1.4.2`)  
**Model File:** `android/app/src/main/assets/nsfw_mobilenet_v2_140_224.tflite`  
**File Size:** `17,251,928` bytes (`16.45 MiB`, memory-mapped via uncompressed APK asset)  
**SHA-256:** `75d02206ac4dd97134d17aadef38a01f51d27e723b71a4088e73220c2750179c`

---

## 1. Model Investigation & Selection

Four open-source mobile-candidate NSFW classification models were evaluated prior to integration:

| Candidate Model | Backbone Architecture | Weights Format & Size | Input / Normalization | Output Classes | License | Evaluation Outcome |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`GantMan/nsfw_model` (`mobilenet_v2_140_224`)** | MobileNetV2 ($\alpha = 1.40$, 150 bottleneck blocks) | TFLite FlatBuffer (`TFL3`), `17.25 MB` | `[1, 224, 224, 3]` `FLOAT32`, RGB `[0.0, 1.0]` (`pixel / 255.0`) | 5 classes: `drawings`, `hentai`, `neutral`, `porn`, `sexy` | MIT License | **Selected**: 5-class taxonomy cleanly separates `sexy` (swimwear/fitness/suggestive) from `porn` & `hentai`, minimizing false positives on normal phone usage. |
| **Yahoo `open_nsfw` TFLite (`devzwy/TensorflowLite-NSFW-Android`)** | ResNet-50 (Caffe port) | TFLite (`5.96 MB`) | `[1, 224, 224, 3]` `FLOAT32`, BGR mean-subtracted `[104, 117, 123]` | 2 classes: `sfw`, `nsfw` | BSD-2-Clause | **Rejected**: Binary output conflates swimwear/fitness (`sexy`) with hardcore pornography, producing elevated false positives. |
| **AutoML Vision Edge NSFW (`nipunru/nsfw-detector-android`)** | MobileNetV1 AutoML | Quantized TFLite (`5.86 MB`) | `[1, 224, 224, 3]` `UINT8` | 2 classes: `sfw`, `nsfw` | MIT License | **Rejected**: Binary output lacks `SUGGESTIVE` / `NON_EXPLICIT` granularity. |
| **SIRAN (`joel710/SIRAN`)** | MobileNetV3-Small | PyTorch training source only (no published weights) | `[1, 224, 224, 3]` RGB | Binary logit | Apache-2.0 | **Rejected**: Repository publishes training scripts only without pretrained weights. |

---

## 2. Input Preprocessing & Output Taxonomy Mapping

### 2.1 Exact Tensor Preprocessing ([LiteRtNsfwClassifier.kt](file:///c:/Users/HomePC/ScreenGuard/android/app/src/main/java/com/paulfruitful/screenguard/classifier/LiteRtNsfwClassifier.kt))
- **Source Resolution**: `1080 × 2400` `HardwareBuffer` captured via `AccessibilityService.takeScreenshotOfWindow(windowId)` (Android 14+) or `AccessibilityService.takeScreenshot(DEFAULT_DISPLAY)` (Android 11+).
- **In-Memory Downsampling**: Scaled in RAM to `224 × 224` `Bitmap.Config.ARGB_8888`; the `HardwareBuffer` and full-resolution wrapper `Bitmap` are closed/recycled immediately inside `finally` blocks.
- **Duplicate-Frame Perceptual Hash ([PerceptualFrameHasher.kt](file:///c:/Users/HomePC/ScreenGuard/android/app/src/main/java/com/paulfruitful/screenguard/classifier/PerceptualFrameHasher.kt))**: A 64-bit difference hash (`dHash` over a `9 × 8` luminance grid) is computed in `< 0.15 ms`. If the Hamming distance to the previous frame in the same app is `<= 2` within `8,000 ms`, redundant inference is skipped.
- **Tensor Packing**: Packed into a single pre-allocated direct `ByteBuffer` (`602,112` bytes, native byte order) as RGB `FLOAT32` values normalized to `[0.0f, 1.0f]` (`((argb shr 16) and 0xFF) / 255.0f`, etc.).

### 2.2 Taxonomy Mapping Layer & Scientific Limitations
The raw 5-class softmax probabilities (`drawings`, `hentai`, `neutral`, `porn`, `sexy`) are preserved verbatim in `ClassificationResult.scores` and mapped into the 6-category Screen Guard taxonomy:

1. **`EXPLICIT`**: `p_porn >= explicitThreshold` (default `0.75`) OR `p_hentai >= explicitThreshold` OR `(p_porn + p_hentai) >= max(0.75, explicitThreshold)`.
2. **`NUDITY`**: `p_porn >= nudityThreshold` (`0.62 * explicitThreshold = 0.465`) OR `(derivedNudityScore >= 0.52 && p_porn >= 0.22)` OR `p_hentai >= nudityThreshold`.
3. **`SUGGESTIVE`**: `p_sexy >= 0.52` (while below `NUDITY` and `EXPLICIT` thresholds).
4. **`NON_EXPLICIT`**: `0.18 <= p_sexy < 0.52` with `(p_neutral + p_drawings) >= 0.35` — captures ordinary skin exposure such as swimming, gym/fitness, sports, and fashion/swimwear without triggering false-positive nudity alerts.
5. **`SAFE`**: `(p_neutral + p_drawings) >= 0.52` with `(p_porn + p_hentai) < 0.25`, or `(p_neutral + p_drawings) >= 0.60` with `p_porn < 0.05` and `p_hentai < 0.45` (calibrated on the validation split to prevent high-contrast dark-skin portrait false alarms).
6. **`UNCERTAIN`**: Ambiguous probability distributions where no category reaches confidence thresholds.

> **Documented Model Limitation:** Because `nsfw_mobilenet_v2_140_224` was trained on 5 visual classes (`drawings`, `hentai`, `neutral`, `porn`, `sexy`), it does not have a dedicated semantic class for non-sexual intimate contexts (such as close-up breastfeeding or clinical dermatology). In our test suite, maternal breastfeeding photography (`test_breastfeeding_01`) produced `neutral = 76.3%, porn = 22.7%`, which correctly remains `SAFE` under the calibrated `nudityThreshold = 46.5%`, but close-up anatomical frames could score higher on `porn`. Furthermore, low-contrast monochrome/silhouette nude art (`test_nude_study_02`, `porn = 29.6%, neutral = 67.2%`) routes to `UNCERTAIN` rather than `NUDITY` under a strict low-FPR threshold.

---

## 3. Reproducible On-Device Evaluation Results (24 Unique Samples)

The evaluation suite ([ClassifierEvaluator.kt](file:///c:/Users/HomePC/ScreenGuard/android/app/src/main/java/com/paulfruitful/screenguard/classifier/ClassifierEvaluator.kt)) bundles 24 distinct Wikimedia Commons images in `android/app/src/main/assets/eval_suite/` (verified as 24 unique SHA-256 hashes with zero overlap between the **8-sample Validation Split** and **16-sample Held-Out Test Split**).

### 3.1 Held-Out Test Split Metrics (`n = 16`: 12 Non-Explicit/Safe, 4 Positive Nudity/Explicit)

| Metric | Measured Value (On-Device) | Notes |
| :--- | :--- | :--- |
| **True Positives (`TP`)** | `3` | `test_nude_study_01` (`94.2% porn`), `test_explicit_study_01` (`60.0% porn`), `test_explicit_study_02` (`80.2% porn`) |
| **False Positives (`FP`)** | **`0`** | **0 of 12** difficult non-explicit/safe test images falsely flagged as `EXPLICIT` or `NUDITY` |
| **True Negatives (`TN`)** | **`12`** | Swimming, Gym, Sports, Beach, Breastfeeding, Medical, Fashion, Fitzpatrick I–VI Portraits, UI, Anime |
| **False Negatives (`FN`)** | `1` | `test_nude_study_02` (`29.6% porn` routed to `UNCERTAIN`) |
| **Precision** | **`100.0%`** (`1.000`) | Zero false alarms on non-explicit content |
| **Recall** | **`75.0%`** (`0.750`) | 3 of 4 positive test samples flagged on single-frame pass |
| **False-Positive Rate (`FPR`)** | **`0.0%`** (`0.000`) | Primary design priority for continuous background phone observation |
| **False-Negative Rate (`FNR`)** | **`25.0%`** (`0.250`) | Trade-off from strict threshold (`0.75` explicit / `0.465` nudity) |
| **F1 Score** | **`0.857`** | Harmonic mean of Precision and Recall on held-out test split |
| **Binary Accuracy** | **`93.75%`** (`15 / 16`) | Held-out test split accuracy |

### 3.2 Validation Split Metrics (`n = 8`: 6 Non-Explicit/Safe, 2 Positive Nudity/Explicit)
- **Confusion Matrix**: `TP = 1`, `FP = 0`, `TN = 6`, `FN = 1` (`val_erotic_art_01` scored `38.9% porn, 10.5% sexy` and routed to `UNCERTAIN`).
- **Validation Precision**: `100.0%` | **Validation Recall**: `50.0%` | **Validation FPR**: **`0.0%`** | **Validation F1**: `0.667`.

### 3.3 Per-Sample On-Device Probability Table (All 24 Samples)

| Sample ID | Split | Category / Hard-Negative Type | Ground Truth | Predicted Category | Latency | `neutral` | `drawings` | `sexy` | `porn` | `hentai` | Binary Match |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `val_swimming_01` | `validation` | Swimming | `NON_EXPLICIT` | `SAFE` | `583 ms` | `94.5%` | `5.3%` | `0.1%` | `0.0%` | `0.0%` | ✓ True |
| `val_fitness_01` | `validation` | Gym & Fitness | `NON_EXPLICIT` | `SAFE` | `192 ms` | `95.3%` | `3.3%` | `0.9%` | `0.0%` | `0.5%` | ✓ True |
| `val_beach_01` | `validation` | Beach Photography | `NON_EXPLICIT` | `SAFE` | `244 ms` | `98.5%` | `0.6%` | `0.9%` | `0.0%` | `0.0%` | ✓ True |
| `val_portrait_dark_01` | `validation` | Portrait (Fitzpatrick VI) | `SAFE` | `SAFE` | `247 ms` | `19.7%` | `45.7%` | `0.0%` | `0.4%` | `34.2%` | ✓ True |
| `val_portrait_light_01` | `validation` | Portrait (Fitzpatrick II) | `SAFE` | `SAFE` | `320 ms` | `100.0%` | `0.0%` | `0.0%` | `0.0%` | `0.0%` | ✓ True |
| `val_medical_01` | `validation` | Medical / Educational | `SAFE` | `SAFE` | `397 ms` | `69.4%` | `13.9%` | `1.0%` | `0.8%` | `14.9%` | ✓ True |
| `val_figure_nude_01` | `validation` | Figure Nude (Positive) | `NUDITY` | `EXPLICIT` | `396 ms` | `1.4%` | `0.0%` | `0.0%` | `98.6%` | `0.0%` | ✓ True |
| `val_erotic_art_01` | `validation` | Explicit Study (Positive) | `EXPLICIT` | `UNCERTAIN` | `499 ms` | `50.2%` | `0.0%` | `10.5%` | `38.9%` | `0.4%` | ✕ False (FN) |
| `test_swimming_01` | `test` | Swimming | `NON_EXPLICIT` | `SAFE` | `564 ms` | `95.9%` | `4.0%` | `0.1%` | `0.0%` | `0.0%` | ✓ True |
| `test_gym_01` | `test` | Gym & Fitness | `NON_EXPLICIT` | `SUGGESTIVE` | `470 ms` | `38.7%` | `0.2%` | `60.9%` | `0.0%` | `0.2%` | ✓ True |
| `test_sports_01` | `test` | Sports (Beach Volleyball) | `NON_EXPLICIT` | `NON_EXPLICIT` | `524 ms` | `66.2%` | `1.3%` | `32.2%` | `0.0%` | `0.3%` | ✓ True |
| `test_beach_01` | `test` | Beach Photography | `NON_EXPLICIT` | `SAFE` | `366 ms` | `98.9%` | `0.8%` | `0.2%` | `0.0%` | `0.2%` | ✓ True |
| `test_breastfeeding_01` | `test` | Breastfeeding / Maternal | `NON_EXPLICIT` | `SAFE` | `514 ms` | `76.3%` | `0.0%` | `1.0%` | `22.7%` | `0.0%` | ✓ True |
| `test_medical_01` | `test` | Medical / Anatomy Diagram | `SAFE` | `SAFE` | `614 ms` | `80.3%` | `12.9%` | `0.8%` | `0.8%` | `5.3%` | ✓ True |
| `test_fashion_01` | `test` | Fashion & Swimwear Ad | `NON_EXPLICIT` | `NON_EXPLICIT` | `594 ms` | `50.2%` | `1.6%` | `46.0%` | `1.2%` | `1.0%` | ✓ True |
| `test_portrait_tone1` | `test` | Portrait (Fitzpatrick I–II) | `SAFE` | `SAFE` | `423 ms` | `93.5%` | `6.3%` | `0.1%` | `0.0%` | `0.1%` | ✓ True |
| `test_portrait_tone4` | `test` | Portrait (Fitzpatrick III–IV) | `SAFE` | `SAFE` | `486 ms` | `99.2%` | `0.7%` | `0.0%` | `0.0%` | `0.0%` | ✓ True |
| `test_portrait_tone6` | `test` | Portrait (Fitzpatrick V–VI) | `SAFE` | `SAFE` | `450 ms` | `99.2%` | `0.7%` | `0.0%` | `0.0%` | `0.0%` | ✓ True |
| `test_ui_article` | `test` | Mobile UI Screenshot | `SAFE` | `SAFE` | `412 ms` | `97.6%` | `1.3%` | `0.0%` | `0.0%` | `1.1%` | ✓ True |
| `test_drawings_anime` | `test` | Illustration / Anime | `SAFE` | `SAFE` | `568 ms` | `0.0%` | `99.8%` | `0.0%` | `0.0%` | `0.2%` | ✓ True |
| `test_nude_study_01` | `test` | Artistic Nude Study 1 (Pos) | `NUDITY` | `EXPLICIT` | `331 ms` | `1.1%` | `0.0%` | `4.6%` | `94.2%` | `0.1%` | ✓ True |
| `test_nude_study_02` | `test` | Artistic Nude Study 2 (Pos) | `NUDITY` | `UNCERTAIN` | `534 ms` | `67.2%` | `0.1%` | `2.9%` | `29.6%` | `0.3%` | ✕ False (FN) |
| `test_explicit_study_01` | `test` | Explicit Study 1 (Pos) | `EXPLICIT` | `NUDITY` | `394 ms` | `38.8%` | `0.0%` | `1.2%` | `60.0%` | `0.0%` | ✓ True |
| `test_explicit_study_02` | `test` | Explicit Study 2 (Pos) | `EXPLICIT` | `EXPLICIT` | `418 ms` | `18.4%` | `0.0%` | `1.3%` | `80.2%` | `0.2%` | ✓ True |

---

## 4. Hardware Performance & Memory Benchmarks (Android 16 Device `24117RN76G`)

| Benchmark Dimension | `CPU_XNNPACK_4T` (4 Threads) | `NNAPI_DELEGATE` | Live Accessibility Pipeline |
| :--- | :--- | :--- | :--- |
| **Model Initialization Time** | `107 ms` (warm) / `410–479 ms` (cold) | `150 ms` | One-time lazy init on background thread |
| **Min Inference Latency** | `216 ms` | `257 ms` | `0 ms` on dHash duplicate cache hit / `204 ms` min inference |
| **Median (`P50`) Latency** | `382 ms` | `336 ms` | `290–450 ms` on live `1080×2400 -> 224×224` frames |
| **Average Inference Latency** | `360.1 ms` | `338.2 ms` | `439.2 ms` across 24-sample suite |
| **95th Percentile (`P95`) Latency** | `465 ms` | `420 ms` | `583 ms` |
| **Native Heap Allocated (`dumpsys meminfo`)** | `34,404 KB` (`33.6 MB`) | `34,404 KB` (`33.6 MB`) | Stable after 24-sample suite + live captures |
| **Dalvik / Java Heap Allocated** | `3,708 KB` (`3.6 MB`) | `3,708 KB` (`3.6 MB`) | Zero `HardwareBuffer` or `Bitmap` leaks |
| **Total Process PSS** | `148,319 KB` (`144.8 MB`) | `148,319 KB` (`144.8 MB`) | Includes React Native runtime + LiteRT interpreter |

> **Latency Benchmark Analysis:** On the connected `24117RN76G` device (MediaTek Helio G81-Ultra Cortex-A75/A55 CPU), FP32 `MobileNetV2` (`alpha = 1.40`, `224×224`, 17.25 MB) executes in **204–382 ms (P50)** on 4 CPU threads with XNNPACK and **336 ms (P50)** via NNAPI (while static duplicate frames resolve in **`0 ms`** via the 64-bit `dHash` cache). Achieving the `< 30 ms` target on mid-range ARM cores requires INT8 full-integer quantization with a NPU/GPU delegate or a smaller backbone (`MobileNetV3-Small` / `MobileNetV2 alpha = 0.75` INT8). Because `DetectionEngine` enforces `MAX_PENDING_INFERENCE_JOBS = 1` and `EventDebouncer` enforces a `2,500 ms` default sampling cooldown, the ~340 ms background inference runs at `< 14%` duty cycle during active scrolling and `0%` duty cycle when the screen is static.
