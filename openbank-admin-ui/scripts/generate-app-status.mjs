// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Generate the customer-app dossier artefact (ADR-0074). Mirrors the
// generate-catalog.mjs / generate-governance.mjs bake pattern: derive what code
// already knows, declare only the curatorial, emit a committed JSON the admin-ui
// loads as a static snapshot (ADR-0029 #7: derived, never hand-edited).
//
// Two layers, joined here:
//   DERIVED  — extracted from the customer app's source (openbank-app), never
//              transcribed: useFakeData, edge URL, OAuth scopes, cert-pinning
//              state from AppConfig.kt; app version from version.txt. The README
//              drift (it claimed F0/useFakeData=true while the code says false)
//              is exactly why these must be derived.
//   DECLARED — the curatorial facts no code expresses (capability list, lens
//              tagging, gap narratives, decision-missing judgment) read from
//              openbank-app/app-status.yaml, which lives next to that code.
//
// Honest by construction: a missing source yields null + a recorded gap, never a
// fabricated value.
//
// Per-capability `derivedStatus` (issue #26): the curatorial `status` field
// (live|partial|planned) is the single most change-prone fact in app-status.yaml
// and it rotted — 5 of 13 capabilities under-stated as of 2026-06-08, fixed by
// hand in #25. `deriveCapabilityStatus` below computes a `derivedStatus` for the
// SUBSET of capabilities that are cheap presence/shape checks against the app
// source (tls-pinning, sca-device-key, auth-pkce, credential-storage,
// diagnostics, payment-initiation, qrless-pay). It is diffed against the curatorial `status`
// in --check mode and printed as a `::warning` on disagreement — advisory, not
// enforced (unlike the derived-facts diff below): status has genuinely ambiguous
// cases (e.g. an iOS-live/Android-stub split) the curator must still be able to
// call `partial` even when a single signal reads `live`. See ADR-0074 follow-up
// (2026-07-05) for which capabilities are derivable vs stay curatorial.
//
// Cross-repo by design: the customer app is a SEPARATE repo (JiRaska/openbank-app).
// This is a new integration, not the in-repo monorepo walk that generate-catalog
// does — the app source path is an explicit --app-repo arg, never a baked sibling
// path. In CI both repos provide it via an actions/checkout of the app source
// (ADR-0074 invariant 3, transport): the platform repo holds the COMMITTED
// app-status.json (the baked artefact the Dockerfile COPYs); openbank-app CI
// regenerates it from its own source and publishes it back. The committed copy is
// the transport — the build never reaches across a filesystem to a sibling.
//
// Modes:
//   (write, default)  emit app-status.json. If the app source is UNAVAILABLE and a
//                     committed artefact already exists at --out, the committed copy
//                     is PRESERVED (never clobbered with a null-derived stub) — a
//                     run without the app checkout must not erase the transport copy.
//   --check           do not write; regenerate in memory and diff the `derived`
//                     block against the committed artefact (--against, default --out).
//                     This is the ADR-0074 invariant-5 content check that would have
//                     caught the README-vs-code drift. ADVISORY by default: prints a
//                     ::warning and exits 0 on drift. Add --enforce to exit 1 on drift
//                     (the advisory→enforce flip, mirroring ADR-0071 / ADR-0034).
//
// Usage:
//   node scripts/generate-app-status.mjs --app-repo <path> [--out <file>]
//   node scripts/generate-app-status.mjs --app-repo <path> --check [--against <file>] [--enforce]
// Defaults: app-repo = <admin-ui>/../../openbank-app (LOCAL DEV ONLY), out = ./app-status.json

import { readFileSync, writeFileSync, existsSync } from 'fs'
import path from 'path'
import { fileURLToPath, pathToFileURL } from 'url'
import { parse as parseYaml } from 'yaml'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}
const hasFlag = (flag) => args.includes(flag)

const ADMIN_UI = path.resolve(__dirname, '..')
const PLATFORM_REPO = path.resolve(ADMIN_UI, '..')
const APP_REPO = path.resolve(getArg('--app-repo', path.resolve(PLATFORM_REPO, '..', 'openbank-app')))
const OUT = path.resolve(getArg('--out', path.resolve(ADMIN_UI, 'app-status.json')))
const CHECK = hasFlag('--check')
const ENFORCE = hasFlag('--enforce')
const AGAINST = path.resolve(getArg('--against', OUT))

