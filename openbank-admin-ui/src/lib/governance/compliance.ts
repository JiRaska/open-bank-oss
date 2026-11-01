// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-side loader for the Compliance Control Tower (/docs/control-tower).
// Reads the authored control catalogue (openbank-libs/governance/
// compliance-controls.yaml, baked into the image like agents.yaml) and JOINS
// each control's live status from the derived security posture
// (security-graph.json) wherever the control declares a `derivedFrom` signal —
// governance-as-code (ADR-0029). READ-ONLY consumer (CLAUDE rule #3).
//
// Honest by construction: a control with a derived signal shows the live value;
// `enforced` only when the signal is actually true, otherwise the authored
// (audit/planned/partial) status stands. Nothing is asserted that isn't backed.

import { promises as fs } from 'fs'
import path from 'path'
import { parse as parseYaml } from 'yaml'
import { loadSecurityPosture, type SecurityPosture } from './security'
import { resolveAdminUiContent, type ContentSourceMetadata } from '@/lib/evidence/contentSource'

export type ControlStatus = 'enforced' | 'partial' | 'audit' | 'planned' | 'unknown'

export interface Framework {
  id: string
  name: string
}

export interface ComplianceControl {
  id: string
  title: string
  category: string
  frameworks: string[]
  references: string[]
  evidence: string
  evidenceSource?: string
  /** Authored catalogue value, kept separate from the observed signal. */
  declaredStatus: ControlStatus
  status: ControlStatus
  derivedFrom?: string
  /** The build-time manifest signal. It is not proof of deployed runtime enforcement. */
  signalState?: 'true' | 'false' | 'unknown'
  /** Retained for API compatibility; manifest snapshots are never marked live. */
  live?: boolean
}

export interface ComplianceCatalog {
  frameworks: Framework[]
  controls: ComplianceControl[]
  /** Per-framework coverage: enforced vs total controls touching that framework. */
  coverage: Record<string, { enforced: number; total: number }>
  posture: { source: string; collectedAt: string | null } | null
  contentSource?: ContentSourceMetadata
}

function catalogFile(): string {
  // Explicit override (set in the image).
  if (process.env.OPENBANK_COMPLIANCE_CONTROLS) return process.env.OPENBANK_COMPLIANCE_CONTROLS
  // Image-baked next to agents.yaml (same /app dir).
  if (process.env.OPENBANK_AGENTS_FILE) {
    return path.join(path.dirname(process.env.OPENBANK_AGENTS_FILE), 'compliance-controls.yaml')
  }
  // Local dev: the source-of-truth in openbank-libs/governance.
  return path.resolve(process.cwd(), '..', 'openbank-libs', 'governance', 'compliance-controls.yaml')
}

/** Read a dotted path (e.g. "istio.mtls.strict") out of the posture object. */
function signalValue(posture: SecurityPosture | null, dotted: string): boolean | undefined {
  if (!posture) return undefined
  let cur: unknown = posture
  for (const key of dotted.split('.')) {
    if (cur && typeof cur === 'object' && key in (cur as Record<string, unknown>)) {
      cur = (cur as Record<string, unknown>)[key]
    } else {
      return undefined
    }
  }
  return typeof cur === 'boolean' ? cur : undefined
}

export async function loadComplianceCatalog(): Promise<ComplianceCatalog | null> {
  const resolved = await resolveAdminUiContent(
    snapshot => snapshot.content.complianceControlsYaml,
    async () => {
      try { return await fs.readFile(catalogFile(), 'utf-8') } catch { return null }
    },
  )
  if (!resolved.value) return null
  let parsed: { frameworks?: Framework[]; controls?: ComplianceControl[] }
  try { parsed = parseYaml(resolved.value) as typeof parsed } catch { return null }

  const frameworks = parsed.frameworks ?? []
  const authored = parsed.controls ?? []

  // Keep build-time manifest inputs separate from operational status. The
  // security graph is derived from source manifests, not a live cluster probe,
  // so its booleans may be shown as snapshot observations but never promote an
  // authored claim to an operational `enforced` result.
  const posture = await loadSecurityPosture()
  const controls: ComplianceControl[] = authored.map(c => {
    const declaredStatus = c.declaredStatus ?? c.status
    const signal = c.derivedFrom && posture ? signalValue(posture, c.derivedFrom) : undefined
    const signalState = signal === undefined ? 'unknown' : signal ? 'true' : 'false'
    return { ...c, declaredStatus, status: 'unknown', signalState, live: false }
  })

  // Per-framework coverage roll-up.
  const coverage: Record<string, { enforced: number; total: number }> = {}
  for (const c of controls) {
    for (const f of c.frameworks) {
      const bucket = coverage[f] ?? { enforced: 0, total: 0 }
      bucket.total += 1
      if (c.status === 'enforced') bucket.enforced += 1
      coverage[f] = bucket
    }
  }

  return {
    frameworks,
    controls,
    coverage,
    posture: posture ? { source: posture.source, collectedAt: posture.collectedAt } : null,
    contentSource: resolved.contentSource,
  }
}
