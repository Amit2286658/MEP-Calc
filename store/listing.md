# Uptodown submission kit — MEP Calc

Upload-ready assets and copy for the Developers Console. Everything here is
generated from the current build; screenshots are real captures of the app
running on a Pixel 4 (light), a Pixel 6 Pro (dark) and a Pixel Tablet
(light), in portrait and landscape.

## Files

| File | Where it goes |
|---|---|
| `icon-512.png` | App icon (512 × 512 PNG). Regenerate with `python3 store/render_icon.py`. |
| `screenshots/phone-light-0*.png` | Phone screenshots, light theme (1080 × 2280), 7 shots including the .math Studio. |
| `screenshots/phone-dark-0*.png` | Phone screenshots, dark theme (1440 × 3120), same 7 shots. |
| `screenshots/phone-*-landscape-*.png` | Landscape phone captures (2280 × 1080 light, 3120 × 1440 dark) showing the two-pane layout. |
| `screenshots/tablet-light-*.png` | Tablet captures (1600 × 2560 portrait, 2560 × 1600 landscape) of the 1.0.21 tablet shell. |
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
• Real-terms currency analysis: pick a From and To year and see how a currency
  actually appreciated or depreciated after inflation, using World Bank CPI data
  (relative purchasing-power parity). The Insert button writes a ready-made
  inflation-adjusted conversion into the expression with your selections
  prefilled.
• The expression stays on screen after =, with the answer on the line below;
  Ans reuses that answer and M+/M− keep working with it.
• Math Studio: write and run your own .math modules with functions, units,
  structs and multi-file projects, right on the phone.
• Searchable catalog of every function with its signature and description.
• Calculation history, memory keys, angle modes, light and dark themes, and a
  tablet-friendly layout.

PRIVACY
No accounts. No advertising. No analytics. The app stores your history and
projects only on your device. Internet access is used solely to fetch currency
rates, inflation statistics and optional engine updates. Full policy:
https://github.com/Amit2286658/MEP-Calc/blob/main/PRIVACY.md

MEP Calc is open source. The engine is available at
https://github.com/Amit2286658/Math-Engine-MEP
```

## Version notes (build 1.0.21)

```
• The Currency tab now has a dedicated Insert button below the results. It
  writes a ready-made convert_real(amount, from, fromYear, to, toYear) call at
  the cursor with your selected currencies and years already filled in, and
  puts the cursor on the amount so you can type it straight away.
• Changing a From/To currency or year after inserting updates the call in
  place — the amount you typed is kept. convert_real converts between two
  currencies and restates the result in the target year, adjusting both sides
  for inflation with World Bank CPI data (relative purchasing-power parity).
• The = key no longer replaces the expression with the answer: the expression
  stays on the display and the result appears on the answer line below it.
  Ans now inserts the numeric answer into the expression, and M+/M− keep
  working with the evaluated answer.
• The Currency mini-keypad now uses the same full-window frosted backdrop as
  the function catalog, with the expression and answer displays kept sharp.
• Tablets and both orientations are supported; the Currency action bar stays
  reachable while the results scroll on short screens.
```

## Pre-upload checklist

1. Upload `icon-512.png` — not a screenshot crop and not an upscaled mipmap.
2. Upload 8 screenshots in this order: `phone-light-01`, `-02`, `-03`, `-05`,
   `-06`, `-07-studio`, `phone-dark-01`, `phone-dark-07-studio` (the rest are
   spares; every shot is a real capture, no mockups).
   Then add `phone-light-landscape-01`, `phone-light-landscape-05` and
   `phone-dark-landscape-05` after the portrait set, and
   `tablet-light-portrait-01` + `tablet-light-landscape-01` last to show the
   tablet shell in both orientations.
3. Paste the short and full description above.
4. Paste the privacy policy URL.
5. Upload `ecalc-release-v21.apk` (signed, universal, from the
   `ecalc-release-apk-v21` CI artifact) — **not** any `*-debug-*` or
   `*CI-ONLY*` artifact.
