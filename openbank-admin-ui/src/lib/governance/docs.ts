// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-side governance-docs loader. Surfaces the repo's three governance
// corpora — Architecture Decision Records (docs/adr), per-service threat
// models (docs/threat-models), and AI agent charters (docs/agents, ADR-0156)
// — in the operator console.
//
// READ-ONLY consumer (CLAUDE rule #3): the admin-ui *displays* governance
// artifacts, it never authors them. The .md files in the repo are the source of
// truth; this just renders them. Nothing here writes or derives governance data.
//
// Sourcing mirrors the SBOM/docs bundle pattern (lib/services/docs.ts): the repo
// root docs/ tree is .dockerignore'd out of the runtime fs, so the Dockerfile's
// `governance-collector` stage bakes docs/adr + docs/threat-models + docs/agents
// into an immutable /app/governance-bundle. Validated published content takes precedence;
// when absent, the image fallback is explicitly stale. Fallback candidates:
//   1. $OPENBANK_GOVERNANCE_DOCS   (explicit override; set in the image)
//   2. <cwd>/governance-bundle     (image-baked bundle)
//   3. <cwd>/../docs               (local dev: `npm run dev` sees the repo root)

import { promises as fs } from 'fs'
import path from 'path'
import { parse as parseYaml } from 'yaml'
import { resolveRepositoryDocumentation, repositoryDocumentationFallback, repositoryCatalogClassification } from '@/lib/evidence/repositoryDocumentationSource'
import type { ContentSourceMetadata } from '@/lib/evidence/contentSource'

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
  contentSource?: ContentSourceMetadata
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
  contentSource?: ContentSourceMetadata
  markdown: string
}

const SLUG_RE = /^[a-z0-9][a-z0-9-]*$/i

async function isDir(p: string): Promise<boolean> {
  try { return (await fs.stat(p)).isDirectory() } catch { return false }
}

