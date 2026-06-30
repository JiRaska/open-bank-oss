// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

// Unit tests for the docs/releases source (src/lib/docs/releases.ts).
// Covers the behaviour that fixes the "changelog & release notes are empty"
// regression: the repo is PRIVATE and the admin-ui pod has no token, so the
// GitHub-only path 404'd. Data must resolve from the IMAGE-BAKED CHANGELOG.md
// first, and release notes must be DERIVED from it when GitHub is unreachable.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// releases.ts is server-only; the `server-only` marker is aliased to a stub in
// vitest.config.ts so it imports under Vitest's jsdom env.

// Mock fs.promises.readFile so we can drive the baked-bundle read deterministically.
const readFile = vi.fn()
vi.mock('fs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('fs')>()
  const promises = { ...actual.promises, readFile: (...a: unknown[]) => readFile(...a) }
  return { ...actual, promises, default: { ...actual, promises } }
})

import { parseChangelogReleases, fetchChangelog, fetchReleaseNotes } from '@/lib/docs/releases'

const SAMPLE = `# Changelog

## [1.5.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.4.0...balance-service-v1.5.0) (2026-06-12)

### Features

* **balance:** add currency pockets ([#590](https://github.com/JiRaska/open-bank-oss/issues/590))

## [1.4.0](https://github.com/JiRaska/open-bank-oss/compare/balance-service-v1.3.0...balance-service-v1.4.0) (2026-05-30)

### Bug Fixes

* **balance:** idempotent credit/debit
`

describe('parseChangelogReleases', () => {
  it('splits a release-please changelog into per-version entries', () => {
    const rels = parseChangelogReleases(SAMPLE, 'openbank-balance-service')
    expect(rels).toHaveLength(2)
    expect(rels[0]).toMatchObject({
      tag: 'openbank-balance-service-v1.5.0',
      name: '1.5.0',
      publishedAt: '2026-06-12',
    })
    expect(rels[0].url).toContain('/compare/')
    expect(rels[0].body).toContain('add currency pockets')
    expect(rels[1].name).toBe('1.4.0')
    expect(rels[1].body).toContain('idempotent credit/debit')
  })

  it('returns empty for changelog with no release headings', () => {
    expect(parseChangelogReleases('# Changelog\n\nNothing yet.\n', 'openbank-x-service')).toEqual([])
  })
})

describe('fetchChangelog', () => {
  beforeEach(() => readFile.mockReset())
  afterEach(() => vi.restoreAllMocks())

  it('serves the image-baked CHANGELOG.md without touching GitHub', async () => {
    readFile.mockResolvedValueOnce(SAMPLE)
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const res = await fetchChangelog('balance-service')
    expect(res.markdown).toContain('## [1.5.0]')
    expect(fetchSpy).not.toHaveBeenCalled() // baked-first, no network
  })

  it('falls back to GitHub when no baked changelog exists', async () => {
    readFile.mockResolvedValue('') // empty bundle file ⇒ treated as "not baked"
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('# from github', { status: 200 }),
    )
    const res = await fetchChangelog('balance-service')
    expect(res.markdown).toBe('# from github')
  })
})

describe('fetchReleaseNotes', () => {
  beforeEach(() => readFile.mockReset())
  afterEach(() => vi.restoreAllMocks())

  it('derives releases from the baked changelog when GitHub returns nothing', async () => {
    // GitHub unreachable (private repo, no token) → empty/non-ok.
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', { status: 200 }))
    readFile.mockResolvedValueOnce(SAMPLE)
    const res = await fetchReleaseNotes('balance-service')
    expect(res.releases).toHaveLength(2)
    expect(res.releases[0].name).toBe('1.5.0')
  })

  it('prefers live GitHub Releases when reachable', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify([
          { tag_name: 'balance-service-v1.5.0', name: 'balance 1.5.0', body: 'gh body', published_at: '2026-06-12T00:00:00Z', html_url: 'https://gh/r', draft: false, prerelease: false },
        ]),
        { status: 200 },
      ),
    )
    const res = await fetchReleaseNotes('balance-service')
    expect(res.releases).toHaveLength(1)
    expect(res.releases[0].body).toBe('gh body')
    expect(readFile).not.toHaveBeenCalled() // didn't need the baked fallback
  })
})
