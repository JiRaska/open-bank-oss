// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// ADR-0383 explainer (#16606). The load-bearing assertion is the render-level one: a badge that
// reads "in force" must be backed by an enforced gate in the baked gate catalog or a shipped ADR,
// judged here by an oracle written independently of deriveEvidenceStatus. Make the derivation
// lenient (e.g. treat an absent gate or a planned ADR as active) and this suite goes red.
import fs from 'node:fs'
import path from 'node:path'
import { parse as parseYaml } from 'yaml'
import { describe, expect, it, afterEach } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { McpChannelExplainer, FlowStepper } from '@/components/docs/McpChannelExplainer'
import {
  ATTACKS, CHANNEL_CONTROLS, FLOW_STEPS, deriveEvidenceStatus, resolveControls,
  type AdrFact, type Evidence, type EvidenceSnapshot,
} from '@/lib/docs/mcpChannelControls'
import { gatesFromCatalog } from '@/lib/docs/mcpChannelEvidence'

const root = process.cwd()
const src = (file: string) => fs.readFileSync(path.resolve(root, 'src', file), 'utf8')

function repoSnapshot(): EvidenceSnapshot {
  // gate-catalog.json is baked by `pretest` (scripts/generate-gate-catalog.mjs).
  const gates = gatesFromCatalog(JSON.parse(fs.readFileSync(path.resolve(root, 'gate-catalog.json'), 'utf8')))
  const adrDir = path.resolve(root, '..', 'docs', 'adr')
  const adrs: Record<string, AdrFact> = {}
  for (const file of fs.readdirSync(adrDir).filter(name => /^\d{4}-.*\.md$/.test(name))) {
    const front = /^---\r?\n([\s\S]*?)\r?\n---/.exec(fs.readFileSync(path.join(adrDir, file), 'utf8'))
    let meta: Record<string, unknown> = {}
    try { meta = (front ? parseYaml(front[1]) : {}) as Record<string, unknown> } catch { /* unparseable front-matter: no delivery claim */ }
    const delivery = meta['delivery-status']
    adrs[file.slice(0, 4)] = {
      deliveryStatus: delivery === 'shipped' || delivery === 'partial' || delivery === 'planned' || delivery === 'n-a' ? delivery : null,
      decision: String(meta['decision-status'] ?? ''), slug: file.replace(/\.md$/, ''),
    }
  }
  return { gates, adrs }
}

/** Independent oracle: may this evidence be shown as in force? */
function backedByShippedEvidence(evidence: Evidence, snapshot: EvidenceSnapshot): boolean {
  if (evidence.kind === 'gate') return snapshot.gates?.[evidence.gateId]?.mode === 'enforced'
  if (evidence.kind === 'adr') return snapshot.adrs?.[evidence.adr]?.deliveryStatus === 'shipped'
  return false
}

function renderPage(snapshot: EvidenceSnapshot) {
  return render(
    <LanguageProvider initialLanguage="en">
      <McpChannelExplainer resolved={resolveControls(snapshot)} adr={snapshot.adrs?.['0383'] ?? null}
        sourcesAvailable={{ gates: snapshot.gates !== null, adrs: snapshot.adrs !== null }} />
    </LanguageProvider>,
  )
}

/** Checks every rendered layer row against the oracle; returns the rows that lie. */
function lyingRows(container: HTMLElement, snapshot: EvidenceSnapshot): string[] {
  const lies: string[] = []
  for (const control of CHANNEL_CONTROLS) {
    const row = container.querySelector(`#layer-${control.id}`)
    expect(row, `layer row ${control.id}`).not.toBeNull()
    const badges = [...row!.querySelectorAll('[data-status]')].map(node => node.getAttribute('data-status'))
    const evidence = [control.evidence, ...control.foundations.map(item => item.evidence)]
    expect(badges).toHaveLength(evidence.length)
    evidence.forEach((item, index) => {
      if (badges[index] === 'active' && !backedByShippedEvidence(item, snapshot)) lies.push(`${control.id}#${index}`)
    })
  }
  return lies
}

