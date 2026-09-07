// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Loader test against the REAL registry tree (openbank-libs/governance/prompts), not a fixture.
//
// Why the real tree: the whole value of the phase-1 projection is that it mirrors what the bank's
// bots actually say. A fixture would keep passing while the registry's shape moved underneath it —
// exactly the drift ADR-0148's own coverage manifest exists to prevent. The assertions below are
// about SHAPE (a registered charter resolves its prompt files; a not-applicable one carries a
// reason instead), never about the wording of any prompt, so editing a prompt never reddens this.

import { describe, expect, it } from 'vitest'
import { loadPersona, loadPromptRegistry } from '../lib/governance/prompts'

describe('prompt registry projection (ADR-0285 phase 1)', () => {
  it('reads the committed registry from the repo tree', async () => {
    const projection = await loadPromptRegistry()

    expect(projection.available).toBe(true)
    expect(projection.schemaVersion).toBe(1)
    expect(projection.relatedAdrs).toContain('ADR-0148')
    expect(projection.personas.length).toBeGreaterThan(0)
  })

  it('resolves every registered charter to at least one prompt file', async () => {
    const { personas } = await loadPromptRegistry()
    const registered = personas.filter(p => p.status === 'registered')

    expect(registered.length).toBeGreaterThan(0)
    // A registered charter with zero versions means a listed prompt file did not resolve — the
    // failure mode the image bundle can introduce and that check-prompt-registry.py cannot see.
    expect(registered.filter(p => p.versions.length === 0).map(p => p.id)).toEqual([])
  })

  it('carries a stated reason for every charter that has no prompt here', async () => {
    const { personas } = await loadPromptRegistry()
    const unregistered = personas.filter(p => p.status === 'external' || p.status === 'not-applicable')

    expect(unregistered.length).toBeGreaterThan(0)
    expect(unregistered.filter(p => !p.reason).map(p => p.id)).toEqual([])
  })

  it('joins the agents.yaml plane onto the customer-facing copilot and reports its layers unpublished', async () => {
    const persona = await loadPersona('customer-copilot')

    expect(persona).not.toBeNull()
    expect(persona?.plane).toBe('customer')
    expect(persona?.versions.map(v => v.id)).toContain('system.v1')
    expect(persona?.versions.every(v => v.chars > 0)).toBe(true)
    // Phase 1 has no communication-service, so this is the only honest value. When phase 2 lands
    // and this flips for a published persona, that is a deliberate change to this expectation.
    expect(persona?.editableLayers).toBe('not-published')
  })

  it('returns null for an unknown persona', async () => {
    expect(await loadPersona('no-such-charter')).toBeNull()
  })
})
