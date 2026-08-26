// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import type { EvidenceKind, EvidenceState, TestAgentFinding } from '@/lib/types/test-intelligence'
import type { TestIntelligenceReport } from '@/lib/types/test-intelligence'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

// The snapshot is file data and this route forwards it to another service, so every value
// leaving here is reduced to a closed vocabulary rather than escaped. An unrecognised kind or
// state becomes `unknown`: the agent must never receive free text lifted out of a report.
const COMPONENT_NAME = /^[a-z0-9][a-z0-9-]{0,63}$/
// Written as exhaustive records so a kind or state added to the union fails compilation here
// instead of silently degrading a real observation to `unknown` on the way to the agent.
const EVIDENCE_KIND_KEYS: Record<EvidenceKind, true> = {
  unit: true, integration: true, contract: true, e2e: true, performance: true,
  synthetic: true, mutation: true, visual: true, trace: true, simulation: true,
}
const EVIDENCE_STATE_KEYS: Record<EvidenceState, true> = {
  passed: true, failed: true, skipped: true, 'not-run': true, stale: true, blocked: true, unknown: true,
}
const EVIDENCE_KINDS = new Set<string>(Object.keys(EVIDENCE_KIND_KEYS))
const EVIDENCE_STATES = new Set<string>(Object.keys(EVIDENCE_STATE_KEYS))
const INFRASTRUCTURE = new Set(['postgres', 'redpanda', 'valkey'])
const AGENT_SEVERITIES = new Set<TestAgentFinding['severity']>(['WARNING', 'CRITICAL'])
const MAX_AGENT_TEXT = 1_000
const MAX_AGENT_COUNT = 2_147_483_647

const boundedCount = (value: unknown): number => typeof value === 'number' && Number.isFinite(value)
  ? Math.min(MAX_AGENT_COUNT, Math.max(0, Math.round(value))) : 0

const safeEvidence = (items: ReadonlyArray<{ kind: string; state: string }> | undefined) =>
  (items ?? []).map(item => ({
    kind: EVIDENCE_KINDS.has(item.kind) ? item.kind : 'unknown',
    state: EVIDENCE_STATES.has(item.state) ? item.state : 'unknown',
  }))

const boundedText = (value: unknown): string | null => {
  if (typeof value !== 'string') return null
  const text = value.trim()
  return text.length > 0 && text.length <= MAX_AGENT_TEXT ? text : null
}

const safeProposalUrl = (value: unknown): string | null => {
  const text = boundedText(value)
  if (!text) return null
  try {
    const parsed = new URL(text)
    return parsed.protocol === 'https:' ? parsed.toString() : null
  } catch { return null }
}

// Agent responses are advisory, but they still cross a network boundary. Normalize them before
// rendering: no arbitrary component identity, no executable URL scheme, and no unbounded text.
// This intentionally does not turn a finding into evidence or alter any CI/runtime verdict.
const safeFindings = (value: unknown): TestAgentFinding[] => !Array.isArray(value) ? [] : value.flatMap(item => {
  if (!item || typeof item !== 'object') return []
  const finding = item as Record<string, unknown>
  const id = boundedText(finding.id)
  const component = boundedText(finding.component)
  const title = boundedText(finding.title)
  const severity = finding.severity
  if (!id || !component || !COMPONENT_NAME.test(component) || !title || typeof severity !== 'string' || !AGENT_SEVERITIES.has(severity as TestAgentFinding['severity'])) return []
  return [{
    id, component, title, severity: severity as TestAgentFinding['severity'],
    checkType: boundedText(finding.checkType) ?? 'advisory',
    detectedAt: boundedText(finding.detectedAt) ?? '',
    rootCause: boundedText(finding.rootCause), proposalUrl: safeProposalUrl(finding.proposalUrl),
    status: boundedText(finding.status) ?? 'open',
  }]
})

function flakyHunterBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://flaky-test-hunter.flaky-test-hunter.svc:8148'
  return process.env.FLAKY_TEST_HUNTER_URL ?? 'http://localhost:8148'
}

