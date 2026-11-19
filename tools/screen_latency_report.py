#!/usr/bin/env python3
"""Summarize ScreenLatency logcat output; incomplete screens never become zero-ms wins."""
import argparse
import json
import math
import re
import statistics
from pathlib import Path


def summarize(text):
    runs = {}
    for line in text.splitlines():
        match = re.search(r"screen_latency id=(\d+) event=(\w+) (ms|count)=(\d+)", line)
        if not match:
            continue
        run_id, event, _, value = match.groups()
        run = runs.setdefault(run_id, {})
        # Draw counts are cumulative; milestones are emitted once.
        run[event] = max(run.get(event, 0), int(value))
        for resource in ("cpu_ms", "heap_bytes"):
            sample = re.search(rf"{resource}=(\d+)", line)
            if sample:
                run["max_sampled_" + resource] = max(run.get("max_sampled_" + resource, 0), int(sample[1]))

    def distribution(values):
        values = sorted(values)
        if not values:
            return None
        return {"n": len(values), "median_ms": statistics.median(values),
                "p95_ms": values[math.ceil(len(values) * .95) - 1], "worst_ms": values[-1]}

    def delta(a, b):
        return distribution([r[b] - r[a] for r in runs.values()
                             if a in r and b in r and r[b] >= r[a]])

    regions = sum(r.get("regions", 0) for r in runs.values())
    drawn = sum(r.get("drawn", 0) for r in runs.values())
    return {
        "screens_started": sum("T0" in r for r in runs.values()),
        "screens_fully_drawn": sum("T7" in r for r in runs.values()),
        "first_sentence": delta("T0", "T6"),
        "full_translation": delta("T0", "T7"),
        "detected_dialogue_regions": regions,
        "successfully_drawn_dialogue_groups": drawn,
        "undisplayed_region_rate": (regions - drawn) / regions if regions else None,
        # Only finalized translation passes contribute; aborted captures are separate.
        "translation_failure_rate": (
            sum(max(0, r.get("regions", 0) - r["translation_finished"]) for r in runs.values()
                if "translation_finished" in r) /
            sum(r.get("regions", 0) for r in runs.values() if "translation_finished" in r)
            if any(r.get("regions", 0) for r in runs.values() if "translation_finished" in r) else None),
        "stages": {name: delta(a, b) for name, a, b in [
            ("scan_capture", "T0", "T1"), ("preprocessing", "T1", "T2"),
            ("ocr", "T2", "T3"), ("grouping_filtering", "T3", "T4"),
            ("request_preparation", "T4", "T5"),
            ("translation_to_first_draw", "T5", "T6"),
            ("translation_to_all_drawn", "T5", "T7")]},
        "per_screen": runs,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--baseline", type=Path, help="Previous JSON report (same device/dataset/settings)")
    args = parser.parse_args()
    report = summarize(args.log.read_text())
    if args.baseline:
        baseline = json.loads(args.baseline.read_text())
        report["delta_vs_baseline_ms"] = {
            metric: {stat: report[metric][stat] - baseline[metric][stat]
                     for stat in ("median_ms", "p95_ms", "worst_ms")}
            for metric in ("first_sentence", "full_translation")
            if report[metric] and baseline.get(metric)
        }
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
