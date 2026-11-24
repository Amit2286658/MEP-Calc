# Uptodown submission kit — MEP Calc

Upload-ready assets and copy for the Developers Console. Everything here is
generated from the current build; screenshots are real captures of the app
running on a Pixel 4 (light), Pixel 6 Pro (dark) and a 10" tablet.

## Files

| File | Where it goes |
|---|---|
| `icon-512.png` | App icon (512 × 512 PNG). Regenerate with `python3 store/render_icon.py`. |
| `screenshots/phone-light-0*.png` | Phone screenshots, light theme (1080 × 2280), 7 shots including the .math Studio. |
| `screenshots/phone-dark-0*.png` | Phone screenshots, dark theme (1440 × 3120), 7 shots. |
| `screenshots/tablet-0*.png` | Tablet screenshots, portrait (1600 × 2560), 7 shots. |
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

## Version notes (build 1.0.15)

```
• First release under the com.amitutilitysoft.mepcalc application ID, so the app
  is unmistakably MEP Calc by Amit UtilitySoft.
• Universal native maths engine for 64-bit ARM, 32-bit ARM and x86_64.
• The function library (prime and number theory, number-to-words, memory and
  storage conversion) ships inside the app and loads on first launch offline.
• Patch updates published on GitHub are picked up automatically on the next
  launch; the bundled copy is only a fallback.
• Math Studio now greets first-time visitors with a one-line tip: four demo
  modules are preloaded, press ▶ Run to execute the open one, ☰ opens files.
• Clearer wording in the Studio project dialogs (no engine internals).
• New: computer memory / storage size converter (bit, byte, KB–PB, KiB–PiB).
• Privacy policy link added to the in-app info window.
```

## Pre-upload checklist

1. Upload `icon-512.png` — not a screenshot crop and not an upscaled mipmap.
2. Upload the phone screenshots (light or dark, all 7) plus the 7 tablet ones.
3. Paste the short and full description above.
4. Paste the privacy policy URL.
5. Upload `ecalc-release-v15.apk` (signed, universal, from the
   `ecalc-release-apk-v15` CI artifact) — **not** any `*-debug-*` or
   `*CI-ONLY*` artifact.
