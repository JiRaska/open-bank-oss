// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The mechanism, proved by what it PREVENTS — not by what it renders.
// Every assertion here counts invocations or compares keys; none of them checks a
// label or a disabled attribute, because a disabled attribute is precisely the
// thing that arrives one render too late.

import { describe, it, expect, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import {
  useSingleFlight, useIdempotencyKey, wasSkipped, SKIPPED, __testing,
} from '@/lib/mutations/singleFlight'

function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

describe('useSingleFlight', () => {
  it('does not invoke the operation a second time while the first is in flight', async () => {
    const { result } = renderHook(() => useSingleFlight())
    const gate = deferred<string>()
    const op = vi.fn(() => gate.promise)

    let first!: Promise<unknown>, second!: Promise<unknown>
    await act(async () => {
      // Both activations in the SAME synchronous turn — the case `disabled={busy}` misses.
      first = result.current.run('save', op)
      second = result.current.run('save', op)
    })

    // The load-bearing assertion: a COUNT, not a UI state.
    expect(op).toHaveBeenCalledTimes(1)
    expect(wasSkipped(await second)).toBe(true)
    expect(await second).toBe(SKIPPED)

    await act(async () => { gate.resolve('ok') })
    expect(await first).toBe('ok')
  })

  it('accepts a new operation once the first has settled', async () => {
    const { result } = renderHook(() => useSingleFlight())
    const op = vi.fn(async () => 'ok')
    await act(async () => { await result.current.run('save', op) })
    await act(async () => { await result.current.run('save', op) })
    expect(op).toHaveBeenCalledTimes(2)
  })

  it('releases the lock when the operation REJECTS, so a retry is possible', async () => {
    const { result } = renderHook(() => useSingleFlight())
    const op = vi.fn(async () => { throw new Error('network down') })
    await act(async () => { await expect(result.current.run('save', op)).rejects.toThrow('network down') })
    expect(result.current.isRunning('save')).toBe(false)
    await act(async () => { await expect(result.current.run('save', op)).rejects.toThrow('network down') })
    expect(op).toHaveBeenCalledTimes(2)
  })

  it('keys are independent — a save in flight does not block a publish', async () => {
    const { result } = renderHook(() => useSingleFlight())
    const gate = deferred<string>()
    const save = vi.fn(() => gate.promise)
    const publish = vi.fn(async () => 'published')
    await act(async () => {
      result.current.run('save', save)
      await result.current.run('publish', publish)
    })
    expect(save).toHaveBeenCalledTimes(1)
    expect(publish).toHaveBeenCalledTimes(1)
    await act(async () => { gate.resolve('ok') })
  })

  it('reports which operation is active, and clears it on settle', async () => {
    const { result } = renderHook(() => useSingleFlight())
    const gate = deferred<string>()
    await act(async () => { result.current.run('publish', () => gate.promise) })
    expect(result.current.activeKey).toBe('publish')
    expect(result.current.busy).toBe(true)
    await act(async () => { gate.resolve('ok') })
    expect(result.current.activeKey).toBeNull()
    expect(result.current.busy).toBe(false)
  })
})

describe('useIdempotencyKey', () => {
  it('reuses the SAME key for a byte-identical payload retry', () => {
    const { result } = renderHook(() => useIdempotencyKey())
    const payload = { amount: 100, currency: 'CZK', creditorIban: 'CZ65' }
    const first = result.current.forPayload(payload)
    const retry = result.current.forPayload({ ...payload })
    expect(retry).toBe(first)
  })

  it('is insensitive to key ORDER — the same intent is the same key', () => {
    const { result } = renderHook(() => useIdempotencyKey())
    const a = result.current.forPayload({ amount: 100, currency: 'CZK' })
    const b = result.current.forPayload({ currency: 'CZK', amount: 100 })
    expect(b).toBe(a)
  })

  it('mints a NEW key when the payload changes — an edited payment is a new intent', () => {
    const { result } = renderHook(() => useIdempotencyKey())
    const first = result.current.forPayload({ amount: 100, currency: 'CZK' })
    const edited = result.current.forPayload({ amount: 250, currency: 'CZK' })
    expect(edited).not.toBe(first)
  })

  it('mints a new key after clear() — a deliberate second identical payment', () => {
    const { result } = renderHook(() => useIdempotencyKey())
    const payload = { amount: 100, currency: 'CZK' }
    const first = result.current.forPayload(payload)
    act(() => { result.current.clear() })
    expect(result.current.peek()).toBeNull()
    expect(result.current.forPayload(payload)).not.toBe(first)
  })

  it('produces a distinct key per holder', () => {
    const a = renderHook(() => useIdempotencyKey())
    const b = renderHook(() => useIdempotencyKey())
    const payload = { amount: 1 }
    expect(a.result.current.forPayload(payload)).not.toBe(b.result.current.forPayload(payload))
  })

  it('stableStringify drops undefined and sorts keys, so optional fields do not churn the key', () => {
    const s = __testing.stableStringify
    expect(s({ b: 1, a: 2 })).toBe(s({ a: 2, b: 1 }))
    expect(s({ a: 1, b: undefined })).toBe(s({ a: 1 }))
    expect(s({ a: [1, { z: 1, y: 2 }] })).toBe(s({ a: [1, { y: 2, z: 1 }] }))
  })
})