describe('deriveEvidenceStatus', () => {
  const snapshot: EvidenceSnapshot = {
    gates: { on: { mode: 'enforced', name: 'on' }, soft: { mode: 'advisory', name: 'soft' } },
    adrs: {
      '0001': { deliveryStatus: 'shipped', decision: 'Accepted', slug: '0001-x' },
      '0002': { deliveryStatus: 'partial', decision: 'Accepted', slug: '0002-x' },
      '0003': { deliveryStatus: 'planned', decision: 'Proposed', slug: '0003-x' },
      '0004': { deliveryStatus: 'n-a', decision: 'Accepted', slug: '0004-x' },
      '0005': { deliveryStatus: null, decision: 'Unknown', slug: '0005-x' },
    },
  }
  it.each([
    [{ kind: 'gate', gateId: 'on' }, 'active'],
    [{ kind: 'gate', gateId: 'soft' }, 'partial'],
    [{ kind: 'gate', gateId: 'not-yet', issue: 1 }, 'planned'],
    [{ kind: 'adr', adr: '0001' }, 'active'],
    [{ kind: 'adr', adr: '0002' }, 'partial'],
    [{ kind: 'adr', adr: '0003' }, 'planned'],
    [{ kind: 'adr', adr: '0004' }, 'unknown'],
    [{ kind: 'adr', adr: '0005' }, 'unknown'],
    [{ kind: 'adr', adr: '9999' }, 'unknown'],
    [{ kind: 'planned', issue: 1 }, 'planned'],
  ] as [Evidence, string][])('%j -> %s', (evidence, expected) => {
    expect(deriveEvidenceStatus(evidence, snapshot).status).toBe(expected)
  })

  it('never reports active when the source data is missing', () => {
    expect(deriveEvidenceStatus({ kind: 'gate', gateId: 'on' }, { gates: null, adrs: null }).status).toBe('unknown')
    expect(deriveEvidenceStatus({ kind: 'adr', adr: '0001' }, { gates: null, adrs: null }).status).toBe('unknown')
  })
})

describe('customer MCP channel explainer — rendered status is backed by repository evidence', () => {
  it('renders no "in force" badge the gate catalog or ADR corpus does not back (real repository data)', () => {
    const snapshot = repoSnapshot()
    expect(snapshot.gates && Object.keys(snapshot.gates).length).toBeGreaterThan(100)
    expect(snapshot.adrs?.['0383'], 'ADR-0383 must be in the corpus').toBeDefined()
    const { container } = renderPage(snapshot)
    expect(lyingRows(container, snapshot)).toEqual([])
  })

  it('shows every channel control as not in force while ADR-0383 is not shipped', () => {
    const snapshot = repoSnapshot()
    const { container } = renderPage(snapshot)
    if (snapshot.adrs?.['0383']?.deliveryStatus !== 'shipped') {
      for (const control of CHANNEL_CONTROLS) {
        const own = container.querySelector(`#layer-${control.id} [data-status]`)
        if (!backedByShippedEvidence(control.evidence, snapshot)) expect(own?.getAttribute('data-status'), control.id).not.toBe('active')
      }
    }
  })

  it('a hostile snapshot (all foundations enforced/shipped, channel planned, tripwire gate absent) still renders the channel as planned', () => {
    const real = repoSnapshot()
    const gates = Object.fromEntries(Object.keys(real.gates ?? {}).map(id => [id, { mode: 'enforced' as const, name: id }]))
    delete gates['mcp-customer-channel-propose-only']
    const adrs = Object.fromEntries(Object.entries(real.adrs ?? {}).map(([id, adr]) => [id, { ...adr, deliveryStatus: 'shipped' as const }]))
    adrs['0383'] = { ...adrs['0383'], deliveryStatus: 'planned' }
    const snapshot = { gates, adrs }
    const { container } = renderPage(snapshot)
    expect(lyingRows(container, snapshot)).toEqual([])
    for (const control of CHANNEL_CONTROLS) {
      expect(container.querySelector(`#layer-${control.id} [data-status]`)?.getAttribute('data-status'), control.id).toBe('planned')
    }
  })

  it('the oracle catches a lie (negative control for the check itself)', () => {
    const snapshot = repoSnapshot()
    const resolved = resolveControls(snapshot)
    for (const key of Object.keys(resolved)) resolved[key] = { ...resolved[key], own: { ...resolved[key].own, status: 'active' } }
    const { container } = render(
      <LanguageProvider initialLanguage="en">
        <McpChannelExplainer resolved={resolved} adr={snapshot.adrs?.['0383'] ?? null} sourcesAvailable={{ gates: true, adrs: true }} />
      </LanguageProvider>,
    )
    expect(lyingRows(container, snapshot).length).toBeGreaterThan(0)
  })

  it('says unknown, not in force, when the build has no gate catalog or ADR corpus', () => {
    const { container } = renderPage({ gates: null, adrs: null })
    expect(container.querySelectorAll('[data-status="active"]').length).toBe(0)
    expect(screen.getByText(/Some source data/)).toBeInTheDocument()
  })
})

