// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Server-side docs loader, shared by:
//   - /api/services/[name]/docs[/...]   (HTTP proxy for the FE)
//   - /services/[name]/docs/[[...slug]] (server-rendered docs page)
//
// Strategy per service id:
//   • "libs"         → read from /app/docs-bundle/openbank-libs/docs (image-baked)
//   • runnable svc   → fetch from http://<container>:<port>/q/openbank/docs[/<slug>]
//                      (openbank-libs DocsResource — single source of truth,
//                      version-locked to the service's running JAR)
//
// Both paths return the same DocsIndex / DocsDocument shapes so callers
// don't special-case the source.
//
// Language convention (mirrors openbank-libs DocsCatalog):
//   File naming: <slug>[.<lang>].md   e.g. README.cs.md, 01-overview.en.md
//   Falls back: requested lang → "" (language-agnostic) → "en" → "cs" → any.

import { promises as fs } from 'fs'
import path from 'path'
import { findService, serviceBaseUrl, type ServiceEntry } from './registry'
import { inCluster, resolveInClusterBaseUrl } from '@/lib/discovery'

const FETCH_TIMEOUT_MS = 2000
const DEFAULT_LANG = 'en'
const FALLBACK_CHAIN = ['en', 'cs']

export type Language = 'cs' | 'en'

export interface DocsIndexItem {
  slug: string
  lang?: string
  availableLanguages?: string[]
  title: string
  bytes?: number
  etag?: string
}

export interface DocsIndex {
  service: string
  version?: string
  source: 'live' | 'bundle'
  requestedLang: string
  availableLanguages: string[]
  /**
   * Well-known related endpoints (relative paths) published by the service.
   * Standard set from openbank-libs DocsResource: openapi, swagger, health,
   * metrics, info, docsMeta. UI prefixes each with the admin-ui /api/svc/
   * proxy so links stay first-party.
   */
  links?: Record<string, string>
  items: DocsIndexItem[]
}

export interface DocsDocument {
  source: 'live' | 'bundle'
  lang: string
  availableLanguages: string[]
  markdown: string
}

const SAFE_NAME_RE = /^[a-z][a-z0-9-]{2,40}$/
const SAFE_SLUG_RE = /^[a-z0-9-]{1,60}$/
const SAFE_LANG_RE = /^[a-z]{2}$/

function docsBundleRoot(): string {
  return (
    process.env.OPENBANK_DOCS_BUNDLE
    ?? process.env.OPENBANK_REPO_ROOT
    ?? path.resolve(process.cwd(), '..')
  )
}

function normaliseLang(lang: string | undefined): string {
  const candidate = (lang ?? DEFAULT_LANG).toLowerCase()
  return SAFE_LANG_RE.test(candidate) ? candidate : DEFAULT_LANG
}

/** Parse `<slug>[.<lang>].md` → { slug, lang }. lang = "" for language-agnostic files. */
function parseSlugAndLang(filename: string): { slug: string; lang: string } | null {
  if (!filename.endsWith('.md')) return null
  const noExt = filename.slice(0, -3)
  const m = /^(.+)\.([a-z]{2})$/i.exec(noExt)
  if (m) {
    return { slug: m[1], lang: m[2].toLowerCase() }
  }
  return { slug: noExt, lang: '' }
}

function resolveLangFromMap<T>(langMap: Map<string, T>, requested: string): T | null {
  if (langMap.size === 0) return null
  if (langMap.has(requested)) return langMap.get(requested)!
  if (langMap.has('')) return langMap.get('')!
  for (const f of FALLBACK_CHAIN) {
    if (f === requested) continue
    if (langMap.has(f)) return langMap.get(f)!
  }
  return langMap.values().next().value ?? null
}

// ─────────────────────────── BUNDLE PATH (libs only) ───────────────────────────

