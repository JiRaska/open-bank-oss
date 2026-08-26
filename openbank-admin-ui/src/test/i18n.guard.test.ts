// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ── Admin-UI bilingual-by-default rule (enforced) ──────────────────────────
//
// Why this guard exists: the console is bilingual (cs/en). The language toggle
// flips a client context (`useLanguage().t(cs, en)`) that every consumer
// re-renders from — the Sidebar and Header were wired up, but page BODIES were
// historically authored with hardcoded English JSX text. The result: toggling
// the language changed the chrome but not the page content, which reads as
// "i18n is broken". It is not broken — those strings were simply never wrapped
// in `t()`.
//
// The rule: a page renders NO hardcoded human-language copy. Every user-facing
// string goes through `t('Česky', 'English')` from `@/lib/i18n/LanguageContext`
// so it switches with the toggle. This test is the executable form of that rule.
//
// How it works (a ratchet, like a lint baseline):
//   - Pages NOT in BASELINE must be clean (zero hardcoded strings). Any NEW page,
//     or a regression on an already-swept page, fails CI immediately.
//   - Pages IN BASELINE are known-not-yet-swept; they are allowed to still have
//     hardcoded strings. But once a baseline page is swept clean it MUST be
//     removed from BASELINE (the test fails telling you to), so coverage only
//     ever grows — it can never silently regress.
//
// The detector is deliberately conservative (high precision): it flags multi-word
// literal JSX text and hardcoded user-facing attributes (placeholder/aria-label/
// title/alt). Single-word labels and pure tokens are not flagged here — wrap them
// in `t()` anyway when you sweep a page; the guard's job is to stop the obvious
// untranslated sentences from coming back.

import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'fs'
import path from 'path'

const APP_DIR = path.resolve(__dirname, '../app')
const COMPONENTS_DIR = path.resolve(__dirname, '../components')

// Auth screens are static, pre-login, single-language by design. The GDPR privacy
// notice (privacy/page.tsx) is no longer in this set — it renders no copy of its
// own (the bilingual content lives in components/privacy/PrivacyContent.tsx,
// covered by the component-level guard below) and is bilingual since #7068.
const EXEMPT = new Set<string>([
  'auth/login/page.tsx',
  'auth/error/page.tsx',
  'auth/forbidden/page.tsx',
])

// Brand / proper nouns / technical tokens that read identically in cs and en and
// therefore do not need translation even inside otherwise-flagged text.
const ALLOWED_TOKENS = new Set<string>([
  'OpenBank', 'Keycloak', 'Kafka', 'ArgoCD', 'PostgreSQL', 'Postgres', 'Redis',
  'Grafana', 'Prometheus', 'Vault', 'OpenBao', 'Valkey', 'Strimzi', 'Quarkus', 'Next', 'CycloneDX',
  'Trivy', 'CodeQL', 'Gitleaks', 'SWIFT', 'SEPA', 'IBAN', 'BIC', 'API', 'BFF',
  'OpenAPI', 'AsyncAPI', 'Swagger',
  'SBOM', 'KYC', 'AML', 'PID', 'FX', 'CI', 'CD', 'ADR', 'UI', 'SQL', 'JSON',
  'YAML', 'HTTP', 'HTTPS', 'OIDC', 'JWT', 'mTLS', 'TLS', 'PSD2', 'GDPR',
])

// ── Baseline: pages not yet swept. Burn this down; never add to it. ──────────
// Generated from the codebase state when the guard was introduced. Each entry is
// a page that still contains hardcoded copy. Removing entries (by sweeping the
// page) is the whole point — the test forces removal once a page is clean.
// Remaining: long-form documentation pages that render large prose blocks
// (service map, API reference, auth-flow narrative, BCP/BPMN/compliance/cloud
// docs). These are queued for the bilingual sweep; the ratchet keeps every
// already-swept operator page (accounts, payments, system, compliance, …) clean.
const BASELINE = new Set<string>([
  // All long-form documentation pages have been swept bilingual — the baseline is
  // empty. Every page now renders its copy through t('Česky','English'); the
  // ratchet below keeps it that way (any new hardcoded copy fails CI).
])

// ── Component baseline: components not yet swept. Burn down; never add to. ───
// The guard originally scanned ONLY src/app/**/page.tsx, which left src/components
// — where a large share of user-facing copy actually lives — completely unchecked.
// Extending the scope surfaced four violators; the two small ones (AgentDock,
// SbomViewer) were swept in the same change. The two below are long-form
// documentation components that render large prose blocks (the same category the
// page baseline used to hold, and which was burned down to empty the same way).
// They are queued for the bilingual sweep. Same ratchet as pages: a component here
// that becomes clean MUST be removed, so this set can only ever shrink.
const COMPONENT_BASELINE = new Set<string>([
  'docs/BpmnView.tsx',    // BPMN catalogue: long prose, already part cs/part en — needs a real sweep, not a mechanical wrap
  'docs/ProcessView.tsx', // auth-flow/JWT narrative: same, long-form mixed-language prose
])

function walk(dir: string, match: (entry: string) => boolean): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) out.push(...walk(full, match))
    else if (match(entry)) out.push(full)
  }
  return out
}

function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
}

// True when a flagged fragment is made entirely of allowed brand/technical tokens
// (and separators), so it needs no translation.
function isAllowedFragment(text: string): boolean {
  const words = text.split(/[^A-Za-zÀ-ž]+/).filter(Boolean)
  if (words.length === 0) return true
  return words.every(w => ALLOWED_TOKENS.has(w))
}