export async function GET(): Promise<NextResponse> {
  const accessToken = (await auth())?.user?.accessToken
  if (!accessToken) return NextResponse.json({ findings: [], available: false, reason: 'unauthorized' }, { status: 401 })
  try {
    const response = await fetch(`${flakyHunterBase()}/api/v1/flaky-test-hunter/findings`, {
      headers: { Authorization: `Bearer ${accessToken}` }, signal: AbortSignal.timeout(10_000),
    })
    if (!response.ok) return NextResponse.json({ findings: [], available: false })
    return NextResponse.json({ findings: safeFindings(await response.json()), available: true })
  } catch {
    return NextResponse.json({ findings: [], available: false })
  }
}

export async function POST(): Promise<NextResponse> {
  const session = await auth()
  const accessToken = session?.user?.accessToken
  if (!accessToken) return NextResponse.json({ findings: [], available: false, reason: 'unauthorized' }, { status: 401 })
  if (!session.user.roles?.includes('ROLE_ADMIN')) {
    return NextResponse.json({ findings: [], available: false, reason: 'forbidden' }, { status: 403 })
  }
  try {
    const file = process.env.OPENBANK_TEST_INTELLIGENCE ?? path.resolve(process.cwd(), 'test-intelligence.json')
    const report = JSON.parse(await fs.readFile(file, 'utf8')) as TestIntelligenceReport
    const historyByComponent = new Map<string, { flakyTests: number; failingTests: number; sameCommitTransitions: number; wastedDurationMs: number }>()
    for (const test of report.testCases ?? []) {
      if (!COMPONENT_NAME.test(test.component)) continue
      const current = historyByComponent.get(test.component) ?? { flakyTests: 0, failingTests: 0, sameCommitTransitions: 0, wastedDurationMs: 0 }
      current.flakyTests = boundedCount(current.flakyTests + (test.state === 'flaky' ? 1 : 0))
      current.failingTests = boundedCount(current.failingTests + (test.state === 'failing' ? 1 : 0))
      current.sameCommitTransitions = boundedCount(current.sameCommitTransitions + boundedCount(test.sameCommitTransitions))
      current.wastedDurationMs = boundedCount(current.wastedDurationMs + boundedCount(test.wastedDurationMs))
      historyByComponent.set(test.component, current)
    }
    const serviceComponents = report.components
      .filter(component => COMPONENT_NAME.test(component.component))
      .map(component => ({
        component: component.component,
        moneyPath: component.moneyPath === true,
        evidence: safeEvidence(component.evidence),
        declaredInfrastructure: (component.testInfrastructure?.declared ?? []).filter(item => INFRASTRUCTURE.has(item)),
        observedInfrastructureStarts: component.testInfrastructure?.observed.filter(item => item.lifecycle === 'started').length ?? 0,
        ...(historyByComponent.get(component.component) ?? { flakyTests: 0, failingTests: 0, sameCommitTransitions: 0, wastedDurationMs: 0 }),
      }))
    const clientComponents = (report.clientExperiences ?? []).filter(client => COMPONENT_NAME.test(client.id)).map(client => ({
      component: client.id,
      // The customer app can initiate money movement; absence of its execution evidence
      // deserves the same critical human attention as a money-path service gap.
      moneyPath: client.id === 'openbank-app',
      evidence: safeEvidence(client.evidence),
      declaredInfrastructure: [],
      observedInfrastructureStarts: 0,
      ...(historyByComponent.get(client.id) ?? { flakyTests: 0, failingTests: 0, sameCommitTransitions: 0, wastedDurationMs: 0 }),
    }))
    const payload = {
      snapshotId: `${new Date(report.collectedAt).toISOString()}:schema-${Number(report.schemaVersion)}`,
      collectedAt: new Date(report.collectedAt).toISOString(),
      components: [...serviceComponents, ...clientComponents],
    }
    const response = await fetch(`${flakyHunterBase()}/api/v1/flaky-test-hunter/evidence/analyze`, {
      method: 'POST', headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload), signal: AbortSignal.timeout(30_000),
    })
    if (!response.ok) return NextResponse.json({ findings: [], available: false }, { status: 502 })
    return NextResponse.json({ findings: safeFindings(await response.json()), available: true })
  } catch {
    return NextResponse.json({ findings: [], available: false }, { status: 502 })
  }
}