async function indexFromBundle(serviceDir: string, requestedLang: string): Promise<DocsIndex | null> {
  const dir = path.join(docsBundleRoot(), serviceDir, 'docs')
  try {
    const entries = await fs.readdir(dir)
    // Group filenames → slug → { lang → filename }
    const bySlug = new Map<string, Map<string, string>>()
    for (const file of entries) {
      const parsed = parseSlugAndLang(file)
      if (!parsed) continue
      const slug = parsed.slug.toUpperCase() === 'README' ? 'index' : parsed.slug
      if (!bySlug.has(slug)) bySlug.set(slug, new Map())
      bySlug.get(slug)!.set(parsed.lang, file)
    }
    const items: DocsIndexItem[] = []
    for (const [slug, langMap] of [...bySlug.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
      const filename = resolveLangFromMap(langMap, requestedLang)
      if (!filename) continue
      const content = await fs.readFile(path.join(dir, filename), 'utf-8')
      const titleMatch = content.match(/^#\s+(.+)$/m)
      const langPresent = [...langMap.keys()].filter(l => l !== '').sort()
      const availableLanguages = langPresent.length > 0 ? langPresent : ['']
      const chosenLang = [...langMap.entries()].find(([_, f]) => f === filename)?.[0] ?? ''
      items.push({
        slug,
        lang: chosenLang,
        availableLanguages,
        title: titleMatch ? titleMatch[1].trim() : slug,
        bytes: Buffer.byteLength(content, 'utf-8'),
      })
    }
    const availableLanguages = [...new Set(items.flatMap(i => i.availableLanguages ?? []))]
      .filter(l => l !== '')
      .sort()
    return {
      service: serviceDir,
      source: 'bundle',
      requestedLang,
      availableLanguages,
      items,
    }
  } catch {
    return null
  }
}

async function docFromBundle(serviceDir: string, slug: string, requestedLang: string): Promise<DocsDocument | null> {
  const resolvedSlug = slug === 'index' ? 'README' : slug
  if (!SAFE_SLUG_RE.test(resolvedSlug.toLowerCase())) return null
  const dir = path.join(docsBundleRoot(), serviceDir, 'docs')
  // Try in order: requested → "" (language-agnostic) → en → cs → any
  const candidates = [
    `${resolvedSlug}.${requestedLang}.md`,
    `${resolvedSlug}.md`,
    ...FALLBACK_CHAIN
      .filter(l => l !== requestedLang)
      .map(l => `${resolvedSlug}.${l}.md`),
  ]
  let availableLanguages: string[] = []
  try {
    const entries = await fs.readdir(dir)
    availableLanguages = entries
      .map(f => parseSlugAndLang(f))
      .filter((x): x is { slug: string; lang: string } => x !== null)
      .filter(p => p.slug === resolvedSlug && p.lang !== '')
      .map(p => p.lang)
      .sort()
  } catch { /* dir missing entirely */ }

  for (const cand of candidates) {
    try {
      const markdown = await fs.readFile(path.join(dir, cand), 'utf-8')
      const chosenLang = parseSlugAndLang(cand)?.lang ?? ''
      return { source: 'bundle', lang: chosenLang, availableLanguages, markdown }
    } catch { /* try next */ }
  }
  return null
}

// ─────────────────────────── LIVE PATH (runnable services) ───────────────────────────

/**
 * Base URL for talking to a running service from the admin-ui server process.
 *   • In-cluster → real Service DNS (`<name>.<namespace>.svc:<port>`) resolved
 *     via the ADR-0051 discovery feed — the same path the BFF proxy and System
 *     Health use. Returns null when the service isn't deployed (→ docs degrade to
 *     a calm "not available" 404 instead of hanging on an unresolvable host).
 *   • Off-cluster (local dev / docker-compose) → legacy localhost/container map.
 * The old code always used the compose hostname / localhost, which does not
 * resolve inside the Kubernetes pod — so docs for *running* services silently
 * 404'd in the sandbox.
 */
async function liveBaseUrl(svc: ServiceEntry): Promise<string | null> {
  if (inCluster()) {
    const k8sName = svc.container.replace(/^openbank-/, '')
    return resolveInClusterBaseUrl(k8sName)
  }
  return serviceBaseUrl(svc)
}

async function indexFromLive(id: string, requestedLang: string): Promise<DocsIndex | null> {
  const svc = findService(id)
  if (!svc) return null
  const base = await liveBaseUrl(svc)
  if (!base) return null
  const url = `${base}/q/openbank/docs?lang=${requestedLang}`
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT_MS)
    const res = await fetch(url, { signal: ctrl.signal, cache: 'no-store' })
    clearTimeout(timer)
    if (!res.ok) return null
    const body = await res.json() as {
      service: string
      version?: string
      available?: boolean
      requestedLang?: string
      availableLanguages?: string[]
      links?: Record<string, string>
      items?: DocsIndexItem[]
    }
    if (!body.available || !body.items || body.items.length === 0) return null
    return {
      service: body.service,
      version: body.version,
      source: 'live',
      requestedLang: body.requestedLang ?? requestedLang,
      availableLanguages: body.availableLanguages ?? [],
      links: body.links,
      items: body.items,
    }
  } catch {
    return null
  }
}

async function docFromLive(id: string, slug: string, requestedLang: string): Promise<DocsDocument | null> {
  const svc = findService(id)
  if (!svc) return null
  if (!SAFE_SLUG_RE.test(slug)) return null
  const base = await liveBaseUrl(svc)
  if (!base) return null
  const url = `${base}/q/openbank/docs/${slug}?lang=${requestedLang}`
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT_MS)
    const res = await fetch(url, {
      signal: ctrl.signal, cache: 'no-store',
      headers: { Accept: 'text/markdown' },
    })
    clearTimeout(timer)
    if (!res.ok) return null
    const markdown = await res.text()
    const contentLanguage = res.headers.get('content-language') ?? ''
    // Fetch index to discover available languages — cheap (cached upstream).
    const idx = await indexFromLive(id, requestedLang)
    const slugEntry = idx?.items.find(i => i.slug === slug)
    return {
      source: 'live',
      lang: contentLanguage === 'any' ? '' : contentLanguage,
      availableLanguages: slugEntry?.availableLanguages ?? [],
      markdown,
    }
  } catch {
    return null
  }
}

// ─────────────────────────── PUBLIC API ───────────────────────────

export async function loadDocsIndex(id: string, requestedLang: string = DEFAULT_LANG): Promise<DocsIndex | null> {
  if (!SAFE_NAME_RE.test(id)) return null
  const lang = normaliseLang(requestedLang)
  if (id === 'libs') return indexFromBundle('openbank-libs', lang)
  return indexFromLive(id, lang)
}

export async function loadDocsDocument(id: string, slug: string, requestedLang: string = DEFAULT_LANG): Promise<DocsDocument | null> {
  if (!SAFE_NAME_RE.test(id)) return null
  const lang = normaliseLang(requestedLang)
  if (id === 'libs') return docFromBundle('openbank-libs', slug, lang)
  return docFromLive(id, slug, lang)
}