const LETTER = 'A-Za-zÀ-ž'
// Multi-word literal JSX text: a run of letters, whitespace, then another run of
// letters, with no JSX braces (dynamic content) inside the node.
const JSX_TEXT = new RegExp(`>\\s*([${LETTER}][^<>{}]*?[${LETTER}]{2,}[^<>{}]*?)\\s*<`, 'g')
// Only aria-label / alt are reliably human copy. placeholder/title carry too much
// example data (IBANs, UUIDs, sample names) and technical tooltips to gate on —
// translate them when you sweep a page, but the ratchet doesn't enforce them.
const ATTR = new RegExp(`\\b(aria-label|alt)\\s*=\\s*"([^"{}]*[${LETTER}]{2,}[^"{}]*)"`, 'g')
const TWO_WORDS = new RegExp(`[${LETTER}]{2,}\\s+[${LETTER}]{2,}`)

// The `>…<` text regex also straddles JS that isn't JSX text — an arrow body
// (`=> s.up).length`), a comparison, a generic type param. These fragments carry
// code punctuation that real UI copy never does; reject them so the guard stays
// precise (a noisy guard gets disabled, which is worse than no guard).
const CODE_LIKE = /[=;'`]|=>|&&|\|\||===|!==|\bconst\b|\breturn\b|\bvoid\b|\bnew\s|\.\w+\(|::|\$\{/
function isCodeLike(text: string): boolean {
  if (CODE_LIKE.test(text)) return true
  const open = (text.match(/\(/g) || []).length
  const close = (text.match(/\)/g) || []).length
  return open !== close // unbalanced parens ⇒ a sliced expression, not a text node
}

function findHardcoded(src: string): string[] {
  const code = stripComments(src)
  const hits: string[] = []
  let m: RegExpExecArray | null
  JSX_TEXT.lastIndex = 0
  while ((m = JSX_TEXT.exec(code))) {
    const txt = m[1].trim()
    if (!TWO_WORDS.test(txt)) continue // conservative: only multi-word copy
    if (isCodeLike(txt)) continue // not JSX text — a straddled JS expression
    if (isAllowedFragment(txt)) continue
    hits.push(`text "${txt.slice(0, 48)}"`)
  }
  ATTR.lastIndex = 0
  while ((m = ATTR.exec(code))) {
    const val = m[2].trim()
    if (isAllowedFragment(val)) continue
    hits.push(`${m[1]}="${val.slice(0, 40)}"`)
  }
  return hits
}

describe('admin-ui bilingual-by-default rule', () => {
  const pages = walk(APP_DIR, e => e === 'page.tsx')

  it('discovers page files', () => {
    expect(pages.length).toBeGreaterThan(10)
  })

  for (const file of pages) {
    const rel = path.relative(APP_DIR, file).split(path.sep).join('/')
    if (EXEMPT.has(rel)) continue
    const hardcoded = findHardcoded(readFileSync(file, 'utf8'))

    if (BASELINE.has(rel)) {
      // Ratchet: a baseline page that is now clean must be removed from BASELINE.
      it(`${rel} is still pending i18n (remove from BASELINE once swept)`, () => {
        expect(
          hardcoded.length,
          `${rel} no longer has hardcoded copy — delete it from BASELINE in i18n.guard.test.ts so the ratchet keeps it clean.`,
        ).toBeGreaterThan(0)
      })
    } else {
      it(`${rel} renders no hardcoded copy (all strings via t())`, () => {
        expect(
          hardcoded,
          `${rel} has hardcoded copy that won't switch language:\n  - ${hardcoded.join('\n  - ')}\n\nWrap each user-facing string in t('Česky','English') from @/lib/i18n/LanguageContext.`,
        ).toEqual([])
      })
    }
  }
})

// A page is only half the surface: the Sidebar, Header, AgentDock and every panel
// under src/components render copy too, and were never scanned. Same rule, same
// ratchet — a component that renders human copy renders it through t().
describe('admin-ui bilingual-by-default rule — components', () => {
  const components = walk(COMPONENTS_DIR, e => e.endsWith('.tsx'))

  it('discovers component files', () => {
    expect(components.length).toBeGreaterThan(10)
  })

  for (const file of components) {
    const rel = path.relative(COMPONENTS_DIR, file).split(path.sep).join('/')
    const hardcoded = findHardcoded(readFileSync(file, 'utf8'))

    if (COMPONENT_BASELINE.has(rel)) {
      // Ratchet: a baseline component that is now clean must leave the baseline.
      it(`${rel} is still pending i18n (remove from COMPONENT_BASELINE once swept)`, () => {
        expect(
          hardcoded.length,
          `${rel} no longer has hardcoded copy — delete it from COMPONENT_BASELINE in i18n.guard.test.ts so the ratchet keeps it clean.`,
        ).toBeGreaterThan(0)
      })
    } else {
      it(`${rel} renders no hardcoded copy (all strings via t())`, () => {
        expect(
          hardcoded,
          `${rel} has hardcoded copy that won't switch language:\n  - ${hardcoded.join('\n  - ')}\n\nWrap each user-facing string in t('Česky','English') from @/lib/i18n/LanguageContext.`,
        ).toEqual([])
      })
    }
  }
})
