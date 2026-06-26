// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Server-only docs source for the docs BFF (ADR-0056). The browser never calls
// GitHub directly; admin-ui resolves release-please output server-side here and
// either serves it as JSON (/api/docs/[kind]/[service]) or renders it in a
// server component (/docs/changelog|release-notes/[service]). Both reuse this
// module so there is a single source of truth and no duplicated fetch logic.
//
// Resolution order — IMAGE FIRST, GitHub as a best-effort fallback:
//   1. The CHANGELOG.md baked into the image by the Dockerfile changelog-collector
//      (/app/changelog-bundle/<folder>/CHANGELOG.md). This is the authoritative,
//      offline, token-free source — it works in the PRIVATE repo / sandbox where
//      the admin-ui pod has no GitHub credentials (the previous GitHub-only path
//      404'd for every service, which is why changelog & release notes showed up
//      empty everywhere). Mirrors the openapi/sbom/docs bundles.
//   2. Live GitHub (raw + Releases API) — only reached when the bundle is absent
//      (e.g. a dev checkout) or empty, and only useful when the repo is public or
//      a GITHUB_TOKEN is present. Every failure fails soft to an empty state.
//
// Release notes are DERIVED from the same baked CHANGELOG.md (release-please emits
// one `## [x.y.z](compare) (date)` section per release), so they work offline too;
// the GitHub Releases API is used only as an enrichment when reachable.

import 'server-only'
import { promises as fs } from 'fs'
import path from 'path'
import { componentFolder } from '@/lib/services/bff'

const REPO = process.env.GITHUB_DOCS_REPO ?? 'JiRaska/open-bank'
const REF = process.env.GITHUB_DOCS_REF ?? 'main'

// Fixed vocabulary (lowercase, digits, single dashes). Validating here keeps the
// fetch from being turned into an SSRF / path-traversal primitive against
// raw.githubusercontent.com / api.github.com and the baked bundle path.
export const SERVICE_RE = /^[a-z][a-z0-9-]{1,48}$/

const GH_HEADERS: Record<string, string> = {
  Accept: 'application/vnd.github+json',
  'User-Agent': 'openbank-admin-ui',
  ...(process.env.GITHUB_TOKEN ? { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}),
}

export interface ChangelogResult {
  service: string
  markdown: string | null
  source: string
}

export interface ReleaseNote {
  tag: string
  name: string
  body: string
  publishedAt: string | null
  url: string
}

export interface ReleaseNotesResult {
  service: string
  releases: ReleaseNote[]
  source: string
}

interface GithubRelease {
  tag_name: string
  name: string | null
  body: string | null
  published_at: string | null
  html_url: string
  prerelease: boolean
  draft: boolean
}

// ── Image-baked CHANGELOG.md (changelog-collector → /app/changelog-bundle) ──────

function changelogBundleRoot(): string {
  return process.env.OPENBANK_CHANGELOG_BUNDLE ?? '/app/changelog-bundle'
}

/** Candidate changelog paths, image bundle first, then a dev (off-cluster) checkout. */
function changelogCandidatePaths(folder: string): string[] {
  const repoRoot = process.env.OPENBANK_REPO_ROOT ?? path.resolve(process.cwd(), '..')
  return [
    path.join(changelogBundleRoot(), folder, 'CHANGELOG.md'),
    path.join(repoRoot, folder, 'CHANGELOG.md'),
  ]
}

async function readBakedChangelog(folder: string): Promise<string | null> {
  for (const p of changelogCandidatePaths(folder)) {
    try {
      const txt = await fs.readFile(p, 'utf-8')
      if (txt && txt.trim()) return txt
    } catch {
      // try next candidate
    }
  }
  return null
}

export async function fetchChangelog(service: string): Promise<ChangelogResult> {
  const folder = componentFolder(service)
  const source = `https://github.com/${REPO}/blob/${REF}/${folder}/CHANGELOG.md`

  // 1) Image-baked changelog (works offline, in the private repo, no token).
  const baked = await readBakedChangelog(folder)
  if (baked) return { service, markdown: baked, source }

  // 2) Live GitHub fallback (public repo or token present).
  const rawUrl = `https://raw.githubusercontent.com/${REPO}/${REF}/${folder}/CHANGELOG.md`
  try {
    const res = await fetch(rawUrl, { next: { revalidate: 300 } })
    if (!res.ok) return { service, markdown: null, source }
    return { service, markdown: await res.text(), source }
  } catch {
    return { service, markdown: null, source }
  }
}

// ── Release notes: parse the release-please CHANGELOG into per-version entries ───

// A release-please changelog section heading, e.g.
//   ## [1.5.0](https://github.com/.../compare/a...b) (2026-06-12)
//   ## 1.5.0 (2026-06-12)
// Captures: 1=version, 2=compare URL (optional), 3=ISO date (optional).
const RELEASE_HEADING =
  /^##\s+(?:\[)?v?(\d+\.\d+\.\d+(?:[-+.][\w.]+)?)(?:\])?(?:\(([^)]+)\))?\s*(?:\((\d{4}-\d{2}-\d{2})\))?\s*$/

/** Derive releases from a baked release-please CHANGELOG.md (most-recent first). */
export function parseChangelogReleases(markdown: string, folder: string): ReleaseNote[] {
  const lines = markdown.split('\n')
  const out: ReleaseNote[] = []
  let cur: { version: string; url: string | null; date: string | null; body: string[] } | null = null

  const flush = () => {
    if (!cur) return
    out.push({
      tag: `${folder}-v${cur.version}`,
      name: cur.version,
      body: cur.body.join('\n').trim(),
      publishedAt: cur.date,
      url: cur.url ?? `https://github.com/${REPO}/releases`,
    })
  }

  for (const line of lines) {
    const m = RELEASE_HEADING.exec(line)
    if (m) {
      flush()
      cur = { version: m[1], url: m[2] ?? null, date: m[3] ?? null, body: [] }
    } else if (cur) {
      // release-please separates sections with a horizontal rule — drop it.
      if (line.trim() === '---') continue
      cur.body.push(line)
    }
  }
  flush()
  return out
}

export async function fetchReleaseNotes(service: string): Promise<ReleaseNotesResult> {
  const folder = componentFolder(service)
  const source = `https://github.com/${REPO}/releases`

  // 1) Live GitHub Releases API — richest metadata (real publish dates + URLs),
  // used when reachable (public repo or token). Filtered to this component's tags.
  const prefixes = [`${folder}-v`, `${folder}@`, `${service}-v`, `${service}@`]
  try {
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=100`, {
      headers: GH_HEADERS,
      next: { revalidate: 300 },
    })
    if (res.ok) {
      const all = (await res.json()) as GithubRelease[]
      const releases: ReleaseNote[] = all
        .filter(r => !r.draft && prefixes.some(p => r.tag_name.startsWith(p)))
        .map(r => ({
          tag: r.tag_name,
          name: r.name ?? r.tag_name,
          body: r.body ?? '',
          publishedAt: r.published_at,
          url: r.html_url,
        }))
      if (releases.length > 0) return { service, releases, source }
    }
  } catch {
    // fall through to the baked changelog
  }

  // 2) Offline fallback: derive releases from the image-baked CHANGELOG.md, so
  // the page works in the private repo / sandbox with no GitHub access at all.
  const baked = await readBakedChangelog(folder)
  if (baked) return { service, releases: parseChangelogReleases(baked, folder), source }

  return { service, releases: [], source }
}
