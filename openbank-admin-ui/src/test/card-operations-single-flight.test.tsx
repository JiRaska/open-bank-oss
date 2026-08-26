// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCardOperations } from '@/lib/cards/useCardOperations'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import type { Card } from '@/lib/cards/types'

const card = { id: 'card-1', maskedPan: '**** 1234', status: 'ACTIVE' } as Card
const suspend = { action: 'suspend', to: 'SUSPENDED', irreversible: false, reason: false } as const

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('card operator mutation single-flight contract', () => {
  it('rejects an overlapping write and accepts a new one after settlement', async () => {
    let settle: ((response: Response) => void) | undefined
    const fetchMock = vi.fn(() => new Promise<Response>(resolve => { settle = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = ({ children }: { children: React.ReactNode }) => <LanguageProvider>{children}</LanguageProvider>
    const { result } = renderHook(() => useCardOperations(), { wrapper })

    let first: Promise<boolean>
    let overlapping: Promise<boolean>
    act(() => {
      first = result.current.runTransition(card, suspend)
      overlapping = result.current.runTransition(card, suspend)
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    await expect(overlapping!).resolves.toBe(false)

    await act(async () => {
      settle?.(new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }))
      await first!
    })

    let next: Promise<boolean>
    act(() => { next = result.current.runTransition(card, suspend) })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await act(async () => {
      settle?.(new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }))
      await next!
    })
  })
})
