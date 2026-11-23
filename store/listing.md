# Uptodown submission kit — MEP Calc

Upload-ready assets and copy for the Developers Console. Everything here is
generated from the current build; screenshots are real captures of the app
running on a Pixel 4 (light), Pixel 6 Pro (dark) and a 10" tablet.

## Files

| File | Where it goes |
|---|---|
| `icon-512.png` | App icon (512 × 512 PNG). Regenerate with `python3 store/render_icon.py`. |
| `screenshots/phone-light-0*.png` | Phone screenshots, light theme (1080 × 2280). |
| `screenshots/phone-dark-0*.png` | Phone screenshots, dark theme (1440 × 3120). |
| `screenshots/tablet-0*.png` | Tablet screenshots (2560 × 1600). |
| `../PRIVACY.md` | Privacy policy URL: <https://github.com/Amit2286658/MEP-Calc/blob/main/PRIVACY.md> |

## Listing fields

**Title** (max 50 chars)

```
MEP Calc — Scientific Calculator
```

**Short description** (max 70 chars, 65 used)

```
Offline scientific calculator with CAS, units, currency and .math
```

**Category:** Education / Productivity → *Calculators*
**Price:** Free
**Ads:** None
**In-app purchases:** None
**Content rating:** Everyone
**Developer:** Amit UtilitySoft
**Support email:** amitkumar13234353@gmail.com
**Privacy policy:** https://github.com/Amit2286658/MEP-Calc/blob/main/PRIVACY.md
**Source code:** https://github.com/Amit2286658/MEP-Calc

## Full description

```
MEP Calc is a fast, ad-free scientific calculator built on its own native C11
math engine. It does basic arithmetic, algebra, calculus, unit conversion,
computer-storage conversion and live currency conversion — and it keeps working
with no internet connection.

WHY MEP CALC IS DIFFERENT
Most calculators are a thin front end over the phone's built-in arithmetic.
MEP Calc ships a complete symbolic and numeric engine written in C11. It boots
from a precompiled standard library in constant time, so results are instant
even for long expressions, and every function is documented in the built-in
catalog.

FEATURES
• Basic, extended and trigonometric keypads with a live expression display and
  tap-to-place cursor.
• Full operator precedence, implicit multiplication (2x, 2π), powers, roots,
  factorials, logarithms and constants.
• Symbolic differentiation, integration and equation solving, plus numeric
  calculus: limits, Taylor series, ODE stepping, minima and maxima.
• Over 850 built-in functions across arithmetic, algebra, linear algebra,
  geometry, physics, chemistry and biology.
• 7-dimensional SI unit system with suffix conversion: "25 km into m",
  "180 deg into rad", imperial and US customary units.
• Computer memory and storage conversion: bits, bytes, and both decimal
  (KB/MB/GB/TB/PB) and binary (KiB/MiB/GiB/TiB/PiB) prefixes.
• Currency converter with From/To pickers and editable rates; the last fetched
  rate table is cached so it also works offline.
• Math Studio: write and run your own .math modules with functions, units,
  structs and multi-file projects, right on the phone.
• Searchable catalog of every function with its signature and description.
• Calculation history, memory keys, angle modes, light and dark themes, and a
  tablet-friendly layout.

PRIVACY
No accounts. No advertising. No analytics. The app stores your history and
projects only on your device. Internet access is used solely to fetch currency
rates and optional engine updates. Full policy:
https://github.com/Amit2286658/MEP-Calc/blob/main/PRIVACY.md

MEP Calc is open source. The engine is available at
https://github.com/Amit2286658/Math-Engine-MEP
```

## Version notes (build 1.0.13)

```
• Universal build: the native math engine now ships for 64-bit ARM, 32-bit ARM
  and x86_64, so the app runs on every supported device instead of crashing on
  32-bit and emulator-only configurations.
• The function library (prime and number theory, number-to-words, memory and
  storage conversion) is now bundled with the app and loads on first launch
  even without a network connection.
• New: computer memory / storage size converter (bit, byte, KB–PB, KiB–PiB).
• Privacy policy link added to the in-app info window.
```

## Pre-upload checklist

1. Upload `icon-512.png` — not a screenshot crop and not an upscaled mipmap.
2. Upload at least 4 phone screenshots and 2 tablet screenshots.
3. Paste the short and full description above.
4. Paste the privacy policy URL.
5. Upload `ecalc-release-v13.apk` (signed, universal, from the
   `ecalc-release-apk-v13` CI artifact) — **not** any `*-debug-*` or
   `*CI-ONLY*` artifact.
