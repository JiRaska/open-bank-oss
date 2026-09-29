// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LazyRoleCatalog } from '@/components/delegations/LazyRoleCatalog'

vi.mock('@/components/delegations/RoleCatalog', () => ({
  RoleCatalog: () => <section aria-label="Loaded role catalog" />,
}))

let intersectionCallback: IntersectionObserverCallback | null = null

class ObserverStub {
  constructor(callback: IntersectionObserverCallback, public options?: IntersectionObserverInit) {
    intersectionCallback = callback
  }
  observe = vi.fn()
  unobserve = vi.fn()
  disconnect = vi.fn()
  takeRecords = vi.fn(() => [])
  root = null
  rootMargin = '600px 0px'
  thresholds = [0]
}

afterEach(() => {
  intersectionCallback = null
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('delegation role catalog loading boundary', () => {
  it('does not mount the independent catalog before it approaches the viewport', async () => {
    vi.stubGlobal('IntersectionObserver', ObserverStub)
    render(<LazyRoleCatalog />)

    expect(screen.queryByLabelText('Loaded role catalog')).toBeNull()
    expect(intersectionCallback).not.toBeNull()
    await act(async () => intersectionCallback?.([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver))
    expect(await screen.findByLabelText('Loaded role catalog')).toBeVisible()
  })

  it('loads immediately when IntersectionObserver is unavailable', async () => {
    Reflect.deleteProperty(window, 'IntersectionObserver')
    render(<LazyRoleCatalog />)

    expect(await screen.findByLabelText('Loaded role catalog')).toBeVisible()
  })

  it('warms the catalog after the primary lookup has had time to hydrate', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('IntersectionObserver', ObserverStub)
    render(<LazyRoleCatalog />)

    expect(screen.queryByLabelText('Loaded role catalog')).toBeNull()
    await act(async () => vi.advanceTimersByTime(1_500))
    expect(screen.getByLabelText('Loaded role catalog')).toBeVisible()
  })
})
