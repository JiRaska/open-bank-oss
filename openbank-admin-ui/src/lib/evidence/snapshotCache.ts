// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Server-side snapshot cache: the cost of an evidence page is bounded by the TTL, never by
 * the number of viewers.
 *
 *  - SINGLE-FLIGHT: concurrent callers share one in-flight load; 1000 open tabs cause one
 *    refresh, not 1000.
 *  - STALE-WHILE-REVALIDATE: once a value exists every caller gets it immediately; an expired
 *    value triggers a background refresh and is still served until that refresh lands.
 *  - LAST GOOD WINS: a failed refresh keeps the previous value and backs off for `retryMs`,
 *    so an unavailable dependency is not hammered on every request.
 *
 * In-memory per process: N admin-ui replicas cost N refreshes per TTL — still constant in
 * traffic. Not a client-side lock (see mutations/singleFlight.ts for that, different problem).
 */
export interface Snapshot<T> {
  value: T
  /** Epoch ms when `value` was produced. */
  producedAt: number
}

export interface SnapshotCache<T> {
  get(): Promise<Snapshot<T>>
  /** Test seam: forget everything. */
  reset(): void
}

export function createSnapshotCache<T>(opts: {
  load: () => Promise<T>
  ttlMs: number
  retryMs?: number
  now?: () => number
}): SnapshotCache<T> {
  const now = opts.now ?? Date.now
  const retryMs = opts.retryMs ?? opts.ttlMs
  let current: Snapshot<T> | null = null
  let inflight: Promise<Snapshot<T>> | null = null
  let nextAttemptAt = 0

  const refresh = (): Promise<Snapshot<T>> => {
    if (inflight) return inflight
    inflight = opts.load()
      .then(value => {
        current = { value, producedAt: now() }
        nextAttemptAt = now() + opts.ttlMs
        return current
      })
      .catch((err: unknown) => {
        nextAttemptAt = now() + retryMs
        if (current) return current
        throw err
      })
      .finally(() => { inflight = null })
    return inflight
  }

  return {
    async get() {
      if (!current) return refresh()
      if (now() >= nextAttemptAt) void refresh().catch(() => undefined)
      return current
    },
    reset() { current = null; inflight = null; nextAttemptAt = 0 },
  }
}
