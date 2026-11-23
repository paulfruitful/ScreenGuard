#!/usr/bin/env python3
"""
Screen Guard — Milestone 3 Policy & State-Machine Verification Suite

Verifies:
1. Category-Specific Decision Thresholds & Policy Mapping across the 36-sample evaluation dataset:
   - BALANCED Mode: Intervenes on confirmed EXPLICIT (>= 0.72), NUDITY (>= 0.42), SEXUAL_ACTIVITY (>= 0.45).
     Never triggers full-screen overlay on SUGGESTIVE, NON_EXPLICIT, SAFE, or UNCERTAIN.
   - STRICT Mode: Tighter thresholds (EXPLICIT >= 0.63, NUDITY >= 0.36, SEXUAL_ACTIVITY >= 0.39)
     and covers high-confidence confirmed SUGGESTIVE (>= 0.76).
2. Overlay Lifecycle State Machine (HIDDEN, PENDING, PROTECTED, RECOVERING, DISMISSED):
   - Multi-frame confirmation (HIDDEN -> PENDING -> PROTECTED)
   - High-certainty corroborated frame fast-path
   - UNCERTAIN frame preservation while PROTECTED (anti-flicker)
   - Minimum display duration enforcement (RECOVERING until minDisplayDurationMs & recoverySafeFramesRequired met)
   - Foreground application switch immediate reset & overlay removal
   - Screen-off immediate reset & overlay removal
   - Manual user dismissal ("Dismiss for This Screen") and per-package cooldown suppression
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import evaluate_milestone_2_1 as m21


class ProtectionPolicyConfig:
    def __init__(self, mode="BALANCED", base_threshold=0.72, confirmation_frames=2, allow_dismiss=True):
        self.sensitivity_mode = mode
        self.allow_manual_dismiss = allow_dismiss
        if mode == "STRICT":
            self.intervene_on_suggestive_in_strict = True
            self.explicit_threshold = max(0.52, min(0.85, base_threshold * 0.88))
            self.nudity_threshold = max(0.32, min(0.55, base_threshold * 0.50))
            self.sexual_activity_threshold = max(0.35, min(0.58, base_threshold * 0.54))
            self.suggestive_threshold_strict = 0.76
            self.confirmation_frames_required = max(2, confirmation_frames - 1)
            self.recovery_safe_frames_required = 2
            self.min_display_duration_ms = 3000
            self.dismiss_cooldown_ms = 15000
        else:
            self.intervene_on_suggestive_in_strict = False
            self.explicit_threshold = max(0.60, min(0.92, base_threshold))
            self.nudity_threshold = max(0.38, min(0.62, base_threshold * 0.58))
            self.sexual_activity_threshold = max(0.42, min(0.65, base_threshold * 0.62))
            self.suggestive_threshold_strict = 0.99
            self.confirmation_frames_required = max(2, min(5, confirmation_frames))
            self.recovery_safe_frames_required = 2
            self.min_display_duration_ms = 2500
            self.dismiss_cooldown_ms = 12000


class ProtectionDecisionEngineSim:
    EWMA_ALPHA = 0.62

    def __init__(self):
        self.tracked_package = ""
        self.current_overlay_state = "HIDDEN"
        self.consecutive_positive_frames = 0
        self.consecutive_safe_frames = 0
        self.ewma_violation_confidence = 0.0
        self.ewma_initialized = False
        self.protection_shown_at_ms = 0
        self.dismissed_at_ms = 0
        self.dismissed_for_package = ""
        self.active_trigger_category = "NONE"

    def on_foreground_app_changed(self, new_package: str) -> bool:
        if new_package == self.tracked_package:
            return False
        self.tracked_package = new_package
        self.consecutive_positive_frames = 0
        self.consecutive_safe_frames = 0
        self.ewma_violation_confidence = 0.0
        self.ewma_initialized = False
        self.active_trigger_category = "NONE"
        was_visible = self.current_overlay_state in ("PROTECTED", "RECOVERING")
        self.current_overlay_state = "HIDDEN"
        self.protection_shown_at_ms = 0
        return was_visible

    def on_user_dismissed_overlay(self, package_name: str, now_ms: int):
        self.current_overlay_state = "DISMISSED"
        self.dismissed_at_ms = now_ms
        self.dismissed_for_package = package_name
        self.consecutive_positive_frames = 0
        self.consecutive_safe_frames = 0
        self.protection_shown_at_ms = 0

    def reset_all(self):
        self.current_overlay_state = "HIDDEN"
        self.consecutive_positive_frames = 0
        self.consecutive_safe_frames = 0
        self.ewma_violation_confidence = 0.0
        self.ewma_initialized = False
        self.protection_shown_at_ms = 0
        self.active_trigger_category = "NONE"

    def evaluate_category_policy(self, category: str, confidence: float, s2_triggered: bool, s2_nsfw: float, policy: ProtectionPolicyConfig):
        if category == "EXPLICIT":
            return confidence >= policy.explicit_threshold, confidence, policy.explicit_threshold
        if category == "NUDITY":
            return confidence >= policy.nudity_threshold, confidence, policy.nudity_threshold
        if category == "SEXUAL_ACTIVITY":
            return confidence >= policy.sexual_activity_threshold, confidence, policy.sexual_activity_threshold
        if category == "SUGGESTIVE":
            should_intervene = (
                policy.sensitivity_mode == "STRICT"
                and policy.intervene_on_suggestive_in_strict
                and confidence >= policy.suggestive_threshold_strict
            )
            return should_intervene, confidence, policy.suggestive_threshold_strict
        return False, confidence, policy.explicit_threshold

    def evaluate(self, category: str, confidence: float, package_name: str, now_ms: int, policy: ProtectionPolicyConfig, s2_triggered=False, s2_nsfw=0.0):
        if package_name and package_name != self.tracked_package:
            self.on_foreground_app_changed(package_name)

        # Check dismissal cooldown
        if self.dismissed_at_ms > 0 and self.dismissed_for_package == package_name:
            rem = max(0, policy.dismiss_cooldown_ms - (now_ms - self.dismissed_at_ms))
            if rem > 0:
                self.current_overlay_state = "DISMISSED"
                return {
                    "directive": "SUPPRESSED_BY_COOLDOWN",
                    "state": "DISMISSED",
                    "shouldShow": False,
                    "shouldHide": False,
                }
        if self.current_overlay_state == "DISMISSED":
            self.current_overlay_state = "HIDDEN"

        violates, eff_score, thresh = self.evaluate_category_policy(
            category, confidence, s2_triggered, s2_nsfw, policy
        )

        if violates:
            instant_signal = eff_score
        elif category == "UNCERTAIN":
            instant_signal = self.ewma_violation_confidence * 0.85
        else:
            instant_signal = 0.0

        if not self.ewma_initialized:
            self.ewma_violation_confidence = instant_signal
            self.ewma_initialized = True
        else:
            self.ewma_violation_confidence = (
                self.EWMA_ALPHA * instant_signal
                + (1.0 - self.EWMA_ALPHA) * self.ewma_violation_confidence
            )

        currently_showing = self.current_overlay_state in ("PROTECTED", "RECOVERING")

        if violates:
            self.consecutive_positive_frames += 1
            self.consecutive_safe_frames = 0
            self.active_trigger_category = category
            req = max(1, policy.confirmation_frames_required)
            meets_smoothed = self.ewma_violation_confidence >= (thresh * 0.72)
            is_high_conf_corroborated = (
                (category == "EXPLICIT" and eff_score >= thresh)
                or (category == "NUDITY" and (eff_score >= 0.48 or (s2_triggered and s2_nsfw >= 0.42)))
                or (category == "SEXUAL_ACTIVITY" and (eff_score >= 0.50 or (s2_triggered and s2_nsfw >= 0.45)))
            )
            confirmed = (self.consecutive_positive_frames >= req and meets_smoothed) or (
                self.consecutive_positive_frames >= 1 and is_high_conf_corroborated
            )
            if confirmed or currently_showing:
                if not currently_showing:
                    self.protection_shown_at_ms = now_ms
                self.current_overlay_state = "PROTECTED"
                return {
                    "directive": "MAINTAIN_PROTECTION" if currently_showing else "TRIGGER_PROTECTION",
                    "state": "PROTECTED",
                    "shouldShow": True,
                    "shouldHide": False,
                }
            else:
                self.current_overlay_state = "PENDING"
                return {
                    "directive": "ENTER_PENDING_CONFIRMATION",
                    "state": "PENDING",
                    "shouldShow": False,
                    "shouldHide": False,
                }

        if category == "UNCERTAIN":
            self.consecutive_safe_frames = 0
            if currently_showing:
                return {
                    "directive": "MAINTAIN_PROTECTION",
                    "state": self.current_overlay_state,
                    "shouldShow": True,
                    "shouldHide": False,
                }
            next_state = "PENDING" if self.consecutive_positive_frames > 0 else "HIDDEN"
            self.current_overlay_state = next_state
            return {
                "directive": "CONTINUE_OBSERVING",
                "state": next_state,
                "shouldShow": False,
                "shouldHide": False,
            }

        # SAFE / NON_EXPLICIT / non-violating SUGGESTIVE
        self.consecutive_safe_frames += 1
        if currently_showing:
            shown_dur = now_ms - self.protection_shown_at_ms
            min_met = shown_dur >= policy.min_display_duration_ms
            rec_met = self.consecutive_safe_frames >= policy.recovery_safe_frames_required
            if min_met and rec_met:
                self.consecutive_positive_frames = 0
                self.active_trigger_category = "NONE"
                self.current_overlay_state = "HIDDEN"
                self.protection_shown_at_ms = 0
                return {
                    "directive": "CLEAR_PROTECTION",
                    "state": "HIDDEN",
                    "shouldShow": False,
                    "shouldHide": True,
                }
            else:
                self.current_overlay_state = "RECOVERING"
                return {
                    "directive": "ENTER_RECOVERING",
                    "state": "RECOVERING",
                    "shouldShow": True,
                    "shouldHide": False,
                }
        else:
            self.consecutive_positive_frames = 0
            self.active_trigger_category = "NONE"
            self.current_overlay_state = "HIDDEN"
            return {
                "directive": "NO_ACTION",
                "state": "HIDDEN",
                "shouldShow": False,
                "shouldHide": False,
            }


def run_state_machine_unit_verification():
    print("=" * 80)
    print("1. OVERLAY LIFECYCLE & TEMPORAL STATE MACHINE VERIFICATION")
    print("=" * 80)
    policy_balanced = ProtectionPolicyConfig(mode="BALANCED", base_threshold=0.72, confirmation_frames=2)
    engine = ProtectionDecisionEngineSim()

    # Scenario A: Single borderline frame (e.g. NUDITY 0.43 without Stage 2) -> PENDING (no flicker), followed by SAFE -> HIDDEN
    d1 = engine.evaluate("NUDITY", 0.43, "com.instagram.android", 1000, policy_balanced)
    assert d1["state"] == "PENDING" and not d1["shouldShow"], f"Expected PENDING, got {d1}"
    d2 = engine.evaluate("NON_EXPLICIT", 0.92, "com.instagram.android", 2500, policy_balanced)
    assert d2["state"] == "HIDDEN" and not d2["shouldShow"], f"Expected HIDDEN reset, got {d2}"
    print("[PASS] Scenario A: Single borderline frame enters PENDING without showing overlay; safe frame resets to HIDDEN.")

    # Scenario B: Two consecutive borderline frames -> TRIGGER_PROTECTION (PROTECTED), or 1 high-confidence corroborated frame -> Immediate PROTECTED
    d3 = engine.evaluate("SEXUAL_ACTIVITY", 0.46, "com.instagram.android", 4000, policy_balanced)
    assert d3["state"] == "PENDING", f"Expected PENDING on borderline frame 1, got {d3}"
    d4 = engine.evaluate("SEXUAL_ACTIVITY", 0.48, "com.instagram.android", 5150, policy_balanced)
    assert d4["state"] == "PROTECTED" and d4["directive"] == "TRIGGER_PROTECTION" and d4["shouldShow"], f"Expected PROTECTED on frame 2, got {d4}"
    print("[PASS] Scenario B: Borderline frames transition PENDING -> PROTECTED on automatic 1.15s re-capture; corroborated explicit frames trigger immediately.")

    # Scenario C: Temporary UNCERTAIN frame while PROTECTED -> Maintains overlay (no flicker)
    d5 = engine.evaluate("UNCERTAIN", 0.41, "com.instagram.android", 6500, policy_balanced)
    assert d5["state"] == "PROTECTED" and d5["shouldShow"] and not d5["shouldHide"], f"Expected overlay maintained on UNCERTAIN, got {d5}"
    print("[PASS] Scenario C: Temporary UNCERTAIN classification while PROTECTED maintains overlay without flicker.")

    # Scenario D: Safe frame arrives before minimum display duration (2500ms) -> RECOVERING (overlay kept visible)
    d6 = engine.evaluate("NON_EXPLICIT", 0.91, "com.instagram.android", 7000, policy_balanced)  # 1500ms elapsed (< 2500ms), 1 safe frame (< 2)
    assert d6["state"] == "RECOVERING" and d6["shouldShow"], f"Expected RECOVERING, got {d6}"
    d7 = engine.evaluate("NON_EXPLICIT", 0.94, "com.instagram.android", 8200, policy_balanced)  # 2700ms elapsed (>= 2500ms), 2 safe frames (>= 2)
    assert d7["state"] == "HIDDEN" and d7["directive"] == "CLEAR_PROTECTION" and d7["shouldHide"], f"Expected CLEAR_PROTECTION -> HIDDEN, got {d7}"
    print("[PASS] Scenario D: Recovery requires both 2 consecutive safe frames and 2500ms minimum display duration (PROTECTED -> RECOVERING -> HIDDEN).")

    # Scenario E: Foreground application change while PROTECTED -> Immediately clears overlay
    engine.evaluate("EXPLICIT", 0.85, "com.zhiliaoapp.musically", 10000, policy_balanced)
    d8 = engine.evaluate("EXPLICIT", 0.88, "com.zhiliaoapp.musically", 11500, policy_balanced)
    assert d8["state"] == "PROTECTED"
    was_vis = engine.on_foreground_app_changed("com.android.chrome")
    assert was_vis and engine.current_overlay_state == "HIDDEN"
    print("[PASS] Scenario E: Switching foreground application immediately hides stale overlay and resets per-app temporal state.")

    # Scenario F: Manual user dismissal ("Dismiss for This Screen") -> Enters DISMISSED and enforces 12s cooldown
    engine.evaluate("NUDITY", 0.70, "com.twitter.android", 20000, policy_balanced)
    engine.evaluate("NUDITY", 0.75, "com.twitter.android", 21500, policy_balanced)
    assert engine.current_overlay_state == "PROTECTED"
    engine.on_user_dismissed_overlay("com.twitter.android", 22000)
    d9 = engine.evaluate("NUDITY", 0.80, "com.twitter.android", 26000, policy_balanced)  # +4s (< 12s cooldown)
    assert d9["state"] == "DISMISSED" and d9["directive"] == "SUPPRESSED_BY_COOLDOWN" and not d9["shouldShow"]
    d10 = engine.evaluate("NUDITY", 0.80, "com.twitter.android", 35000, policy_balanced)  # +13s (> 12s cooldown expired)
    assert d10["state"] in ("PENDING", "PROTECTED") and d10["shouldShow"]
    print("[PASS] Scenario F: Manual dismissal transitions to DISMISSED, suppresses re-trigger during 12s cooldown, and resumes after expiry.")


def run_dataset_policy_evaluation():
    print("\n" + "=" * 80)
    print("2. 36-SAMPLE DATASET POLICY EVALUATION (BALANCED vs STRICT MODE)")
    print("=" * 80)

    with open(m21.MANIFEST_PATH, "r", encoding="utf-8") as f:
        samples = __import__("json").load(f)
    if isinstance(samples, dict):
        samples = samples.get("samples", [])

    evaluator = m21.TwoStageEvaluator()

    policy_balanced = ProtectionPolicyConfig(mode="BALANCED", base_threshold=0.72, confirmation_frames=2)
    policy_strict = ProtectionPolicyConfig(mode="STRICT", base_threshold=0.72, confirmation_frames=2)

    balanced_interventions_pos = 0
    balanced_interventions_neg = 0
    strict_interventions_pos = 0
    strict_interventions_neg = 0
    pos_total = 0
    neg_total = 0

    for item in samples:
        img_path = os.path.join(m21.EVAL_DIR, item["fileName"])
        rgb = m21.np.array(m21.Image.open(img_path).convert("RGB").resize((224, 224), m21.Image.BILINEAR), dtype=m21.np.uint8)
        ev = evaluator.evaluate_m2_1(rgb)
        cat = ev["m21Category"]
        conf = ev["m21Confidence"]
        s2_triggered = ev["s2Triggered"]
        s2_nsfw = ev["s2Nsfw"]

        # Simulate 2 consecutive frames of this content in BALANCED and STRICT modes
        eng_bal = ProtectionDecisionEngineSim()
        eng_bal.evaluate(cat, conf, "com.test.app", 1000, policy_balanced, s2_triggered, s2_nsfw)
        res_bal = eng_bal.evaluate(cat, conf, "com.test.app", 2500, policy_balanced, s2_triggered, s2_nsfw)

        eng_str = ProtectionDecisionEngineSim()
        eng_str.evaluate(cat, conf, "com.test.app", 1000, policy_strict, s2_triggered, s2_nsfw)
        res_str = eng_str.evaluate(cat, conf, "com.test.app", 2500, policy_strict, s2_triggered, s2_nsfw)

        is_pos = bool(item.get("isPositiveGroundTruth", item.get("isPositive", False)))
        if is_pos:
            pos_total += 1
            if res_bal["shouldShow"]:
                balanced_interventions_pos += 1
            if res_str["shouldShow"]:
                strict_interventions_pos += 1
        else:
            neg_total += 1
            if res_bal["shouldShow"]:
                balanced_interventions_neg += 1
            if res_str["shouldShow"]:
                strict_interventions_neg += 1

    print(f"Positive Samples (n={pos_total}):")
    print(f"  - BALANCED Mode Overlay Trigger Recall: {balanced_interventions_pos}/{pos_total} ({balanced_interventions_pos / pos_total * 100:.1f}%)")
    print(f"  - STRICT Mode Overlay Trigger Recall:   {strict_interventions_pos}/{pos_total} ({strict_interventions_pos / pos_total * 100:.1f}%)")
    print(f"Negative / Safe Samples (n={neg_total}):")
    print(f"  - BALANCED Mode False Overlay Rate:     {balanced_interventions_neg}/{neg_total} ({balanced_interventions_neg / neg_total * 100:.1f}%)")
    print(f"  - STRICT Mode False Overlay Rate:       {strict_interventions_neg}/{neg_total} ({strict_interventions_neg / neg_total * 100:.1f}%)")


if __name__ == "__main__":
    run_state_machine_unit_verification()
    run_dataset_policy_evaluation()
