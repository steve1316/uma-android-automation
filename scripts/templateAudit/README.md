# Template Audit

Scores every component template against reference screenshots of the two guaranteed screens, 1080x1920 and 1080x2340. It flags any template that
matches on one screen size but not the other.

It mirrors the library's match: grayscale `TM_CCOEFF_NORMED`, the default 0.8 confidence, and the scales the library tries on each screen size.

## Usage

```
pip install -r requirements.txt
python template_audit.py --screens <1920 screenshots dir> --screens <2340 screenshots dir>
```

Screenshots of other sizes are skipped. Pass `--tall-scales 1.0` to see which templates only match on 1080x2340 because of the library's extra shrunk scale.

## Reading the output

| Verdict | Meaning |
| --- | --- |
| `only-1920` / `only-2340` | Matches on one screen size only. Either the template was cropped on a different device, or the reference set lacks that screen at the other size. |
| `ok-shrunk` | Matches on both, but on 1080x2340 only at a shrunk scale. |
| `unseen` | No reference screenshot shows it at either size. |
| `ok` | Matches on both at full size. |

Each row names the screenshot that scored best, so a flagged template can be checked by eye.
