# OpenBank public website

Static HTML/CSS/JS; no frontend framework or client-side build. The customer app and
admin portal remain separate products. This page presents them, it does not simulate them.

## Preview

From the repository root:

```sh
python3 -m http.server 8766 --bind 127.0.0.1 --directory openbank-infra/web/landing
```

Open `http://127.0.0.1:8766`. Check the homepage, TestFlight form, demo dialogs,
`platform.html` search, `labs.html` and `classic.html` at desktop and mobile widths.

## Files and sources

- `index.html`, `styles.css`: current design. App neutrals, Space Grotesk / Space Mono,
  blue accent from the app's existing palette. The lions are guides; bots represent assistance.
- `platform.html`: capability navigation and the **complete** repository module inventory.
  The marked catalog section is generated; never edit it by hand.
- `labs.html`: shared banking kernel overview, with links to the corresponding library modules.
- `catalog.js`: local search only. All modules are readable without JavaScript.
- `main.js`: existing demo modal and TestFlight integration, plus a hash entry point for
  opening the sandbox instructions from Labs.
- `classic.html`, `classic.css`: previous homepage, visibly historical and excluded from indexing.
  Old links to `platform.html` now reach the current platform page.
- `assets/app-screen-current.png`: current app screenshot supplied for the redesigned page, used without changing pixels. The previous `app-screen.png` remains on `classic.html`.
- `assets/agent-control-room.webp`: lossless encoding of the supplied presentation's
  actual admin capture (slide 13, image14.png). It is a **recorded demo view**, not telemetry.
- `assets/ob-mark-complete.svg`: complete web mark based on the previously cropped OB mark.
- `assets/openbank-labs-kernel.jpg`: original OpenBank Labs artwork from the linked post.
- `assets/agent-crew.webp`: original artwork copied from the admin portal.
- `assets/explorers-hero.webp`: new AI-generated illustration using the supplied lion,
  lioness and bot references. It contains no product UI.

The presentation is `OpenBank_Bank_Accidentally_final2.pptx`. It is a visual source,
not a source of current metrics. No third-party banking logos or presentation credentials
are imported. Product screenshots are labelled as recorded views.

## Refresh the public inventory

Install the admin UI's dependencies as documented there (the existing catalog generator
uses Node and `yaml`), then run:

```sh
python3 openbank-infra/web/generate-public-catalog.py
python3 openbank-infra/web/generate-public-catalog.py --check
node --test openbank-infra/web/tests/landing.test.cjs
```

The updater invokes `openbank-admin-ui/scripts/generate-catalog.mjs`; it does not create
another inventory definition. `AREAS` in the updater is an editorial navigation mapping,
not the governance `dataDomain` taxonomy. Every module appears once. New/unmapped modules
remain visible in Shared libraries & delivery. The source revision and date are explicit.
Regenerate after catalog input changes; deployment does this before contacting AWS.

## TestFlight: preserve the integration

The same form ID, action, method, hidden fields, required email and opt-in consent,
honeypot, hCaptcha sitekey, AJAX handler, status text and privacy dialog are retained.
Only the widget theme changes from dark to light. Web3Forms receives the same payload.
Public form routing identifiers are the existing browser-visible ones, not secrets.

Tests intercept the submit endpoint locally: **never send test signups to Web3Forms**.
A real end-to-end delivery test requires a user-authorized address and CAPTCHA completion.
The redesign does not change invitation handling or retention policy.

## Publishing and rollback

`../deploy.sh` uses the existing S3/CloudFront deployment. Do not deploy a branch before
required PR review. The previous website remains linked from the footer; the old page
has a historical notice. Roll back the website commit through the normal reviewed flow.
The app association and security discovery files are unchanged.

No health, compliance percentage or cost is presented as live on the public platform
page. Links lead to the actual admin evidence surfaces, which require sign-in.
