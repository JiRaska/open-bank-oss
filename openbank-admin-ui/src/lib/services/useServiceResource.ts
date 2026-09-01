// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One shared data-fetch hook for BFF-backed list/detail screens, so every page
// degrades identically instead of re-inventing (and re-breaking) the same
// fetch → classify → empty-state dance. It encodes three things the sandbox
// makes non-negotiable:
//
//  1. Graceful states (admin-ui CLAUDE.md rule #1): a non-OK BFF response is
//     classified with `classifyBffFailure()` into a typed `kind`, never leaked
//     as a raw "HTTP 404". The page renders <DataUnavailable kind=…>.
//
//  2. Auto-wake (ADR-0057, KEDA scale-to-zero + the FinOps off-hours scaledown).
//     Most of the fleet is idle at zero replicas to save cost; the FIRST request
//     wakes the pod but often returns 503 `scaled_to_zero` / 502 while it boots.
//     Historically the page showed that first cold response as a hard failure and
//     the operator saw "not responding" for a service that was simply waking up.
//     This hook instead keeps polling (a few attempts, a few seconds apart) so the
//     screen fills in on its own once the pod is ready — the wake request the
//     operator implicitly made by opening the page actually completes.
//
//  3. A stable `reload()` and a `waking` flag so a page can show a calm
//     "waking…" affordance distinct from a genuine outage.

'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { classifyBffFailure, type BffFailure } from './bff'

export type ServiceUnavailable = { kind: BffFailure | 'no_data' }

export interface ServiceResource<T> {
  data: T | null
  loading: boolean
  /** Non-null when the resource could not be loaded — drives <DataUnavailable>. */
  unavailable: ServiceUnavailable | null
  /** True while an automatic wake-retry is in flight (scaled-to-zero / cold pod). */
  waking: boolean
  /** Re-run the fetch from scratch (e.g. a manual "Refresh" button). */
  reload: () => void
}

export interface ServiceResourceOptions<T> {
  /** Map the raw JSON body into the shape the page wants (e.g. pick `.cards`). */
  select?: (raw: unknown) => T
  /**
   * Retry `scaled_to_zero` / `unreachable` / network failures this many times
   * before giving up, to let a cold KEDA pod finish waking. Set 0 to disable.
   */
  maxWakeRetries?: number
  /** Delay between wake retries (ms). */
  retryDelayMs?: number
  /** Per-request timeout (ms). */
  timeoutMs?: number
}

const WAKE_KINDS: ReadonlySet<BffFailure> = new Set(['scaled_to_zero', 'unreachable'])

/**
 * Fetch a BFF resource with graceful states and KEDA-wake auto-retry.
 *
 * @param url Same-origin BFF URL (build with `svcUrl(...)`), or `null` to skip.
 */
export function useServiceResource<T = unknown>(
  url: string | null,
  options: ServiceResourceOptions<T> = {},
): ServiceResource<T> {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState<boolean>(!!url)
  const [unavailable, setUnavailable] = useState<ServiceUnavailable | null>(null)
  const [waking, setWaking] = useState<boolean>(false)
  const [nonce, setNonce] = useState<number>(0)

  // Keep the latest options in a ref so an inline `select`/config object doesn't
  // change effect identity every render (which would re-fetch on a loop). The ref
  // is seeded with the first options via useRef(options) and refreshed in an
  // effect — never mutated during render (React purity rule).
  const optsRef = useRef(options)
  useEffect(() => { optsRef.current = options })

  const reload = useCallback(() => setNonce(n => n + 1), [])

  useEffect(() => {
    if (!url) {
      setLoading(false)
      return
    }
    const { select, maxWakeRetries = 3, retryDelayMs = 4000, timeoutMs = 10_000 } = optsRef.current
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | null = null
    let activeController: AbortController | null = null
    let attempt = 0

    const scheduleRetry = (kind: BffFailure) => {
      attempt += 1
      setWaking(true)
      // Show the calm "idle / waking" panel while we keep polling.
      setUnavailable({ kind })
      timer = setTimeout(run, retryDelayMs)
    }

    async function run() {
      const controller = new AbortController()
      activeController = controller
      const deadline = setTimeout(() => controller.abort(), timeoutMs)
      try {
        const res = await fetch(url!, { signal: controller.signal, cache: 'no-store' })
        if (cancelled) return
        if (!res.ok) {
          const kind = await classifyBffFailure(res)
          if (cancelled) return
          if (WAKE_KINDS.has(kind) && attempt < maxWakeRetries) {
            scheduleRetry(kind)
            return
          }
          setUnavailable({ kind })
          setWaking(false)
          setLoading(false)
          return
        }
        const raw = (await res.json()) as unknown
        if (cancelled) return
        setData((select ? select(raw) : (raw as T)))
        setUnavailable(null)
        setWaking(false)
        setLoading(false)
      } catch {
        // Timeout / abort / network — treat like a cold-pod unreachable and retry.
        if (cancelled) return
        if (attempt < maxWakeRetries) {
          scheduleRetry('unreachable')
          return
        }
        setUnavailable({ kind: 'unreachable' })
        setWaking(false)
        setLoading(false)
      } finally {
        clearTimeout(deadline)
        if (activeController === controller) activeController = null
      }
    }

    setLoading(true)
    setUnavailable(null)
    setWaking(false)
    run()

    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
      activeController?.abort()
    }
    // `url` + `nonce` are the only real inputs; options are read via ref.
  }, [url, nonce])

  return { data, loading, unavailable, waking, reload }
}