function readText(p) {
  try {
    return readFileSync(p, 'utf8')
  } catch {
    return null
  }
}

// ── DERIVED layer: extract from AppConfig.kt + version.txt ───────────────────
// Honest extraction: a value we cannot find stays null. We never invent.
function deriveFromCode(appRepo) {
  const gaps = []
  const cfgPath = path.join(appRepo, 'shared/src/commonMain/kotlin/tech/openbank/app/config/AppConfig.kt')
  const cfg = readText(cfgPath)
  const versionTxt = readText(path.join(appRepo, 'version.txt'))

  if (!cfg) {
    gaps.push(`AppConfig.kt not found at ${path.relative(appRepo, cfgPath)} — derived facts unavailable`)
    return {
      derived: {
        version: versionTxt ? versionTxt.trim() : null,
        useFakeData: null,
        edgeBaseUrl: null,
        keycloakIssuer: null,
        oauthScopes: null,
        certPinningConfigured: null,
        sourceAvailable: false,
      },
      gaps,
    }
  }

  const matchStr = (name) => {
    const m = cfg.match(new RegExp(`val\\s+${name}\\s*=\\s*"([^"]*)"`))
    return m ? m[1] : null
  }
  const useFakeDataMatch = cfg.match(/val\s+useFakeData\s*=\s*(true|false)/)
  const useFakeData = useFakeDataMatch ? useFakeDataMatch[1] === 'true' : null

  // OAUTH_SCOPES = listOf("a", "b", ...) → pull the quoted entries
  const scopesBlock = cfg.match(/OAUTH_SCOPES\s*=\s*listOf\(([^)]*)\)/)
  const oauthScopes = scopesBlock
    ? Array.from(scopesBlock[1].matchAll(/"([^"]+)"/g)).map((m) => m[1])
    : null

  // Cert pinning: find the PINNED_SPKI_HASHES line FIRST, then read its value.
  // Honest by construction (ADR-0074): if the field is absent/renamed we return
  // null — never default to `true`, which would claim pinning is configured when
  // we simply couldn't see it (the exact false-security-claim the dossier forbids).
  const pinsLine = cfg.match(/PINNED_SPKI_HASHES[^\n]*/)
  const certPinningConfigured = pinsLine ? !/emptySet\(\)/.test(pinsLine[0]) : null

  if (useFakeData === null) gaps.push('could not parse useFakeData from AppConfig.kt')
  if (certPinningConfigured === null) gaps.push('could not find PINNED_SPKI_HASHES in AppConfig.kt — cert-pinning state unknown')

  return {
    derived: {
      version: versionTxt ? versionTxt.trim() : null,
      useFakeData,
      edgeBaseUrl: matchStr('EDGE_BASE_URL'),
      keycloakIssuer: matchStr('KEYCLOAK_ISSUER'),
      oauthClientId: matchStr('OAUTH_CLIENT_ID'),
      oauthScopes,
      certPinningConfigured,
      // pinning is only ACTIVE off-fakedata with hashes present (AppConfig.CERT_PINNING_ENABLED)
      certPinningActive: useFakeData === false && certPinningConfigured === true,
      sourceAvailable: true,
    },
    gaps,
  }
}