/** Resolve a governance sub-corpus dir ("adr" | "threat-models" | "agents") or null. */
async function corpusDir(sub: 'adr' | 'threat-models' | 'agents'): Promise<string | null> {
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

async function loadCorpus(sub: 'adr' | 'threat-models' | 'agents') {
  const published = await resolveRepositoryDocumentation()
  if (published.value) {
    const key = sub === 'adr' ? 'adrs' : sub === 'threat-models' ? 'threatModels' : 'agentCharters'
    return { files: published.value[key].map(item => ({ slug: path.basename(item.path, '.md'), markdown: item.markdown })),
      contentSource: published.contentSource, moneyPathServices: published.moneyPathServices ?? new Set<string>() }
  }
  const dir = await corpusDir(sub)
  const files = dir ? await Promise.all((await fs.readdir(dir)).filter(file => file.endsWith('.md')).map(async file =>
    ({ slug: file.replace(/\.md$/, ''), markdown: await fs.readFile(path.join(dir, file), 'utf-8') }))) : []
  let moneyPathServices: Set<string> | null = null
  try {
    const classification = repositoryCatalogClassification(JSON.parse(await fs.readFile(process.env.OPENBANK_CATALOG ?? path.resolve(process.cwd(), 'catalog.json'), 'utf-8')))
    if (classification) moneyPathServices = new Set([...classification].filter(([, moneyPath]) => moneyPath).map(([service]) => service))
  } catch { /* No classification is invented when the fallback catalog is absent. */ }
  return { files, moneyPathServices, contentSource: repositoryDocumentationFallback(dir !== null, published.contentSource.detail) }
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
  const front = /^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/.exec(md)
  let metadata: Record<string, unknown> = {}
  try { const parsed: unknown = front ? parseYaml(front[1], { maxAliasCount: 20 }) : null; if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) metadata = parsed as Record<string, unknown> } catch { /* Legacy metadata remains readable. */ }
  const authoredBody = front ? md.slice(front[0].length).replace(/^\s+/, '') : md
  const status = normaliseStatus(typeof metadata['decision-status'] === 'string' ? metadata['decision-status'] : front ? 'Unknown' : md.match(/^Status:\s*(.+)$/m)?.[1]?.trim() ?? 'Unknown')
  const date = typeof metadata.date === 'string' ? metadata.date : front ? null : md.match(/^Date:\s*(.+)$/m)?.[1]?.trim() ?? null
  const authors = Array.isArray(metadata.authors) ? metadata.authors.filter(author => typeof author === 'string').join(', ') || null
    : typeof metadata.authors === 'string' ? metadata.authors : front ? null : md.match(/^Author\(?s?\)?:\s*(.+)$/m)?.[1]?.trim() ?? null
  const withoutHeading = authoredBody.replace(/^#\s+.+?\r?\n/, '').replace(/^\s+/, '')
  const body = (front ? withoutHeading : withoutHeading.replace(/^(?:(?:Date|Status|Author\(?s?\)?):[^\r\n]*(?:\r?\n|$)|[ \t]*\r?\n)+/, '')).replace(/^\s+/, '')

  return { slug, number, numberLabel, title, status, date, authors, body }
}

export async function loadAdrRegistry(): Promise<{ items: AdrMeta[]; contentSource: ContentSourceMetadata }> {
  const corpus = await loadCorpus('adr')
  const items = corpus.files.filter(file => /^\d{4}[-.]/.test(file.slug)).map(({ slug, markdown }) => {
    const { body: _body, ...meta } = parseAdr(slug, markdown)
    return meta
  }).sort((a, b) => a.number - b.number || a.slug.localeCompare(b.slug))
  return { items, contentSource: corpus.contentSource }
}

export async function loadAdrIndex(): Promise<AdrMeta[]> { return (await loadAdrRegistry()).items }

export async function loadAdr(slug: string): Promise<AdrDoc | null> {
  if (!SLUG_RE.test(slug) || !/^\d{4}[-.]/.test(slug)) return null
  const corpus = await loadCorpus('adr')
  const file = corpus.files.find(file => file.slug === slug)
  return file ? { ...parseAdr(slug, file.markdown), contentSource: corpus.contentSource } : null
}

function threatService(slug: string): string {
  return slug.replace(/^openbank-/, '')
}

export async function loadThreatModelRegistry(): Promise<{ items: ThreatModelMeta[]; missing: string[]; classificationAvailable: boolean; contentSource: ContentSourceMetadata }> {
  const corpus = await loadCorpus('threat-models')
  const items = corpus.files.map(({ slug, markdown }) => ({ service: threatService(slug), slug,
    title: firstHeading(markdown, threatService(slug)), moneyPath: corpus.moneyPathServices?.has(threatService(slug)) ?? false,
  })).sort((a, b) => a.service.localeCompare(b.service))
  const present = new Set(items.map(item => item.service))
  return { items, missing: [...(corpus.moneyPathServices ?? [])].filter(service => !present.has(service)).sort(), classificationAvailable: corpus.moneyPathServices !== null, contentSource: corpus.contentSource }
}

export async function loadThreatModelIndex(): Promise<ThreatModelMeta[]> { return (await loadThreatModelRegistry()).items }

export async function loadThreatModel(service: string): Promise<ThreatModelDoc | null> {
  if (!SLUG_RE.test(service)) return null
  const corpus = await loadCorpus('threat-models')
  const bare = threatService(service)
  const file = corpus.files.find(file => file.slug === `openbank-${bare}`) ?? corpus.files.find(file => file.slug === bare)
  return file ? { service: bare, slug: file.slug, title: firstHeading(file.markdown, bare),
    moneyPath: corpus.moneyPathServices?.has(bare) ?? false, markdown: file.markdown, contentSource: corpus.contentSource } : null
}

export async function missingThreatModels(): Promise<string[]> { return (await loadThreatModelRegistry()).missing }

// ── Agent charters (docs/agents, ADR-0156) — the narrative companion to the
// enforced openbank-libs/governance/agents.yaml registry. Frontmatter carries
// only navigation identifiers (id/plane/adr), never policy data — see
// docs/agents/README.md for why. `check-agent-charter-registry.sh` guarantees
// every agents.yaml id has a matching file here, so `id` below is trusted to
// be the filename without a fallback. ──

export interface AgentCharterDocMeta {
  /** Matches an agents.yaml agents[].id, e.g. "finops-agent". */
  id: string
  plane: string
  /** Primary governing ADR, e.g. "ADR-0112". */
  adr: string
  title: string
}

export interface AgentCharterDoc extends AgentCharterDocMeta {
  contentSource?: ContentSourceMetadata
  /** Body markdown with the frontmatter block and leading H1 stripped. */
  body: string
}

function parseAgentCharter(id: string, md: string): AgentCharterDoc {
  const fmMatch = md.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?/)
  const fm = fmMatch?.[1] ?? ''
  const plane = fm.match(/^plane:\s*(.+)$/m)?.[1]?.trim() ?? '—'
  const adr = fm.match(/^adr:\s*(.+)$/m)?.[1]?.trim() ?? '—'
  const withoutFrontmatter = (fmMatch ? md.slice(fmMatch[0].length) : md).replace(/^\s+/, '')
  const title = firstHeading(withoutFrontmatter, id)
  const body = withoutFrontmatter.replace(/^#\s+.+?\r?\n/, '').replace(/^\s+/, '')
  return { id, plane, adr, title, body }
}

export async function loadAgentCharterDocIndex(): Promise<AgentCharterDocMeta[]> {
  const corpus = await loadCorpus('agents')
  return corpus.files.filter(file => file.slug !== 'README').map(file => {
    const { body: _body, ...meta } = parseAgentCharter(file.slug, file.markdown)
    return meta
  }).sort((a, b) => a.id.localeCompare(b.id))
}

export async function loadAgentCharterDoc(id: string): Promise<AgentCharterDoc | null> {
  if (!SLUG_RE.test(id)) return null
  const corpus = await loadCorpus('agents')
  const file = corpus.files.find(file => file.slug === id)
  return file ? { ...parseAgentCharter(id, file.markdown), contentSource: corpus.contentSource } : null
}
