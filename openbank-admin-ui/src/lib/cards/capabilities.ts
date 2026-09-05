// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-only loader for the baked card capability registry (ADR-0283 phase 3, issue #8811).
// Source of truth: openbank-libs/governance/card-capabilities.yaml, baked by
// scripts/generate-card-capabilities.mjs. Derived, never hand-edited (CLAUDE.md rule #6).
//
// Server-only: it touches `fs`, so never import it from a 'use client' component.

import { readFileSync } from 'fs'
import path from 'path'

/** What the SANDBOX offers, never what a production contract would. */
export type Availability = 'sandbox' | 'contract' | 'none'

export interface CardNetwork {
  id: string
  label: string
  developerPortal: string
  sandboxAuth: string
}

export interface CardCapability {
  id: string
  label: string
  /** The Kotlin port's short name, or null where a capability is registered with no port on purpose. */
  port: string | null
  portFqn: string | null
  why: string
  /**
   * What THIS repository implements. Empty, or `['simulator']`, is the common and honest answer —
   * a screen that reads like an integration status page cannot be told apart from a plan.
   */
  bindings: string[]
  networks: Record<string, { product: string; availability: Availability }>
}

export interface CardCapabilityRegistry {
  networks: CardNetwork[]
  capabilities: CardCapability[]
  /** False when the baked file is missing — the page renders "unavailable", never an empty matrix. */
  available: boolean
}

function registryFile(): string {
  return process.env.OPENBANK_CARD_CAPABILITIES ?? path.resolve(process.cwd(), 'card-capabilities.json')
}

let cache: CardCapabilityRegistry | null = null

/**
 * Reads the baked registry.
 *
 * On a missing or unreadable file this returns `available: false` rather than an empty registry.
 * The distinction is the whole point: an empty capability matrix renders as "this platform supports
 * nothing", which is a confident lie the reader cannot tell from the truth. The same reasoning is
 * why the generator refuses to write an empty file rather than falling back to one.
 */
export function getCardCapabilities(): CardCapabilityRegistry {
  if (cache) return cache
  try {
    const parsed = JSON.parse(readFileSync(registryFile(), 'utf-8')) as {
      networks?: CardNetwork[]
      capabilities?: CardCapability[]
    }
    const networks = parsed.networks ?? []
    const capabilities = parsed.capabilities ?? []
    cache = { networks, capabilities, available: networks.length > 0 && capabilities.length > 0 }
  } catch {
    cache = { networks: [], capabilities: [], available: false }
  }
  return cache
}

/** Test seam: the loader caches, and a test that changes the file needs the next read to see it. */
export function resetCardCapabilitiesCache(): void {
  cache = null
}

/** How many networks offer this capability through a free developer sandbox. */
export function sandboxReadyNetworks(capability: CardCapability): string[] {
  return Object.entries(capability.networks)
    .filter(([, n]) => n.availability === 'sandbox')
    .map(([id]) => id)
}

/**
 * True when a capability is registered but deliberately has no port.
 *
 * Distinguished from "not yet built" on purpose: the registry states a reason for each, and the
 * screen shows the reason rather than an empty cell a reader would read as an oversight.
 */
export function isDeliberatelyPortless(capability: CardCapability): boolean {
  return capability.port === null
}
