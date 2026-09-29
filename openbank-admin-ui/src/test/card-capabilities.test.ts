// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mkdtempSync, writeFileSync, rmSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
import {
  getCardCapabilities,
  resetCardCapabilitiesCache,
  sandboxReadyNetworks,
  isDeliberatelyPortless,
  type CardCapability,
} from '@/lib/cards/capabilities'

/**
 * The loader's job is to keep three states apart that a naive implementation merges: a registry
 * that lists capabilities, a registry that lists none, and a registry that is not there. The third
 * is the dangerous one — an empty matrix renders as "this platform supports nothing", which a
 * reader cannot tell from the truth.
 */
describe('card capability registry loader', () => {
  let dir: string
  const previous = process.env.OPENBANK_CARD_CAPABILITIES

  beforeEach(() => {
    dir = mkdtempSync(path.join(tmpdir(), 'card-caps-'))
    resetCardCapabilitiesCache()
  })

  afterEach(() => {
    if (previous === undefined) delete process.env.OPENBANK_CARD_CAPABILITIES
    else process.env.OPENBANK_CARD_CAPABILITIES = previous
    rmSync(dir, { recursive: true, force: true })
    resetCardCapabilitiesCache()
  })

  function bake(payload: unknown): void {
    const file = path.join(dir, 'card-capabilities.json')
    writeFileSync(file, JSON.stringify(payload), 'utf-8')
    process.env.OPENBANK_CARD_CAPABILITIES = file
    resetCardCapabilitiesCache()
  }

  const capability: CardCapability = {
    id: 'bin-lookup',
    label: 'BIN and card-product attributes',
    port: 'BinLookupPort',
    portFqn: 'com.openbank.libs.domain.cards.scheme.BinLookupPort',
    why: 'read-only, no cardholder data',
    bindings: ['simulator'],
    networks: {
      visa: { product: 'BIN Attributes Sharing', availability: 'sandbox' },
      mastercard: { product: 'BIN Lookup', availability: 'contract' },
    },
  }

  it('reads a baked registry', () => {
    bake({
      networks: [{ id: 'visa', label: 'Visa', developerPortal: 'https://x', sandboxAuth: 'mTLS' }],
      capabilities: [capability],
    })

    const registry = getCardCapabilities()

    expect(registry.available).toBe(true)
    expect(registry.capabilities).toHaveLength(1)
    expect(registry.capabilities[0].bindings).toEqual(['simulator'])
  })

  it('reports a MISSING registry as unavailable, never as an empty matrix', () => {
    process.env.OPENBANK_CARD_CAPABILITIES = path.join(dir, 'does-not-exist.json')
    resetCardCapabilitiesCache()

    const registry = getCardCapabilities()

    // Both fields matter: the page branches on `available` to say "this build has no registry"
    // rather than rendering a table that claims the platform supports nothing.
    expect(registry.available).toBe(false)
    expect(registry.capabilities).toEqual([])
  })

  it('reports an EMPTY registry as unavailable too — an empty matrix is never a valid answer', () => {
    bake({ networks: [], capabilities: [] })

    expect(getCardCapabilities().available).toBe(false)
  })

  it('lists only the networks whose sandbox is free to use', () => {
    // The distinction the whole `availability` column exists for: an adapter can be built and
    // exercised against `sandbox` today, and cannot be against `contract` at any effort.
    expect(sandboxReadyNetworks(capability)).toEqual(['visa'])
  })

  it('treats a portless capability as a decision, not an omission', () => {
    const portless: CardCapability = { ...capability, port: null, portFqn: null }

    expect(isDeliberatelyPortless(portless)).toBe(true)
    expect(isDeliberatelyPortless(capability)).toBe(false)
  })
})
