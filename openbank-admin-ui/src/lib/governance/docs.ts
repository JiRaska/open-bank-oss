// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-side governance-docs loader. Surfaces the repo's two governance
// corpora — Architecture Decision Records (docs/adr) and per-service threat
// models (docs/threat-models) — in the operator console.
//
// READ-ONLY consumer (CLAUDE rule #3): the admin-ui *displays* governance
// artifacts, it never authors them. The .md files in the repo are the source of
// truth; this just renders them. Nothing here writes or derives governance data.
//
// Sourcing mirrors the SBOM/docs bundle pattern (lib/services/docs.ts): the repo
// root docs/ tree is .dockerignore'd out of the runtime fs, so the Dockerfile's
// `governance-collector` stage bakes docs/adr + docs/threat-models into an
// immutable /app/governance-bundle. First existing candidate wins:
//   1. $OPENBANK_GOVERNANCE_DOCS   (explicit override; set in the image)
//   2. <cwd>/governance-bundle     (image-baked bundle)
//   3. <cwd>/../docs               (local dev: `npm run dev` sees the repo root)

import { promises as fs } from 'fs'
import path from 'path'

// ── Money-path services (source: openbank-libs/governance/rules.yaml →
// money_path_services). Stable governance constant; used only to badge threat
// models and flag money-path services that are MISSING one (the audit gap). ──
const MONEY_PATH = new Set([
  'ledger-service', 'transaction-service', 'account-service', 'balance-service',
  'sepa-payment', 'sepa-instant', 'domestic-payment', 'clearing-service',
  'swift-service', 'fx-service', 'lending-service', 'sca-service', 'consent-service',
  'fraud-service',
])

export type AdrStatus = 'Accepted' | 'Proposed' | 'Superseded' | 'Deprecated' | 'Rejected' | 'Unknown'

export interface AdrMeta {
  /** Filename without .md, e.g. "0029-versioning-release-and-governance-as-code". */
  slug: string
  /** Parsed leading number, e.g. 29 (for sorting). */
  number: number
  /** Zero-padded label as written, e.g. "0029". */
  numberLabel: string
  title: string
  status: AdrStatus
  date: string | null
  authors: string | null
}

export interface AdrDoc extends AdrMeta {
  /** Body markdown with the leading H1 + Date/Status/Author lines stripped. */
  body: string
}

export interface ThreatModelMeta {
  /** Service id with the openbank- prefix stripped, e.g. "account-service". */
  service: string
  /** File basename without .md, e.g. "openbank-account-service". */
  slug: string
  title: string
  moneyPath: boolean
}

export interface ThreatModelDoc extends ThreatModelMeta {
  markdown: string
}

const SLUG_RE = /^[a-z0-9][a-z0-9-]*$/i

async function isDir(p: string): Promise<boolean> {
  try { return (await fs.stat(p)).isDirectory() } catch { return false }
}

/** Resolve a governance sub-corpus dir ("adr" | "threat-models") or null. */
async function corpusDir(sub: 'adr' | 'threat-models'): Promise<string | null> {
  const candidates = [
    process.env.OPENBANK_GOVERNANCE_DOCS && path.join(process.env.OPENBANK_GOVERNANCE_DOCS, sub),
    path.resolve(process.cwd(), 'governance-bundle', sub),
    path.resolve(process.cwd(), '..', 'docs', sub),
  ].filter((c): c is string => Boolean(c))
  for (const c of candidates) {
    if (await isDir(c)) return c
  }
  return null
}

function normaliseStatus(raw: string): AdrStatus {
  const s = raw.toLowerCase()
  if (s.startsWith('accept')) return 'Accepted'
  if (s.startsWith('propos')) return 'Proposed'
  if (s.startsWith('supersed')) return 'Superseded'
  if (s.startsWith('deprecat')) return 'Deprecated'
  if (s.startsWith('reject')) return 'Rejected'
  return 'Unknown'
}