describe('flow stepper', () => {
  const original = window.matchMedia
  afterEach(() => { window.matchMedia = original })
  const stubMotion = (reduce: boolean) => {
    window.matchMedia = ((query: string) => ({
      matches: reduce && query.includes('reduce'), media: query, addEventListener: () => {}, removeEventListener: () => {},
    })) as unknown as typeof window.matchMedia
  }

  it('steps forward and back by keyboard-operable buttons and marks the current step', () => {
    stubMotion(false)
    const resolved = resolveControls({ gates: {}, adrs: {} })
    render(<LanguageProvider initialLanguage="en"><FlowStepper resolved={resolved} /></LanguageProvider>)
    const steps = within(screen.getByRole('list', { name: 'Flow steps' })).getAllByRole('button')
    expect(steps).toHaveLength(FLOW_STEPS.length)
    expect(steps[0]).toHaveAttribute('aria-current', 'step')
    fireEvent.click(screen.getByRole('button', { name: /Next/ }))
    expect(steps[1]).toHaveAttribute('aria-current', 'step')
    fireEvent.click(screen.getByRole('button', { name: /Previous/ }))
    expect(steps[0]).toHaveAttribute('aria-current', 'step')
    fireEvent.click(steps[9])
    expect(screen.getByText(/Step 10 of 10/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Play/ })).toBeInTheDocument()
  })

  it('offers no auto-play when reduced motion is preferred', () => {
    stubMotion(true)
    render(<LanguageProvider initialLanguage="en"><FlowStepper resolved={resolveControls({ gates: {}, adrs: {} })} /></LanguageProvider>)
    expect(screen.queryByRole('button', { name: /Play/ })).toBeNull()
    expect(screen.getByText(/reduced motion is preferred/)).toBeInTheDocument()
  })
})

describe('content contract', () => {
  it('covers the ten flow steps and the seven attacks from #16606, each pointing at a real layer', () => {
    expect(FLOW_STEPS.map(step => step.id)).toEqual(['connect', 'token', 'call', 'throttle', 'policy', 'grant', 'read', 'proposal', 'sca', 'admission'])
    expect(ATTACKS.map(attack => attack.id)).toEqual(['stolen-token', 'replay', 'injection', 'cross-customer', 'approval-fatigue', 'flood', 'malicious-client'])
    const ids = new Set(CHANNEL_CONTROLS.map(control => control.id))
    for (const step of FLOW_STEPS) expect(ids.has(step.control), step.id).toBe(true)
    for (const attack of ATTACKS) for (const id of attack.controls) expect(ids.has(id), `${attack.id}->${id}`).toBe(true)
  })

  it('every referenced gate id either exists in gates.yaml or carries the issue that tracks it', () => {
    const gates = repoSnapshot().gates ?? {}
    for (const control of CHANNEL_CONTROLS) {
      for (const evidence of [control.evidence, ...control.foundations.map(item => item.evidence)]) {
        if (evidence.kind === 'gate' && !gates[evidence.gateId]) expect(evidence.issue, `${control.id}: ${evidence.gateId}`).toBeDefined()
      }
    }
  })

  it('is reachable from the docs hub and the sidebar, and uses theme tokens only', () => {
    expect(src('components/docs/DocsHub.tsx')).toContain("href: '/docs/mcp-channel'")
    expect(src('lib/navigation/catalog.ts')).toContain("href: '/docs/mcp-channel'")
    const component = src('components/docs/McpChannelExplainer.tsx')
    expect(component).not.toMatch(/["']#[0-9a-f]{3,8}\b/i)
    expect(component).not.toMatch(/rgba?\(\s*\d/i)
    expect(component).toContain('DocsPageHeader')
  })

  it('labels the customer screens as illustrative', () => {
    renderPage({ gates: null, adrs: null })
    expect(screen.getByText(/Illustrative mock, not real screens/)).toBeInTheDocument()
    expect(screen.getAllByText('illustrative')).toHaveLength(4)
  })
})
