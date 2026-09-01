// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// #5904 — "Render charter-backed agent identity in the approval queue".
// The identity must come from openbank-libs/governance/agents.yaml (the document OPA
// enforces), and an actor absent from it must render as UNRESOLVED — never as a
// plausible-looking string, and never confused with "we could not read the registry".

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { render, screen } from '@testing-library/react'
import { parse } from 'yaml'
import { describe, expect, it } from 'vitest'
import { resolveAgentIdentity, type AgentIdentityRegistry } from '@/lib/governance/agentIdentity'
import { AgentIdentityBadge } from '@/components/approvals/AgentIdentityBadge'

interface RawRegistry { agents: { id: string; plane?: string; charter?: string }[] }

// Read the REAL enforced registry, not a fixture — a fixture cannot notice that the id
// the queue resolves against has been renamed in agents.yaml.
const raw = parse(readFileSync(
  path.resolve(process.cwd(), '../openbank-libs/governance/agents.yaml'),
  'utf8',
)) as RawRegistry

const registry: AgentIdentityRegistry = {
  available: true,
  agents: raw.agents.map(a => ({
    id: a.id, plane: a.plane ?? '—', charter: a.charter ?? '', requiresHuman: [],
  })),
}

const KNOWN = raw.agents[0].id

describe('resolveAgentIdentity', () => {
  it('resolves every id agents.yaml actually declares', () => {
    for (const a of raw.agents) {
      const out = resolveAgentIdentity(a.id, registry)
      expect(out.status).toBe('chartered')
      if (out.status === 'chartered') expect(out.charter.id).toBe(a.id)
    }
    expect(raw.agents.length).toBeGreaterThan(0)
  })

  it('resolves the Keycloak service-account form of a charter id', () => {
    const out = resolveAgentIdentity(`service-account-${KNOWN}`, registry)
    expect(out.status).toBe('chartered')
    if (out.status === 'chartered') expect(out.charter.id).toBe(KNOWN)
  })

  it('reports an actor absent from agents.yaml as unresolved', () => {
    // The old approval queue matched /assistant|agent|\bai\b/ over proposedBy, so this
    // string alone earned an "AI-generated" badge with no charter behind it.
    const out = resolveAgentIdentity('risk-agent-review-desk', registry)
    expect(out.status).toBe('unresolved')
  })

  it('does NOT resolve a near-miss by substring', () => {
    expect(resolveAgentIdentity(`${KNOWN}-shadow`, registry).status).toBe('unresolved')
  })

  it('reports unverifiable — not unresolved — when the registry could not be read', () => {
    expect(resolveAgentIdentity(KNOWN, null).status).toBe('unverifiable')
    expect(resolveAgentIdentity(KNOWN, { available: false, agents: [] }).status).toBe('unverifiable')
  })

  it('never claims an empty proposer is chartered', () => {
    expect(resolveAgentIdentity('', registry).status).toBe('unresolved')
    expect(resolveAgentIdentity(null, registry).status).toBe('unresolved')
  })
})

describe('AgentIdentityBadge — the states render distinguishably', () => {
  function stateOf(node: React.ReactElement): { state: string | null; text: string } {
    const { unmount } = render(node)
    const el = screen.getByTestId('agent-identity')
    const out = { state: el.getAttribute('data-state'), text: el.textContent ?? '' }
    unmount()
    return out
  }

  it('shows the charter id for a chartered proposer', () => {
    const out = stateOf(<AgentIdentityBadge identity={resolveAgentIdentity(KNOWN, registry)} />)
    expect(out.state).toBe('chartered')
    expect(out.text).toContain(KNOWN)
  })

  it('unresolved and unverifiable are different badges with different copy', () => {
    const unresolved = stateOf(<AgentIdentityBadge identity={resolveAgentIdentity('nobody-desk', registry)} />)
    const unverifiable = stateOf(<AgentIdentityBadge identity={resolveAgentIdentity('nobody-desk', null)} />)

    expect(unresolved.state).toBe('unresolved')
    expect(unverifiable.state).toBe('unverifiable')
    expect(unresolved.state).not.toBe(unverifiable.state)
    expect(unresolved.text).not.toBe(unverifiable.text)
    expect(unverifiable.text).toMatch(/unverifiable/i)
  })

  it('loading is its own state, not a premature verdict', () => {
    const out = stateOf(<AgentIdentityBadge identity={resolveAgentIdentity(KNOWN, null)} loading />)
    expect(out.state).toBe('loading')
    expect(out.text).not.toMatch(/unverifiable/i)
  })

  it('never prints the raw proposedBy string as if it were an identity', () => {
    const out = stateOf(<AgentIdentityBadge identity={resolveAgentIdentity('totally-made-up-agent', registry)} />)
    expect(out.text).not.toContain('totally-made-up-agent')
  })

  it('uses shared semantic badge tokens instead of local colour literals', () => {
    const source = readFileSync(path.resolve(__dirname, '../components/approvals/AgentIdentityBadge.tsx'), 'utf8')

    expect(source).toContain('badge-warning')
    expect(source).toContain('badge-neutral')
    expect(source).toContain('badge-danger')
    expect(source).not.toMatch(/#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}(?![0-9a-fA-F])/)
  })
})