// ── Per-capability derivedStatus: cheap code presence/shape signals ──────────
// Each check is a targeted read + string/regex match against ONE source file,
// same style as deriveFromCode above. A signal that can't find its source file
// returns null (never guesses) and is surfaced as a gap; the capability's
// derivedStatus itself is then also null and is skipped in the drift diff — an
// absent signal has no verdict, exactly like the derived-facts layer.
export function deriveCapabilityStatus(appRepo, certPinningConfigured) {
  const gaps = []
  const read = (rel) => {
    const p = path.join(appRepo, rel)
    const text = readText(p)
    if (text === null) gaps.push(`${rel} not found — derivedStatus signal unavailable`)
    return text
  }

  const signals = {}

  // tls-pinning: already extracted in deriveFromCode (certPinningConfigured) —
  // reuse it rather than re-reading AppConfig.kt a second time.
  signals['tls-pinning'] =
    certPinningConfigured === null ? null : certPinningConfigured ? 'live' : 'partial'

  // sca-device-key: ensureKeyPair() on Android must actually call the platform
  // KeyPairGenerator against AndroidKeyStore, not a stub that just returns true.
  {
    const src = read('shared/src/androidMain/kotlin/tech/openbank/app/security/ScaDeviceKey.android.kt')
    signals['sca-device-key'] =
      src === null ? null : /KeyPairGenerator/.test(src) && /AndroidKeyStore/.test(src) ? 'live' : 'partial'
  }

  // auth-pkce: both platform OAuthLaunchers must compute a real SHA-256 PKCE
  // challenge (not a hardcoded/omitted code_challenge), AND LoginScreen must
  // actually wire the flow (via the shared AuthService, not a placeholder).
  {
    const ios = read('shared/src/iosMain/kotlin/tech/openbank/app/auth/OAuthLauncher.ios.kt')
    const android = read('shared/src/androidMain/kotlin/tech/openbank/app/auth/OAuthLauncher.android.kt')
    const login = read('composeApp/src/commonMain/kotlin/tech/openbank/app/ui/LoginScreen.kt')
    const iosPkce = ios !== null && /CC_SHA256|sha256/i.test(ios) && /code_challenge/.test(ios)
    const androidPkce =
      android !== null && /MessageDigest\.getInstance\(\s*"SHA-256"\s*\)/.test(android) && /code_challenge/.test(android)
    const loginWired = login !== null && /AuthService/.test(login)
    signals['auth-pkce'] =
      ios === null || android === null || login === null ? null : iosPkce && androidPkce && loginWired ? 'live' : 'planned'
  }

  // credential-storage: Android store must be backed by AndroidKeyStore (a real
  // KeyStore/KeyGenerator, AES-GCM), not the F0 in-memory placeholder. The
  // "AndroidKeyStore" provider name is usually held in a named constant (not
  // inlined at the call site) so match KeyStore.getInstance(...) + the provider
  // string appearing anywhere in the file, same looseness as sca-device-key.
  {
    const src = read('shared/src/androidMain/kotlin/tech/openbank/app/security/SecureCredentialStore.android.kt')
    signals['credential-storage'] =
      src === null
        ? null
        : /KeyStore\.getInstance/.test(src) && /AndroidKeyStore/.test(src) && /KeyGenerator/.test(src)
          ? 'live'
          : 'planned'
  }

  // diagnostics: partial/live iff a DebugMenu/DebugTrigger surface exists at all
  // (a loose presence check — the curatorial layer still calls the F1/F2 split).
  {
    const menu = read('composeApp/src/commonMain/kotlin/tech/openbank/app/debug/DebugMenu.kt')
    const trigger = read('composeApp/src/commonMain/kotlin/tech/openbank/app/debug/DebugTrigger.kt')
    signals['diagnostics'] = menu === null && trigger === null ? null : menu !== null && trigger !== null ? 'partial' : 'planned'
  }

  // payment-initiation: SendScreen must wire BOTH PaymentApi and ScaApi through
  // an initiate+approve pair — the full SCA-gated payment chain, not just a UI shell.
  {
    const src = read('composeApp/src/commonMain/kotlin/tech/openbank/app/ui/SendScreen.kt')
    const wired =
      src !== null &&
      /PaymentApi/.test(src) &&
      /ScaApi/.test(src) &&
      /initiateChallenge/.test(src) &&
      /approveChallenge/.test(src) &&
      /createDomesticPayment/.test(src)
    signals['payment-initiation'] = src === null ? null : wired ? 'live' : 'planned'
  }

  // qrless-pay: two independent halves, and the interesting one is a switch.
  // PAYEE is live when RequestScreen actually advertises a minted session
  // (startReceiving); it is the QR-equivalent surface and ships on. PAYER is held
  // behind NearPay.PAYER_DISCOVERY_ENABLED, off until the threat-model §8 rollout
  // gates are met. Both halves on ⇒ live, exactly one ⇒ partial, neither ⇒ planned.
  //
  // This capability is here because it rotted twice in one week (openbank-app #445,
  // #455): the yaml said `planned`/"NO implementation" while the protocol core, both
  // transports and the payee surface were merged, and the correction was itself stale
  // four PRs later. Both were single-field drifts of exactly the kind this signal sees.
  //
  // A MISSING flag constant is null, never a status. The constant exists precisely
  // because dormancy used to rest on nothing happening to call startDiscovery — a
  // control any refactor removes without noticing. If it is gone, this check has no
  // verdict, and saying so is the honest answer; guessing the safer-looking one would
  // hide the removal it is here to notice.
  {
    const proto = read('shared/src/commonMain/kotlin/tech/openbank/app/payment/nearpay/NearPay.kt')
    const request = read('composeApp/src/commonMain/kotlin/tech/openbank/app/ui/RequestScreen.kt')
    const flag = proto === null ? null : proto.match(/PAYER_DISCOVERY_ENABLED\s*=\s*(true|false)/)
    if (proto !== null && !flag) {
      gaps.push('NearPay.PAYER_DISCOVERY_ENABLED not found in NearPay.kt — qrless-pay derivedStatus signal unavailable')
    }
    if (proto === null || request === null || !flag) {
      signals['qrless-pay'] = null
    } else {
      const payerOn = flag[1] === 'true'
      const payeeWired = /startReceiving\s*\(/.test(request)
      signals['qrless-pay'] = payerOn && payeeWired ? 'live' : payerOn || payeeWired ? 'partial' : 'planned'
    }
  }

  return { signals, gaps }
}

// ── DECLARED layer: curatorial app-status.yaml ───────────────────────────────
function loadDeclared(appRepo) {
  const ymlPath = path.join(appRepo, 'app-status.yaml')
  const raw = readText(ymlPath)
  if (!raw) {
    return { declared: null, gaps: [`app-status.yaml not found at ${path.relative(appRepo, ymlPath)}`] }
  }
  try {
    return { declared: parseYaml(raw), gaps: [] }
  } catch (e) {
    return { declared: null, gaps: [`app-status.yaml failed to parse: ${e.message}`] }
  }
}

// CLI entrypoint only — importing this module (tests) must not read the app repo,
// write the artefact or exit the process.
function main() {
  // ── Join ─────────────────────────────────────────────────────────────────────
  const { derived, gaps: derivedGaps } = deriveFromCode(APP_REPO)
  const { declared, gaps: declaredGaps } = loadDeclared(APP_REPO)
  const { signals: statusSignals, gaps: statusGaps } = deriveCapabilityStatus(APP_REPO, derived.certPinningConfigured)

  // Attach derivedStatus only to capabilities we have a signal for; everything
  // else (ui-stack, shared-domain, customer-edge, keycloak-realm, crash-monitoring,
  // dossier) stays purely curatorial — no cheap code check calls those, and guessing
  // one would be exactly the fabricated-value ADR-0074 forbids.
  const capabilities = (declared?.capabilities ?? []).map((c) =>
    Object.prototype.hasOwnProperty.call(statusSignals, c.id) ? { ...c, derivedStatus: statusSignals[c.id] } : c,
  )
  const decisionMissing = capabilities.filter((c) => c.decisionMissing === true)
  const byStatus = capabilities.reduce((acc, c) => {
    acc[c.status] = (acc[c.status] || 0) + 1
    return acc
  }, {})
  // status vs derivedStatus disagreement — the rot signal issue #26 exists to catch.
  // null derivedStatus (signal source unavailable) is never compared: no verdict.
  const statusDrift = capabilities.filter((c) => c.derivedStatus != null && c.derivedStatus !== c.status)

  const out = {
    schema: 'openbank.appstatus/v1',
    // generatedAt is intentionally omitted to keep the artefact diff-stable in CI
    // (the content check in ADR-0074 diffs the derived block, not a timestamp).
    app: declared?.app ?? { name: 'openbank-app' },
    asOf: declared?.asOf ?? null,
    // Stable repo identifier (not a local filesystem path) — keeps the artefact
    // byte-identical regardless of WHERE the app source is checked out in CI, so the
    // content check diffs source-derived facts, not the runner's directory layout.
    appRepo: declared?.app?.repo ?? 'JiRaska/openbank-app',
    derived,
    capabilities,
    summary: {
      total: capabilities.length,
      byStatus,
      decisionMissing: decisionMissing.map((c) => c.id),
    },
    gaps: [...derivedGaps, ...declaredGaps, ...statusGaps],
  }

  const serialized = JSON.stringify(out, null, 2) + '\n'

  function summarize() {
    console.log(
      `  derived: ${derived.sourceAvailable ? `version=${derived.version} useFakeData=${derived.useFakeData} edge=${derived.edgeBaseUrl}` : 'SOURCE UNAVAILABLE'}`,
    )
    console.log(`  capabilities: ${capabilities.length} (${Object.entries(byStatus).map(([k, v]) => `${k}=${v}`).join(', ')})`)
    if (decisionMissing.length) console.log(`  decision-missing: ${decisionMissing.map((c) => c.id).join(', ')}`)
    if (statusDrift.length) {
      console.log(`  ⚠ status drift: ${statusDrift.length}`)
      for (const c of statusDrift) console.log(`    - ${c.id}: yaml says '${c.status}' but code looks '${c.derivedStatus}'`)
    }
    if (out.gaps.length) console.log(`  ⚠ gaps: ${out.gaps.length}\n    - ${out.gaps.join('\n    - ')}`)
  }

  // Print one ::warning per drifted capability (issue #26). Always advisory,
  // independent of --enforce: unlike the derived-facts diff, curatorial `status`
  // is allowed to legitimately override a single code signal (e.g. an iOS-live/
  // Android-stub split the curator judges still `partial`) — a human call, so a
  // disagreement is a prompt to re-check the yaml, not necessarily a bug.
  function reportStatusDrift() {
    for (const c of statusDrift) {
      console.log(
        `::warning title=app-status status drift::dossier status is stale: ${c.id} code looks '${c.derivedStatus}' but yaml says '${c.status}'`,
      )
    }
  }

  // Read the committed artefact's `derived` block (the layer the content check
  // governs), tolerating an absent/garbled file by returning null.
  function committedDerived(file) {
    try {
      return JSON.parse(readFileSync(file, 'utf8')).derived ?? null
    } catch {
      return null
    }
  }

  // Field-by-field diff of two `derived` objects — the README-vs-code drift this
  // check exists to catch surfaces here (e.g. useFakeData: false → true).
  function diffDerived(committed, fresh) {
    const keys = Array.from(new Set([...Object.keys(committed ?? {}), ...Object.keys(fresh ?? {})])).sort()
    const lines = []
    for (const k of keys) {
      const a = JSON.stringify(committed?.[k])
      const b = JSON.stringify(fresh?.[k])
      if (a !== b) lines.push(`    ${k}: committed=${a} → regenerated=${b}`)
    }
    return lines
  }

  // ── --check: regenerate and diff the derived block (ADR-0074 invariant 5) ─────
  if (CHECK) {
    console.log(`app-status check (advisory${ENFORCE ? '→ENFORCE' : ''}) against ${path.relative(process.cwd(), AGAINST)}`)
    summarize()

    if (!derived.sourceAvailable) {
      // No app checkout ⇒ nothing to compare. Degrade to a skip, never a failure:
      // a content check that can't see the source has no verdict to give.
      console.log('::warning title=app-status::app source unavailable — skipping derived-block content check (no openbank-app checkout)')
      process.exit(0)
    }

    // Status drift (issue #26) is checked whenever the source IS available,
    // independent of the derived-facts diff below and never gated by --enforce
    // (see reportStatusDrift). Report it before the facts-diff early-exits so a
    // clean facts-diff doesn't hide a stale curatorial status.
    reportStatusDrift()

    const committed = committedDerived(AGAINST)
    if (committed === null) {
      console.log(`::warning title=app-status::no committed artefact at ${path.relative(process.cwd(), AGAINST)} to diff against — first run?`)
      process.exit(ENFORCE ? 1 : 0)
    }

    const drift = diffDerived(committed, derived)
    if (drift.length === 0) {
      console.log('  ✓ derived block matches the committed app-status.json')
      process.exit(0)
    }

    // Drift found — this is the signal the ADR wants. GitHub renders ::warning/::error
    // annotations inline on the PR; the body is also printed for the PR-comment step.
    const level = ENFORCE ? 'error' : 'warning'
    console.log(`::${level} title=app-status derived drift::the committed app-status.json no longer matches openbank-app source (${drift.length} field(s)). Regenerate & publish.`)
    console.log('  derived-block drift:')
    for (const l of drift) console.log(l)
    process.exit(ENFORCE ? 1 : 0)
  }

  // ── write mode ────────────────────────────────────────────────────────────────
  // Guard the transport copy: a run WITHOUT the app source must not overwrite a
  // committed artefact with a null-derived stub (that would silently erase the
  // transported facts). Only refuse when there is something to lose.
  if (!derived.sourceAvailable && existsSync(OUT)) {
    console.log(`app-status: app source unavailable — PRESERVING committed ${path.relative(process.cwd(), OUT)} (not clobbering with a null-derived stub)`)
    summarize()
    process.exit(0)
  }

  writeFileSync(OUT, serialized)

  console.log(`app-status → ${path.relative(process.cwd(), OUT)}`)
  summarize()
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main()
