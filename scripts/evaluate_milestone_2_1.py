#!/usr/bin/env python3
"""
Screen Guard — Milestone 2.1 Reproducible Offline Evaluation Suite

Benchmarks:
1. Milestone 2.0 Baseline (Single-Stage MobileNetV2 1.40, Original Frame)
2. Standalone Second-Stage Model (Yahoo OpenNSFW ResNet-50, Original Frame)
3. Low-Light Preprocessing Modes (ORIGINAL, GAMMA_CORRECTION, CLAHE_LOCAL_CONTRAST, BRIGHTNESS_CONTRAST_NORM)
4. Milestone 2.1 Full Two-Stage Cascade + Adaptive Low-Light CLAHE/Gamma Preprocessing
"""

import json
import math
import os
import time
import numpy as np
from PIL import Image

try:
    from ai_edge_litert.interpreter import Interpreter
except ImportError:
    try:
        from tflite_runtime.interpreter import Interpreter
    except ImportError:
        import tensorflow as tf
        Interpreter = tf.lite.Interpreter

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS_DIR = os.path.join(ROOT_DIR, "android", "app", "src", "main", "assets")
EVAL_DIR = os.path.join(ASSETS_DIR, "eval_suite")
STAGE1_MODEL_PATH = os.path.join(ASSETS_DIR, "nsfw_mobilenet_v2_140_224.tflite")
STAGE2_MODEL_PATH = os.path.join(ASSETS_DIR, "open_nsfw_resnet50.tflite")
MANIFEST_PATH = os.path.join(EVAL_DIR, "manifest.json")

POSITIVE_CATEGORIES = {"EXPLICIT", "NUDITY", "SEXUAL_ACTIVITY"}


