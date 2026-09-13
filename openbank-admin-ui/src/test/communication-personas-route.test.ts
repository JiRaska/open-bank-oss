// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Communication Studio API (ADR-0285 D7, phase 1).
//
// The list response deliberately omits prompt TEXT — see the route's own comment. That is the one
// property a reader cannot check by looking at the page, so it is asserted here with a prompt
// whose body is a string no other field could contain.

import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/governance/prompts', () => ({
  loadPromptRegistry: vi.fn(),
  loadPersona: vi.fn(),
}))

import { loadPersona, loadPromptRegistry } from '../lib/governance/prompts'
import { GET as listGET } from '../app/api/communication/personas/route'
import { GET as detailGET } from '../app/api/communication/personas/[personaId]/route'

const CORE_TEXT = 'NEVER-ON-THE-LIST-WIRE core prompt body'

const persona = {
  id: 'customer-copilot',
  status: 'registered' as const,
  plane: 'customer',
  charter: 'Customer-facing mobile assistant (ADR-0089).',
  source: 'openbank-copilot-service CopilotChatService.systemPrompt()',
  reason: null,
  blockedBy: null,
  placeholders: [],
  versions: [{ id: 'system.v1', text: CORE_TEXT, chars: CORE_TEXT.length }],
  editableLayers: 'not-published' as const,
}

describe('GET /api/communication/personas', () => {
  afterEach(() => vi.restoreAllMocks())

  it('summarises each persona without putting the prompt text on the wire', async () => {
    vi.mocked(loadPromptRegistry).mockResolvedValue({
      available: true, schemaVersion: 1, relatedAdrs: ['ADR-0148'], personas: [persona],
    })

    const res = await listGET()
    const body = await res.json()

    expect(res.status).toBe(200)
    expect(body.personas).toHaveLength(1)
    expect(body.personas[0]).toMatchObject({
      id: 'customer-copilot',
      status: 'registered',
      versionCount: 1,
      versionIds: ['system.v1'],
      coreChars: CORE_TEXT.length,
      editableLayers: 'not-published',
    })
    expect(JSON.stringify(body)).not.toContain(CORE_TEXT)
  })

  it('reports an absent registry as unavailable rather than as an empty bank', async () => {
    vi.mocked(loadPromptRegistry).mockResolvedValue({
      available: false, schemaVersion: null, relatedAdrs: [], personas: [],
    })

    const body = await (await listGET()).json()

    expect(body.available).toBe(false)
    expect(body.personas).toEqual([])
  })
})

describe('GET /api/communication/personas/[personaId]', () => {
  afterEach(() => vi.restoreAllMocks())

  it('serves the full prompt text for one persona', async () => {
    vi.mocked(loadPersona).mockResolvedValue(persona)

    const res = await detailGET(new Request('http://localhost/api/communication/personas/customer-copilot'), {
      params: Promise.resolve({ personaId: 'customer-copilot' }),
    })
    const body = await res.json()

    expect(res.status).toBe(200)
    expect(body.versions[0].text).toBe(CORE_TEXT)
    expect(body.editableLayers).toBe('not-published')
  })

  it('404s an unknown persona', async () => {
    vi.mocked(loadPersona).mockResolvedValue(null)

    const res = await detailGET(new Request('http://localhost/api/communication/personas/nope'), {
      params: Promise.resolve({ personaId: 'nope' }),
    })

    expect(res.status).toBe(404)
  })
})
