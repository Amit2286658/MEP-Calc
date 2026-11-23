# MEP Calc — Privacy Policy

**Last updated: 23 November 2026**

MEP Calc ("the app") is developed and published by **Amit UtilitySoft** ("we",
"us"). This policy explains what the app does with data. It applies to the
Android application distributed under the package name `com.amitutilitysoft.mepcalc`.

## Summary

- MEP Calc has **no user accounts**, **no sign-in**, **no advertising**, and
  **no analytics or tracking SDKs**.
- We do **not** collect, sell, rent, or share personal information.
- All calculations, history, saved expressions and `.math` projects stay on your
  device in the app's private storage.
- The app uses the internet for exactly two optional, anonymous purposes:
  1. **Currency exchange rates** (Currency tab).
  2. **Math-engine patch updates** (new function definitions for the built-in
     calculation engine).

## What the app stores on your device

The app writes only to its own private app storage, which other apps cannot
read and which is removed when you uninstall the app:

| Data | Purpose | Leaves the device? |
|---|---|---|
| Calculation history & saved expressions | Your convenience | No |
| `.math` module / project files you create | The Math Studio editor | No |
| Downloaded engine patches and their compiled cache | Faster start-up / offline use | No |
| Theme, keypad and precision preferences | Remembering your settings | No |

## Network requests the app makes

The app declares the `INTERNET` permission. It is used only as follows:

1. **Currency rates.** When you open the Currency tab, the app requests a public
   exchange-rate table (from `frankfurter.app` / `frankfurter.dev`,
   `open.er-api.com`, or `currency-api.pages.dev` via jsDelivr, tried in turn
   until one responds) so that conversions use up-to-date rates. The request
   contains **no identifiers, no account data, and no personal information** —
   only the standard HTTP request needed to fetch a static rate table. The last
   successfully fetched table is cached on the device so the tab also works
   offline.
2. **Engine patch updates.** On start-up the app may request a small JSON index
   and plain-text `.math` definition files from the project's public GitHub
   repository (via jsDelivr or `raw.githubusercontent.com`). These downloads add
   new mathematical functions to the built-in engine. They contain only the
   patch source code, verified with SHA-256; **no data about you is sent**.

The app performs no other network communication. There are no ads, no crash
reporting services, and no third-party analytics.

## Data we receive

We do not operate a server and we do not receive any personal data from the
app. Because currency rates and patches are fetched from public content
networks, those providers may see the usual technical request metadata (such as
an IP address) in their own server logs; that processing is described by their
respective privacy policies. We have no access to those logs and receive no
reports from them.

## Permissions

| Permission | Why it is needed |
|---|---|
| `INTERNET` | Fetching currency exchange rates and optional engine patch updates. |

No location, camera, microphone, contacts, storage, phone-state, or advertising
permissions are requested.

## Children

The app is a general-purpose calculator and is not directed at children under
13. We do not knowingly collect any personal data from anyone, including
children.

## Data deletion

Uninstalling the app removes all app data from your device. Because we hold no
data about you, there is nothing for us to delete on our side.

## Changes to this policy

If this policy changes, the updated version will be published at this URL with
a new "Last updated" date. Continued use of the app after an update means you
accept the revised policy.

## Contact

Questions about this policy or about the app:

**Amit UtilitySoft**
Email: amitkumar13234353@gmail.com

Source code: <https://github.com/Amit2286658/MEP-Calc>