def analyze_illumination(rgb_uint8: np.ndarray) -> dict:
    r = rgb_uint8[:, :, 0].astype(np.int32)
    g = rgb_uint8[:, :, 1].astype(np.int32)
    b = rgb_uint8[:, :, 2].astype(np.int32)
    luma = np.clip((299 * r + 587 * g + 114 * b) // 1000, 0, 255)
    mean_luma = float(np.mean(luma))
    std_luma = float(np.std(luma))
    dark_ratio = float(np.mean(luma < 60))
    bright_ratio = float(np.mean(luma > 210))
    p5 = int(np.percentile(luma, 5))
    p95 = int(np.percentile(luma, 95))
    is_low_light = (mean_luma < 82.0) or (mean_luma < 102.0 and dark_ratio > 0.52)
    is_low_contrast = ((p95 - p5) < 95) or (std_luma < 34.0)
    return {
        "meanLuminance": mean_luma,
        "stdDevLuminance": std_luma,
        "darkPixelRatio": dark_ratio,
        "brightPixelRatio": bright_ratio,
        "p5": p5,
        "p95": p95,
        "isLowLight": is_low_light,
        "isLowContrast": is_low_contrast,
        "lumaPlane": luma,
    }


def apply_gamma_correction(rgb_uint8: np.ndarray, illum: dict) -> np.ndarray:
    norm_mean = max(0.08, min(0.85, illum["meanLuminance"] / 255.0))
    if norm_mean < 0.45:
        gamma = max(0.48, min(0.75, math.log(0.45) / math.log(norm_mean)))
    else:
        gamma = 0.75

    lut = np.zeros(256, dtype=np.int32)
    for v in range(256):
        lifted = int(round(((v / 255.0) ** gamma) * 255.0))
        lifted = max(0, min(255, lifted))
        if v < 14:
            lut[v] = max(0, min(255, (v * (14 - v) + lifted * v) // 14))
        else:
            lut[v] = lifted

    luma = np.maximum(1, illum["lumaPlane"])
    new_luma = lut[luma]
    gain = np.minimum(2.3, new_luma.astype(np.float32) / luma.astype(np.float32))
    out = np.clip(rgb_uint8.astype(np.float32) * gain[:, :, None], 0, 255).astype(np.uint8)
    return out


def apply_clahe_local_contrast(rgb_uint8: np.ndarray, illum: dict, tiles_x=8, tiles_y=8, clip_factor=2.0) -> np.ndarray:
    h, w, _ = rgb_uint8.shape
    luma = illum["lumaPlane"]
    tile_w = max(1, w // tiles_x)
    tile_h = max(1, h // tiles_y)
    cdfs = np.zeros((tiles_y, tiles_x, 256), dtype=np.int32)

    for ty in range(tiles_y):
        y0 = ty * tile_h
        y1 = h if ty == tiles_y - 1 else min(h, (ty + 1) * tile_h)
        for tx in range(tiles_x):
            x0 = tx * tile_w
            x1 = w if tx == tiles_x - 1 else min(w, (tx + 1) * tile_w)
            patch = luma[y0:y1, x0:x1]
            area = max(1, patch.size)
            hist, _ = np.histogram(patch, bins=256, range=(0, 256))
            hist = hist.astype(np.int32)
            clip_limit = max(2, int((clip_factor * area) / 256.0))
            excess = int(np.sum(np.maximum(0, hist - clip_limit)))
            hist = np.minimum(hist, clip_limit)
            redistrib = excess // 256
            rem = excess % 256
            hist += redistrib
            hist[:rem] += 1
            cum = np.cumsum(hist)
            cdfs[ty, tx] = np.clip((cum * 255.0 / area).astype(np.int32), 0, 255)

    out = np.zeros_like(rgb_uint8)
    for y in range(h):
        fy = (y - tile_h / 2.0) / tile_h
        ty0 = max(0, min(tiles_y - 1, int(fy)))
        ty1 = max(0, min(tiles_y - 1, ty0 + 1))
        wy = max(0.0, min(1.0, fy - ty0))
        for x in range(w):
            fx = (x - tile_w / 2.0) / tile_w
            tx0 = max(0, min(tiles_x - 1, int(fx)))
            tx1 = max(0, min(tiles_x - 1, tx0 + 1))
            wx = max(0.0, min(1.0, fx - tx0))
            lv = int(luma[y, x])
            c00 = cdfs[ty0, tx0, lv]
            c10 = cdfs[ty0, tx1, lv]
            c01 = cdfs[ty1, tx0, lv]
            c11 = cdfs[ty1, tx1, lv]
            top = c00 * (1.0 - wx) + c10 * wx
            bot = c01 * (1.0 - wx) + c11 * wx
            clahe_l = int(top * (1.0 - wy) + bot * wy)
            if lv < 15:
                blended = (lv * 2 + clahe_l) // 3
            else:
                blended = (clahe_l * 7 + lv * 3) // 10
            gain = min(2.25, float(blended) / max(1.0, float(lv)))
            out[y, x] = np.clip(rgb_uint8[y, x].astype(np.float32) * gain, 0, 255).astype(np.uint8)
    return out


def apply_brightness_contrast_norm(rgb_uint8: np.ndarray, illum: dict) -> np.ndarray:
    span = max(25, illum["p95"] - illum["p5"])
    scale = min(2.25, 217.0 / float(span))
    offset = 18.0 - (illum["p5"] * scale)
    return np.clip(rgb_uint8.astype(np.float32) * scale + offset, 0, 255).astype(np.uint8)


def interpret_baseline_single_stage(s1: dict, explicit_threshold=0.75) -> tuple[str, float]:
    p_draw, p_hentai, p_neutral, p_porn, p_sexy = (
        s1["drawings"],
        s1["hentai"],
        s1["neutral"],
        s1["porn"],
        s1["sexy"],
    )
    combined_explicit = min(1.0, p_porn + p_hentai)
    combined_safe = min(1.0, p_neutral + p_draw)
    derived_nudity = min(1.0, p_porn + 0.45 * p_sexy + 0.65 * p_hentai)
    nudity_threshold = max(0.35, min(0.65, explicit_threshold * 0.62))
    suggestive_threshold = 0.52

    if p_porn >= explicit_threshold or p_hentai >= explicit_threshold or combined_explicit >= max(0.75, explicit_threshold):
        return "EXPLICIT", max(p_porn, p_hentai, combined_explicit)
    elif p_porn >= nudity_threshold or (derived_nudity >= 0.52 and p_porn >= 0.22) or p_hentai >= nudity_threshold:
        return "NUDITY", max(p_porn, derived_nudity)
    elif p_sexy >= suggestive_threshold:
        return "SUGGESTIVE", p_sexy
    elif p_sexy >= 0.18 and combined_safe >= 0.35:
        return "NON_EXPLICIT", min(1.0, p_sexy + combined_safe)
    elif (combined_safe >= 0.52 and combined_explicit < 0.25) or (combined_safe >= 0.60 and p_porn < 0.05 and p_hentai < 0.45):
        return "NON_EXPLICIT", combined_safe
    else:
        return "UNCERTAIN", max(p_draw, p_hentai, p_neutral, p_porn, p_sexy)


def resolve_two_stage_taxonomy(orig_s1: dict, eff_s1: dict, s2: dict | None, illum: dict, explicit_threshold=0.75) -> tuple[str, float]:
    p_draw = eff_s1["drawings"]
    p_hentai = eff_s1["hentai"]
    p_neutral = eff_s1["neutral"]
    p_porn = max(orig_s1["porn"], eff_s1["porn"])
    p_sexy = max(orig_s1["sexy"], eff_s1["sexy"])

    s2_triggered = s2 is not None
    s2_nsfw = s2["nsfw"] if s2_triggered else 0.0
    s2_sfw = s2["sfw"] if s2_triggered else 1.0

    combined_safe = min(1.0, p_neutral + p_draw)
    s1_explicit = min(1.0, p_porn + p_hentai)

    is_likely_non_sexual_skin = (
        (not illum["isLowLight"])
        and p_neutral >= 0.70
        and p_sexy < 0.025
        and p_porn < 0.30
        and ((not s2_triggered) or s2_nsfw < 0.52)
    )
    is_ordinary_dark_scene = (
        illum["isLowLight"]
        and orig_s1["porn"] < 0.025
        and orig_s1["sexy"] < 0.045
        and ((not s2_triggered) or s2_nsfw < 0.38)
    )

    nudity_threshold = max(0.35, min(0.60, explicit_threshold * 0.58))
    sexual_activity_score = (
        min(1.0, 0.45 * (p_porn + 0.75 * p_sexy) + 0.55 * s2_nsfw)
        if s2_triggered
        else min(1.0, 0.70 * p_porn + 0.45 * p_sexy)
    )
    derived_nudity = (
        max(p_porn, min(1.0, 0.60 * p_porn + 0.40 * s2_nsfw + 0.25 * p_sexy))
        if s2_triggered
        else min(1.0, p_porn + 0.40 * p_sexy + 0.55 * p_hentai)
    )
    derived_explicit = (
        max(s1_explicit, min(1.0, 0.65 * p_porn + 0.35 * s2_nsfw))
        if s2_triggered
        else s1_explicit
    )

    if is_ordinary_dark_scene or is_likely_non_sexual_skin:
        return "NON_EXPLICIT", max(combined_safe, s2_sfw)
    if p_porn >= explicit_threshold or (p_hentai >= explicit_threshold and p_draw < 0.30) or (s2_triggered and p_porn >= 0.50 and s2_nsfw >= 0.68):
        return "EXPLICIT", max(p_porn, derived_explicit)
    if (
        (0.22 <= p_porn <= 0.68 and p_sexy >= 0.14 and (p_porn + p_sexy) >= 0.48)
        or (s2_triggered and (p_porn + p_sexy) >= 0.24 and p_porn >= 0.03 and s2_nsfw >= 0.42)
        or (s2_triggered and p_sexy >= 0.48 and s2_nsfw >= 0.48)
        or (s2_triggered and illum["isLowLight"] and (p_porn + p_sexy) >= 0.08 and s2_nsfw >= 0.45)
    ):
        return "SEXUAL_ACTIVITY", max(sexual_activity_score, s2_nsfw, min(1.0, p_porn + 0.65 * p_sexy))
    if (
        p_porn >= nudity_threshold
        or (s2_triggered and p_porn >= 0.20 and s2_nsfw >= 0.42)
        or (illum["isLowLight"] and p_porn >= 0.18 and (s2_nsfw >= 0.28 or p_porn >= 0.26))
    ):
        return "NUDITY", max(p_porn, derived_nudity, s2_nsfw)
    if p_sexy >= 0.48 and p_porn < 0.14 and ((not s2_triggered) or s2_nsfw < 0.48):
        return "SUGGESTIVE", p_sexy
    if (
        (combined_safe >= 0.48 and p_porn < 0.18)
        or (0.12 <= p_sexy <= 0.48 and p_porn < 0.12 and ((not s2_triggered) or s2_nsfw < 0.42))
        or (s2_triggered and s2_sfw >= 0.68 and p_porn < 0.20)
        or (combined_safe >= 0.60 and p_porn < 0.05 and p_hentai < 0.45)
    ):
        return "NON_EXPLICIT", max(combined_safe, s2_sfw)
    return "UNCERTAIN", max(p_draw, p_hentai, p_neutral, p_porn, p_sexy)


class TwoStageEvaluator:
    def __init__(self):
        self.s1 = Interpreter(model_path=STAGE1_MODEL_PATH)
        self.s1.allocate_tensors()
        self.s1_in = self.s1.get_input_details()[0]["index"]
        self.s1_out = self.s1.get_output_details()[0]["index"]

        self.s2 = Interpreter(model_path=STAGE2_MODEL_PATH)
        self.s2.allocate_tensors()
        self.s2_in = self.s2.get_input_details()[0]["index"]
        self.s2_out = self.s2.get_output_details()[0]["index"]

    def run_s1(self, rgb_uint8: np.ndarray) -> dict:
        t0 = time.perf_counter()
        tensor = (rgb_uint8.astype(np.float32) / 255.0)[None, :, :, :]
        self.s1.set_tensor(self.s1_in, tensor)
        self.s1.invoke()
        probs = self.s1.get_tensor(self.s1_out)[0]
        ms = (time.perf_counter() - t0) * 1000.0
        return {
            "drawings": float(probs[0]),
            "hentai": float(probs[1]),
            "neutral": float(probs[2]),
            "porn": float(probs[3]),
            "sexy": float(probs[4]),
            "durationMs": ms,
        }

    def run_s2(self, rgb_uint8: np.ndarray) -> dict:
        t0 = time.perf_counter()
        r = rgb_uint8[:, :, 0].astype(np.float32) - 123.0
        g = rgb_uint8[:, :, 1].astype(np.float32) - 117.0
        b = rgb_uint8[:, :, 2].astype(np.float32) - 104.0
        bgr = np.stack([b, g, r], axis=-1)[None, :, :, :]
        self.s2.set_tensor(self.s2_in, bgr)
        self.s2.invoke()
        probs = self.s2.get_tensor(self.s2_out)[0]
        ms = (time.perf_counter() - t0) * 1000.0
        return {
            "sfw": float(probs[0]),
            "nsfw": float(probs[1]),
            "durationMs": ms,
        }

    def evaluate_m2_1(self, rgb_uint8: np.ndarray) -> dict:
        illum = analyze_illumination(rgb_uint8)
        orig_s1 = self.run_s1(rgb_uint8)
        base_cat, base_conf = interpret_baseline_single_stage(orig_s1)

        eff_s1 = orig_s1
        applied_mode = "ORIGINAL"
        clahe_rgb = None
        gamma_rgb = None

        if illum["isLowLight"] and orig_s1["drawings"] < 0.80:
            clahe_rgb = apply_clahe_local_contrast(rgb_uint8, illum)
            gamma_rgb = apply_gamma_correction(rgb_uint8, illum)
            c_s1 = self.run_s1(clahe_rgb)
            g_s1 = self.run_s1(gamma_rgb)
            if (c_s1["porn"] + 0.6 * c_s1["sexy"]) >= (g_s1["porn"] + 0.6 * g_s1["sexy"]):
                best_s1, best_mode = c_s1, "CLAHE_LOCAL_CONTRAST"
            else:
                best_s1, best_mode = g_s1, "GAMMA_CORRECTION"
            orig_sig = orig_s1["porn"] + orig_s1["sexy"] + orig_s1["hentai"]
            enh_sig = best_s1["porn"] + best_s1["sexy"]
            if orig_sig >= 0.015 and enh_sig > (orig_sig + 0.04):
                eff_s1 = best_s1
                applied_mode = best_mode

        should_run_s2 = (
            base_cat == "UNCERTAIN"
            or (0.04 <= eff_s1["porn"] <= 0.55)
            or (eff_s1["sexy"] >= 0.16)
            or (illum["isLowLight"] and orig_s1["drawings"] < 0.70)
        )
        s2_res = None
        if should_run_s2:
            s2_orig = self.run_s2(rgb_uint8)
            s2_res = dict(s2_orig)
            enh_target = clahe_rgb if applied_mode == "CLAHE_LOCAL_CONTRAST" else (gamma_rgb if gamma_rgb is not None else clahe_rgb)
            if illum["isLowLight"] and enh_target is not None:
                s2_enh = self.run_s2(enh_target)
                if s2_orig["nsfw"] >= 0.08 or (orig_s1["porn"] + orig_s1["sexy"]) >= 0.05:
                    blended = max(s2_orig["nsfw"], 0.45 * s2_orig["nsfw"] + 0.55 * s2_enh["nsfw"])
                    s2_res["nsfw"] = blended
                    s2_res["sfw"] = 1.0 - blended
                    if applied_mode == "ORIGINAL" and s2_enh["nsfw"] > s2_orig["nsfw"] + 0.10:
                        applied_mode = "CLAHE_LOCAL_CONTRAST"

        m21_cat, m21_conf = resolve_two_stage_taxonomy(orig_s1, eff_s1, s2_res, illum)
        return {
            "baselineCategory": base_cat,
            "baselineConfidence": base_conf,
            "m21Category": m21_cat,
            "m21Confidence": m21_conf,
            "appliedMode": applied_mode,
            "isLowLight": illum["isLowLight"],
            "meanLuma": illum["meanLuminance"],
            "s2Triggered": s2_res is not None,
            "s2Nsfw": s2_res["nsfw"] if s2_res else 0.0,
            "origS1": orig_s1,
            "effS1": eff_s1,
        }


def compute_split_metrics(rows, cat_key):
    tp = fp = tn = fn = unc = 0
    for r in rows:
        gt = r["isPositiveGroundTruth"]
        cat = r[cat_key]
        pred = cat in POSITIVE_CATEGORIES
        if cat == "UNCERTAIN":
            unc += 1
        if gt and pred:
            tp += 1
        elif (not gt) and pred:
            fp += 1
        elif (not gt) and (not pred):
            tn += 1
        else:
            fn += 1
    prec = tp / (tp + fp) if (tp + fp) > 0 else 1.0
    rec = tp / (tp + fn) if (tp + fn) > 0 else 1.0
    fpr = fp / (fp + tn) if (fp + tn) > 0 else 0.0
    fnr = fn / (fn + tp) if (fn + tp) > 0 else 0.0
    f1 = 2 * prec * rec / (prec + rec) if (prec + rec) > 0 else 0.0
    acc = (tp + tn) / len(rows) if rows else 1.0
    unc_rate = unc / len(rows) if rows else 0.0
    return {
        "n": len(rows),
        "TP": tp,
        "FP": fp,
        "TN": tn,
        "FN": fn,
        "Uncertain": unc,
        "UncertainRate": unc_rate,
        "Precision": prec,
        "Recall": rec,
        "FPR": fpr,
        "FNR": fnr,
        "F1": f1,
        "Accuracy": acc,
    }


if __name__ == "__main__":
    ev = TwoStageEvaluator()
    with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    all_rows = []
    print("=== PER-SAMPLE BEFORE (M2.0) vs AFTER (M2.1) COMPARISON ===")
    for item in manifest:
        fp = os.path.join(EVAL_DIR, item["fileName"])
        rgb = np.array(Image.open(fp).convert("RGB").resize((224, 224)))
        res = ev.evaluate_m2_1(rgb)
        row = {**item, **res}
        all_rows.append(row)
        print(
            f"{item['id']:24s} | {item['split']:10s} | GT:{item['expectedGroup']:15s} "
            f"| M2.0:{res['baselineCategory']:12s} ({res['baselineConfidence']*100:5.1f}%) "
            f"-> M2.1:{res['m21Category']:15s} ({res['m21Confidence']*100:5.1f}%) "
            f"| Mode:{res['appliedMode']:20s} | S2:{res['s2Nsfw']*100:5.1f}%"
        )

    for split_name, subset in [
        ("HELD-OUT TEST SPLIT (n=24)", [r for r in all_rows if r["split"] == "test"]),
        ("VALIDATION SPLIT (n=12)", [r for r in all_rows if r["split"] == "validation"]),
        ("ALL SAMPLES (n=36)", all_rows),
    ]:
        m20 = compute_split_metrics(subset, "baselineCategory")
        m21 = compute_split_metrics(subset, "m21Category")
        print(f"\n=== {split_name} ===")
        print(f"  BEFORE (M2.0 Single-Stage): TP={m20['TP']} FP={m20['FP']} TN={m20['TN']} FN={m20['FN']} | Prec={m20['Precision']*100:5.1f}% Rec={m20['Recall']*100:5.1f}% FPR={m20['FPR']*100:4.1f}% F1={m20['F1']:.3f} Acc={m20['Accuracy']*100:5.1f}% Uncertain={m20['Uncertain']}/{m20['n']} ({m20['UncertainRate']*100:4.1f}%)")
        print(f"  AFTER  (M2.1 Two-Stage)   : TP={m21['TP']} FP={m21['FP']} TN={m21['TN']} FN={m21['FN']} | Prec={m21['Precision']*100:5.1f}% Rec={m21['Recall']*100:5.1f}% FPR={m21['FPR']*100:4.1f}% F1={m21['F1']:.3f} Acc={m21['Accuracy']*100:5.1f}% Uncertain={m21['Uncertain']}/{m21['n']} ({m21['UncertainRate']*100:4.1f}%)")
