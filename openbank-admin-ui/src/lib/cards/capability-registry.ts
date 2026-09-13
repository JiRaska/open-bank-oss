// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The shape of the card capability registry, and the pure helpers over it (ADR-0283 phase 3).
//
// SEPARATE FROM THE LOADER ON PURPOSE. `capabilities.ts` imports `fs`, so anything importing it is
// server-only; the matrix screen is a client component because the admin UI is bilingual and
// `useLanguage` is a client hook. Importing the loader from there fails the build outright with
// `Module not found: Can't resolve 'fs'` — measured, not anticipated. This file has no runtime
// dependency at all, so both halves can share the types and the helpers.

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