function firstHeading(md: string, fallback: string): string {
  return md.match(/^#\s+(.+?)\s*$/m)?.[1]?.trim() ?? fallback
}

function parseAdr(slug: string, md: string): AdrDoc {
  const numMatch = slug.match(/^(\d+)/)
  const number = numMatch ? parseInt(numMatch[1], 10) : 0
  const numberLabel = numMatch ? numMatch[1] : '----'
  const title = firstHeading(md, slug).replace(/^\d+\.\s*/, '')
  const status = normaliseStatus(md.match(/^Status:\s*(.+)$/m)?.[1]?.trim() ?? 'Unknown')
  const date = md.match(/^Date:\s*(.+)$/m)?.[1]?.trim() ?? null
  const authors = md.match(/^Author\(?s?\)?:\s*(.+)$/m)?.[1]?.trim() ?? null

  // Strip the front block (H1 + the Date/Status/Author lines) — we render those
  // in a styled meta bar, so leaving them in the body would duplicate them.
  const body = md
    .replace(/^#\s+.+?\r?\n/, '')
    .replace(/^(?:Date|Status|Author\(?s?\)?):.*\r?\n?/gm, '')
    .replace(/^\s+/, '')

  return { slug, number, numberLabel, title, status, date, authors, body }
}

export async function loadAdrIndex(): Promise<AdrMeta[]> {
  const dir = await corpusDir('adr')
  if (!dir) return []
  const files = (await fs.readdir(dir)).filter(f => f.endsWith('.md'))
  const metas = await Promise.all(
    files.map(async f => {
      const slug = f.replace(/\.md$/, '')
      const md = await fs.readFile(path.join(dir, f), 'utf-8')
      const { body: _body, ...meta } = parseAdr(slug, md)
      return meta as AdrMeta
    }),
  )
  return metas.sort((a, b) => a.number - b.number || a.slug.localeCompare(b.slug))
}

export async function loadAdr(slug: string): Promise<AdrDoc | null> {
  if (!SLUG_RE.test(slug)) return null
  const dir = await corpusDir('adr')
  if (!dir) return null
  try {
    const md = await fs.readFile(path.join(dir, `${slug}.md`), 'utf-8')
    return parseAdr(slug, md)
  } catch {
    return null
  }
}

function threatService(slug: string): string {
  return slug.replace(/^openbank-/, '')
}

export async function loadThreatModelIndex(): Promise<ThreatModelMeta[]> {
  const dir = await corpusDir('threat-models')
  if (!dir) return []
  const files = (await fs.readdir(dir)).filter(f => f.endsWith('.md'))
  const metas = await Promise.all(
    files.map(async f => {
      const slug = f.replace(/\.md$/, '')
      const service = threatService(slug)
      const md = await fs.readFile(path.join(dir, f), 'utf-8')
      return {
        service,
        slug,
        title: firstHeading(md, service),
        moneyPath: MONEY_PATH.has(service),
      } satisfies ThreatModelMeta
    }),
  )
  return metas.sort((a, b) => a.service.localeCompare(b.service))
}

export async function loadThreatModel(service: string): Promise<ThreatModelDoc | null> {
  if (!SLUG_RE.test(service)) return null
  const dir = await corpusDir('threat-models')
  if (!dir) return null
  // Accept both the bare service id ("account-service") and the file slug
  // ("openbank-account-service").
  const bare = threatService(service)
  for (const name of [`openbank-${bare}.md`, `${bare}.md`]) {
    try {
      const md = await fs.readFile(path.join(dir, name), 'utf-8')
      return {
        service: bare,
        slug: name.replace(/\.md$/, ''),
        title: firstHeading(md, bare),
        moneyPath: MONEY_PATH.has(bare),
        markdown: md,
      }
    } catch {
      // try next candidate
    }
  }
  return null
}

/** Money-path services (rules.yaml) that have NO threat model yet — the gap. */
export async function missingThreatModels(): Promise<string[]> {
  const present = new Set((await loadThreatModelIndex()).map(m => m.service))
  return [...MONEY_PATH].filter(s => !present.has(s)).sort()
}
