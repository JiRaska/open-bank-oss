// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-only loader for the compliance trace (ADR-0379). Joins three artefacts already baked
// into the image: the control catalogue (compliance-controls.yaml), the obligation register
// (regulatory-obligations.json) and the gate manifest projection (gate-catalog.json). It adds no
// new generated file. Never import this from a 'use client' component.

import { readFileSync } from 'fs'
import path from 'path'

import { loadComplianceCatalog, type ComplianceControl } from '@/lib/governance/compliance'
import { loadAdrRegistry } from '@/lib/governance/docs'
import { getRegulatoryObligations } from '@/lib/regulatory/obligations'
import { createGateHealthSnapshotReader, validateGateHealthSnapshotData, type GateHealthSnapshotData } from '@/lib/evidence/gateHealthSnapshot'
import { buildComplianceTrace, type ComplianceTrace, type DeclaredStatus, type TraceInputGate, type TraceInputs } from './trace'

export type TraceSnapshot =
  | ({ status: 'available'; missing: string[] } & ComplianceTrace)
  | { status: 'unavailable'; missing: string[] }

function loadGateCatalog(): { gates: TraceInputGate[]; ref: string | null } | null {
  try {
    const file = process.env.OPENBANK_GATE_CATALOG ?? path.resolve(process.cwd(), 'gate-catalog.json')
    const parsed = JSON.parse(readFileSync(file, 'utf-8')) as { ref?: string; gates?: TraceInputGate[] }
    const ref = parsed.ref && /^[a-f0-9]{40}$/.test(parsed.ref) ? parsed.ref : null
    return { gates: parsed.gates ?? [], ref }
  } catch {
    return null
  }
}

const gateHealthReader = createGateHealthSnapshotReader()

/**
 * Same order as /api/devops/gate-health: the published runtime snapshot first, then the copy
 * baked into the image. A snapshot that fails validation is treated as absent, which makes every
 * age-bound claim `evidence-age-unknown` rather than trusting an unreadable file.
 */
async function loadGateHealth(): Promise<TraceInputs['gateHealth']> {
  let data: GateHealthSnapshotData | null = null
  try {
    const runtime = await gateHealthReader.read()
    if (runtime.snapshot) data = runtime.snapshot
  } catch { /* fall through to the bundled copy */ }
  if (!data) {
    try {
      const file = process.env.OPENBANK_GATE_HEALTH_REPORT ?? path.resolve(process.cwd(), 'gate-health.json')
      const parsed: unknown = JSON.parse(readFileSync(file, 'utf-8'))
      if (validateGateHealthSnapshotData(parsed)) data = parsed
    } catch { /* absent */ }
  }
  if (!data) return null
  return {
    collectedAt: data.collectedAt,
    gates: Object.fromEntries(data.gates.map(g => [g.id, { status: g.status, lastOkAt: g.lastOk?.createdAt ?? null }])),
  }
}

async function adrSlugs(): Promise<Record<string, string>> {
  try {
    const { items } = await loadAdrRegistry()
    return Object.fromEntries(items.map(a => [String(a.number).padStart(4, '0'), a.slug]))
  } catch {
    return {}
  }
}

/**
 * A missing source is reported by name, never papered over. Without the control catalogue there
 * is no trace at all. Without the gate catalogue every gate ref resolves as `missing`, so every
 * positive claim renders `unverified`. That is the honest reading when the evidence cannot be
 * looked up.
 */
export async function loadComplianceTrace(): Promise<TraceSnapshot> {
  const missing: string[] = []
  const catalog = await loadComplianceCatalog()
  if (!catalog) return { status: 'unavailable', missing: ['compliance-controls.yaml'] }
  const register = getRegulatoryObligations()
  if (!register.available) missing.push('regulatory-obligations.json')
  const gateCatalog = loadGateCatalog()
  if (!gateCatalog) missing.push('gate-catalog.json')
  const gateHealth = await loadGateHealth()
  if (!gateHealth) missing.push('gate-health.json')

  const trace = buildComplianceTrace({
    frameworks: catalog.frameworks,
    controls: catalog.controls.map((c: ComplianceControl & { evidence_refs?: { kind: string; ref: string }[]; max_evidence_age_days?: number }) => ({
      id: c.id,
      title: c.title,
      category: c.category,
      frameworks: c.frameworks ?? [],
      references: c.references ?? [],
      evidence: c.evidence,
      declaredStatus: c.declaredStatus as DeclaredStatus,
      signalState: c.signalState,
      evidenceRefs: c.evidence_refs ?? [],
      maxEvidenceAgeDays: typeof c.max_evidence_age_days === 'number' ? c.max_evidence_age_days : undefined,
    })),
    obligations: register.obligations,
    areas: register.areas,
    gates: gateCatalog?.gates ?? [],
    commit: gateCatalog?.ref ?? null,
    postureCollectedAt: catalog.posture?.collectedAt ?? null,
    adrSlugs: await adrSlugs(),
    gateHealth,
    now: new Date().toISOString(),
  })
  return { status: 'available', missing, ...trace }
}
