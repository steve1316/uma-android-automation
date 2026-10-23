"""Scores every component template against reference screenshots of the two guaranteed screens, 1080x1920 and 1080x2340.

A template cropped on one device can quietly fail on the other, so this flags any template that matches on one screen size but not the other. It mirrors the
library's match: grayscale TM_CCOEFF_NORMED, the default 0.8 confidence, and the scales the library tries for each screen size.

Usage:
    python template_audit.py --screens <dir> [--screens <dir> ...] [--templates <dir>] [--tall-scales 1.0,0.96] [--confidence 0.8]
"""

import argparse
import glob
import os
from multiprocessing import Pool

import cv2

DEFAULT_TEMPLATES = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "assets", "images", "components")
SCREEN_SIZES = {(1080, 1920): "1920", (1080, 2340): "2340"}

_variants: dict = {}


def _init(templates: dict, scales: dict) -> None:
    """Resizes each template once per scale the library tries, so every worker process reuses them across screenshots.

    Args:
        templates: Template name to grayscale image.
        scales: Screen group to the scales the library tries there.
    """
    global _variants
    _variants = {
        group: {
            name: [(scale, t if scale == 1.0 else cv2.resize(t, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)) for scale in group_scales]
            for name, t in templates.items()
        }
        for group, group_scales in scales.items()
    }


def _score_screen(path: str) -> tuple:
    """Finds the best score and scale of every template on one screenshot.

    Args:
        path: The screenshot path.

    Returns:
        The screen group, the file name, and a dict of template name to (score, scale). The group is None for a screen size that is not guaranteed.
    """
    screen = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    group = None if screen is None else SCREEN_SIZES.get((screen.shape[1], screen.shape[0]))
    if group is None:
        return None, os.path.basename(path), {}

    results = {}
    for name, variants in _variants[group].items():
        best = (0.0, 1.0)
        for scale, resized in variants:
            if resized.shape[0] > screen.shape[0] or resized.shape[1] > screen.shape[1] or min(resized.shape[:2]) < 2:
                continue
            score = cv2.minMaxLoc(cv2.matchTemplate(screen, resized, cv2.TM_CCOEFF_NORMED))[1]
            if score > best[0]:
                best = (score, scale)
        results[name] = best
    return group, os.path.basename(path), results


def main() -> None:
    """Scores the templates and prints one row per template, worst first."""
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--screens", action="append", required=True, help="A folder of reference screenshots. Repeat for more folders.")
    parser.add_argument("--templates", default=DEFAULT_TEMPLATES, help="The component templates folder.")
    parser.add_argument("--tall-scales", default="1.0,0.96", help="Scales the library tries on 1080x2340, comma separated.")
    parser.add_argument("--confidence", type=float, default=0.8, help="The match threshold.")
    args = parser.parse_args()

    templates = {}
    for path in sorted(glob.glob(os.path.join(args.templates, "**", "*.png"), recursive=True)):
        image = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
        if image is not None:
            templates[os.path.relpath(path, args.templates).replace(os.sep, "/")[:-4]] = image
    scales = {"1920": [1.0], "2340": [float(s) for s in args.tall_scales.split(",")]}
    screens = [p for folder in args.screens for p in sorted(glob.glob(os.path.join(folder, "*.png")))]

    # Per template and screen group: (best score, scale, screenshot).
    best: dict = {name: {"1920": (0.0, 1.0, ""), "2340": (0.0, 1.0, "")} for name in templates}
    counts = {"1920": 0, "2340": 0}
    with Pool(initializer=_init, initargs=(templates, scales)) as pool:
        for group, file_name, results in pool.imap_unordered(_score_screen, screens, chunksize=4):
            if group is None:
                continue
            counts[group] += 1
            for name, (score, scale) in results.items():
                if score > best[name][group][0]:
                    best[name][group] = (score, scale, file_name)

    print(f"Screens: {counts['1920']} at 1080x1920, {counts['2340']} at 1080x2340. Confidence {args.confidence}.")
    rows = []
    for name, groups in best.items():
        hit = {g: groups[g][0] >= args.confidence for g in groups}
        if hit["1920"] and hit["2340"]:
            verdict = "ok" if groups["2340"][1] == 1.0 else "ok-shrunk"
        elif hit["1920"]:
            verdict = "only-1920"
        elif hit["2340"]:
            verdict = "only-2340"
        else:
            verdict = "unseen"
        rows.append((verdict, name, groups))

    order = {"only-1920": 0, "only-2340": 1, "ok-shrunk": 2, "unseen": 3, "ok": 4}
    for verdict, name, groups in sorted(rows, key=lambda r: (order[r[0]], r[1])):
        g1, g2 = groups["1920"], groups["2340"]
        print(f"{verdict:10s} {name:60s} 1920={g1[0]:.3f} ({g1[2]})  2340={g2[0]:.3f}@{g2[1]} ({g2[2]})")


if __name__ == "__main__":
    main()
