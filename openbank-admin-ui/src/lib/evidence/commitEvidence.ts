// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Commit-addressed evidence store reader (#13201).
 *
 * Evidence for `(component, commit)` is IMMUTABLE — a commit's tests ran once — so a hit is
 * cached for the life of the process and fetched once per deploy, not once per page view.
 * Layout: `${OPENBANK_EVIDENCE_STORE_URL}/<component>/<commit>/test-intelligence.json`, where
 * `<commit>` is exactly the prefix the image tag carries. The object is that component's
 * `ComponentTestPosture`, already projected by the collector.
 *
 * Resilience, because this sits behind a page anyone can leave open:
 *  - bounded LRU (fleet size × a few deploys), so memory cannot grow with history;
 *  - a miss (404) is remembered for `missTtlMs` — the publisher may still be uploading;
 *  - per-key circuit breaker: after `breakerThreshold` consecutive failures the key is not
 *    retried for `breakerOpenMs`;
 *  - at most `concurrency` requests to the store at once, each with a hard timeout;
 *  - never throws: every failure is a typed result the caller renders as provenance.
 * No credential is sent. The store is expected to be readable from the cluster network only;
 * a long-lived bearer token on this page is exactly what #13201 rules out.
 */
import type { ComponentTestPosture } from '@/lib/types/test-intelligence'

export type CommitEvidenceResult =
  | { status: 'hit'; posture: ComponentTestPosture }
  | { status: 'miss' }
  | { status: 'error'; error: string }
  | { status: 'disabled' }

interface Entry {
  result: CommitEvidenceResult
  /** Epoch ms after which a non-hit entry may be retried. Hits never expire. */
  retryAt: number
  failures: number
}

export interface CommitEvidenceOptions {
  baseUrl?: string
  fetchImpl?: typeof fetch
  now?: () => number
  maxEntries?: number
  timeoutMs?: number
  concurrency?: number
  missTtlMs?: number
  errorRetryMs?: number
  breakerThreshold?: number
  breakerOpenMs?: number
}

const SAFE_COMPONENT = /^openbank-[a-z0-9-]{2,60}$/
const SAFE_COMMIT = /^[0-9a-f]{7,40}$/

export function storeBaseUrl(raw: string | undefined): string | null {
  if (!raw) return null
  try {
    const u = new URL(raw)
    if (u.protocol !== 'https:' || u.search || u.hash || u.username || u.password) return null
    return u.toString().replace(/\/+$/, '')
  } catch { return null }
}

/** Accept only a posture that is about this component AND this commit's run. */
export function validPosture(body: unknown, component: string, commit: string): body is ComponentTestPosture {
  if (!body || typeof body !== 'object') return false
  const p = body as Partial<ComponentTestPosture>
  if (p.component !== component || !Array.isArray(p.evidence) || !p.coverage || !p.testInfrastructure) return false
  // Every observation that names a run must name THIS commit — a posture from another build
  // stored under the wrong key would otherwise be rendered as the running code's evidence.
  return p.evidence.every(item => !item.run || item.run.commit.startsWith(commit) || commit.startsWith(item.run.commit))
}

export function createCommitEvidenceReader(opts: CommitEvidenceOptions = {}) {
  const now = opts.now ?? Date.now
  const fetchImpl = opts.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args))
  const maxEntries = opts.maxEntries ?? 256
  const timeoutMs = opts.timeoutMs ?? 3000
  const concurrency = opts.concurrency ?? 4
  const missTtlMs = opts.missTtlMs ?? 5 * 60_000
  const errorRetryMs = opts.errorRetryMs ?? 60_000
  const breakerThreshold = opts.breakerThreshold ?? 3
  const breakerOpenMs = opts.breakerOpenMs ?? 5 * 60_000

  const entries = new Map<string, Entry>()
  const inflight = new Map<string, Promise<CommitEvidenceResult>>()
  let active = 0
  const waiting: Array<() => void> = []

  const acquire = async () => {
    if (active < concurrency) { active += 1; return }
    await new Promise<void>(resolve => waiting.push(resolve))
    active += 1
  }
  const release = () => { active -= 1; waiting.shift()?.() }

  const remember = (key: string, entry: Entry) => {
    entries.delete(key)
    entries.set(key, entry)
    while (entries.size > maxEntries) entries.delete(entries.keys().next().value as string)
  }

  async function fetchOne(base: string, component: string, commit: string, key: string, previous?: Entry): Promise<CommitEvidenceResult> {
    await acquire()
    try {
      const res = await fetchImpl(`${base}/${component}/${commit}/test-intelligence.json`, {
        cache: 'no-store', signal: AbortSignal.timeout(timeoutMs), headers: { Accept: 'application/json' },
      })
      if (res.status === 404) {
        const result: CommitEvidenceResult = { status: 'miss' }
        remember(key, { result, retryAt: now() + missTtlMs, failures: 0 })
        return result
      }
      if (!res.ok) throw new Error(`evidence store HTTP ${res.status}`)
      const body: unknown = await res.json()
      if (!validPosture(body, component, commit)) throw new Error('evidence store returned a posture for another component or commit')
      const result: CommitEvidenceResult = { status: 'hit', posture: body }
      remember(key, { result, retryAt: Number.POSITIVE_INFINITY, failures: 0 })
      return result
    } catch (err) {
      const failures = (previous?.failures ?? 0) + 1
      const result: CommitEvidenceResult = { status: 'error', error: err instanceof Error ? err.message : 'evidence store unreachable' }
      remember(key, { result, retryAt: now() + (failures >= breakerThreshold ? breakerOpenMs : errorRetryMs), failures })
      return result
    } finally {
      release()
    }
  }

  return {
    async read(component: string, commit: string): Promise<CommitEvidenceResult> {
      const base = storeBaseUrl(opts.baseUrl ?? process.env.OPENBANK_EVIDENCE_STORE_URL)
      if (!base) return { status: 'disabled' }
      if (!SAFE_COMPONENT.test(component) || !SAFE_COMMIT.test(commit)) return { status: 'error', error: 'invalid component or commit' }
      const key = `${component}@${commit}`
      const cached = entries.get(key)
      if (cached && now() < cached.retryAt) return cached.result
      const running = inflight.get(key)
      if (running) return running
      const pending = fetchOne(base, component, commit, key, cached).finally(() => inflight.delete(key))
      inflight.set(key, pending)
      return pending
    },
    size: () => entries.size,
  }
}
