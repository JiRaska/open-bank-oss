// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { render } from '@testing-library/react'
import { parse } from 'yaml'
import { describe, expect, it } from 'vitest'
import { AgentPortrait, getAgentPersona } from '@/components/agent/AgentIdentity'

interface Registry { agents: { id: string }[] }

const registry = parse(readFileSync(
  path.resolve(process.cwd(), '../openbank-libs/governance/agents.yaml'),
  'utf8',
)) as Registry

describe('AIOps agent identities', () => {
  it('gives every governed agent a named bilingual business persona', () => {
    const csNames = registry.agents.map(({ id }) => getAgentPersona(id, 'cs').name)
    const enNames = registry.agents.map(({ id }) => getAgentPersona(id, 'en').name)

    expect(csNames).not.toContain('Nový kolega')
    expect(enNames).not.toContain('New colleague')
    expect(new Set(csNames).size).toBe(registry.agents.length)
    expect(new Set(enNames).size).toBe(registry.agents.length)
  })

  it('renders an original decorative robot portrait for a known agent', () => {
    const { container } = render(<AgentPortrait agentId="rca-investigator" />)
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument()
    expect(container.querySelector('svg')).toBeInTheDocument()
  })
})
