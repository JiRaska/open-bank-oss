// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Two DIFFERENT defects hide behind "a mutation can fire twice", and they need
 * different mechanisms. Keep them apart:
 *
 *  1. RE-ENTRY — a second activation while the first request is still outstanding.
 *     `useSingleFlight` fixes this. React `setState` only disables a control on the
 *     NEXT render, so a same-tick double activation sails past `disabled={busy}`.
 *     The lock must therefore be a ref, claimed SYNCHRONOUSLY before the first
 *     `await`. This is a client-side concern only.
 *
 *  2. REPLAY — a retry issued after a response was lost. No client lock helps: the
 *     first request may well have been executed by the server. The only fix is a
 *     STABLE idempotency key that the server honours, so the retry replays the
 *     original attempt instead of creating a second one. `useIdempotencyKey`
 *     supplies that: one key per distinct payload, minted once, held across
 *     failures, cleared on success.
 *
 * A third shape — two DIFFERENT operators racing the same four-eyes decision — is
 * NOT addressed here and cannot be: nothing in one browser observes the other's
 * click. That is a server-side concern (state transition guard / optimistic
 * locking). Applying a client single-flight lock to it would be a control that
 * reports healthy while the race is still there.
 */

import { useCallback, useRef, useState } from 'react'

/** Returned when an activation was rejected because the same key was already in flight. */
export const SKIPPED = Symbol('single-flight:skipped')
export type SingleFlightResult<T> = T | typeof SKIPPED

export function wasSkipped<T>(result: SingleFlightResult<T>): result is typeof SKIPPED {
  return result === SKIPPED
}

export interface SingleFlight {
  /**
   * Runs `fn` unless an operation under `key` is already in flight, in which case
   * it returns `SKIPPED` without invoking `fn` at all — no fetch is issued.
   *
   * The claim is synchronous, so it wins against a second activation in the SAME
   * event-loop turn, which is exactly the case `disabled={busy}` cannot cover.
   * The lock is released on every settle path, success or throw.
   */
  run: <T>(key: string, fn: () => Promise<T>) => Promise<SingleFlightResult<T>>
  /** True while any operation is in flight — for `aria-busy` / disabled rendering. */
  busy: boolean
  /**
   * The sole key currently in flight, or null when none (or several) are running.
   * Prefer `isRunning(key)` for per-control rendering: different keys may run together.
   */
  activeKey: string | null
  /** All active keys, reactively updated for UIs that intentionally permit concurrency. */
  activeKeys: readonly string[]
  /** Synchronous read; does not depend on a render having happened. */
  isRunning: (key: string) => boolean
}

export function useSingleFlight(): SingleFlight {
  const inFlight = useRef<Set<string>>(new Set())
  const [activeKeys, setActiveKeys] = useState<readonly string[]>([])

  const isRunning = useCallback((key: string) => activeKeys.includes(key), [activeKeys])

  const run = useCallback(async <T,>(key: string, fn: () => Promise<T>): Promise<SingleFlightResult<T>> => {
    // Synchronous claim BEFORE any await — this is the whole point of the hook.
    if (inFlight.current.has(key)) return SKIPPED
    inFlight.current.add(key)
    setActiveKeys(keys => [...keys, key])
    try {
      return await fn()
    } finally {
      inFlight.current.delete(key)
      setActiveKeys(keys => keys.filter(active => active !== key))
    }
  }, [])

  return {
    run,
    busy: activeKeys.length > 0,
    activeKey: activeKeys.length === 1 ? activeKeys[0] : null,
    activeKeys,
    isRunning,
  }
}

/**
 * A stable idempotency key, held per payload identity.
 *
 * `forPayload(p)` returns the SAME key for as long as `p` serialises identically —
 * so a retry after a dropped response replays the original attempt rather than
 * creating a second one. Edit the payload and a fresh key is minted (it is a
 * different intent). Call `clear()` once the attempt has definitively succeeded,
 * so the next deliberate submission of an identical payload is a new operation.
 */
export interface IdempotencyKeyHolder {
  forPayload: (payload: unknown) => string
  clear: () => void
  /** Current key without minting one; null if none held. For assertions/telemetry. */
  peek: () => string | null
}

function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'undefined'
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, v]) => v !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
  return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${stableStringify(v)}`).join(',')}}`
}

export function newIdempotencyKey(): string {
  // `crypto.randomUUID` needs a secure context; keep a deterministic-shape fallback
  // so a non-HTTPS operator shell still sends a well-formed key rather than none.
  const c = globalThis.crypto as Crypto | undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return `k-${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 14)}`
}

export function useIdempotencyKey(): IdempotencyKeyHolder {
  const held = useRef<{ fingerprint: string; key: string } | null>(null)

  const forPayload = useCallback((payload: unknown) => {
    const fingerprint = stableStringify(payload)
    if (held.current && held.current.fingerprint === fingerprint) return held.current.key
    const key = newIdempotencyKey()
    held.current = { fingerprint, key }
    return key
  }, [])

  const clear = useCallback(() => { held.current = null }, [])
  const peek = useCallback(() => held.current?.key ?? null, [])

  return { forPayload, clear, peek }
}

export const __testing = { stableStringify }
