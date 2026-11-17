// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-side evidence snapshot for the ADR-0383 explainer. Reads only artifacts this build already
// bakes — gate-catalog.json (scripts/generate-gate-catalog.mjs over .github/gates/gates.yaml) and the
// ADR corpus (lib/governance/docs.ts) — so no new generator and no GitHub call. A source that is not
// available yields a null map, which deriveEvidenceStatus renders as `unknown`, never as active.
import 'server-only'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { loadAdrRegistry } from '@/lib/governance/docs'
import type { AdrFact, EvidenceSnapshot, GateFact } from './mcpChannelControls'
import type { EvidenceAsOf } from '@/components/docs/McpChannelExplainer'

export function gatesFromCatalog(catalog: unknown): Record<string, GateFact> | null {
  const gates = (catalog as { gates?: unknown } | null)?.gates
  if (!Array.isArray(gates)) return null
  const out: Record<string, GateFact> = {}
  for (const gate of gates as { id?: unknown; mode?: unknown; name?: unknown }[]) {
    if (typeof gate.id !== 'string') continue
    if (gate.mode !== 'enforced' && gate.mode !== 'advisory') continue
    out[gate.id] = { mode: gate.mode, name: typeof gate.name === 'string' ? gate.name : gate.id }
  }
  return out
}

export async function loadMcpChannelEvidence(): Promise<{ snapshot: EvidenceSnapshot; asOf: EvidenceAsOf }> {
  const asOf: EvidenceAsOf = { gateRef: null, gateGeneratedAt: null, adrSource: null, adrSha: null, adrCollectedAt: null, adrImageBuiltAt: null }
  let gates: Record<string, GateFact> | null = null
  try {
    const file = process.env.OPENBANK_GATE_CATALOG ?? path.resolve(process.cwd(), 'gate-catalog.json')
    const catalog = JSON.parse(await readFile(file, 'utf8')) as { ref?: unknown; generatedAt?: unknown }
    gates = gatesFromCatalog(catalog)
    asOf.gateRef = typeof catalog.ref === 'string' ? catalog.ref : null
    asOf.gateGeneratedAt = typeof catalog.generatedAt === 'string' ? catalog.generatedAt : null
  } catch { gates = null }

  let adrs: Record<string, AdrFact> | null = null
  try {
    const { items, contentSource } = await loadAdrRegistry()
    asOf.adrSource = contentSource.source
    asOf.adrSha = contentSource.sourceSha
    asOf.adrCollectedAt = contentSource.sourceCollectedAt
    asOf.adrImageBuiltAt = contentSource.imageBuiltAt
    if (items.length > 0) {
      adrs = {}
      for (const adr of items) adrs[adr.numberLabel] = { deliveryStatus: adr.deliveryStatus, decision: adr.status, slug: adr.slug }
    }
  } catch { adrs = null }

  return { snapshot: { gates, adrs }, asOf }
}
