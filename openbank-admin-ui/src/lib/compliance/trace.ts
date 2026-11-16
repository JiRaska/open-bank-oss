// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The compliance trace (ADR-0379): obligation -> control -> evidence, joined from three baked,
// reviewed sources. Pure and framework-free so the verdict rules are unit-testable and identical
// for the page, the JSON export and any future MCP tool. Imports nothing with side effects, so a
// client component may import the types.
//
// The verdict is computed here, never authored and never produced by a model. Its one rule:
// a control may claim no more than its evidence can show.

export type DeclaredStatus = 'enforced' | 'partial' | 'audit' | 'planned' | 'unknown'
export type EvidenceKind = 'gate' | 'workflow' | 'adr' | 'path'
/** Strongest evidence behind a control: an enforced gate runs on every PR and blocks it. */
export type EvidenceLevel = 'enforced-gate' | 'ci' | 'documented' | 'none'
export type Verdict = DeclaredStatus | 'unverified'

export interface TraceInputControl {
  id: string
  title: string
  category: string
  frameworks: string[]
  references: string[]
  evidence: string
  declaredStatus: DeclaredStatus
  signalState?: 'true' | 'false' | 'unknown'
  evidenceRefs?: { kind: string; ref: string }[]
}

export interface TraceInputObligation {
  id: string
  area: string
  name: { cs: string; en: string }
  authority: string
  legalBasis: string
  cadence: string
  cadenceConfidence: string
  coverage: { status: string }
  controls?: string[]
}

export interface TraceInputGate { id: string; name: string; mode: 'enforced' | 'advisory'; line: number }

export interface TraceInputs {
  frameworks: { id: string; name: string }[]
  controls: TraceInputControl[]
  obligations: TraceInputObligation[]
  areas: { id: string; cs: string; en: string }[]
  gates: TraceInputGate[]
  /** Commit the image was built from; `null` when unknown, never a branch name passed off as a SHA. */
  commit: string | null
  postureCollectedAt: string | null
  /** ADR number (4 digits) -> slug of the in-app ADR page. */
  adrSlugs: Record<string, string>
}

export interface TraceEvidence {
  kind: EvidenceKind
  ref: string
  label: string
  href: string | null
  /** Only for kind `gate`; `missing` when the id is not in the baked gate catalogue. */
  gateMode?: 'enforced' | 'advisory' | 'missing'
}

export interface TraceControl extends Omit<TraceInputControl, 'evidenceRefs'> {
  evidence: string
  evidenceRefs: TraceEvidence[]
  evidenceLevel: EvidenceLevel
  verdict: Verdict
  /** Why the verdict differs from the declared status, if it does. */
  downgradeReason: 'no-machine-evidence' | null
  obligationIds: string[]
}

export interface TraceObligation extends TraceInputObligation {
  controls: string[]
  /** Controls listed on the obligation that the catalogue does not know (CI rejects these). */
  unknownControls: string[]
  hasControl: boolean
}

export interface ComplianceTrace {
  asOf: { commit: string | null; postureCollectedAt: string | null }
  frameworks: { id: string; name: string; controls: number; machineEvidenced: number }[]
  areas: { id: string; cs: string; en: string }[]
  controls: TraceControl[]
  obligations: TraceObligation[]
  summary: {
    controls: number
    byVerdict: Record<Verdict, number>
    machineEvidenced: number
    obligations: number
    obligationsWithControl: number
    obligationsByCoverage: Record<string, number>
  }
}

const REPO = 'https://github.com/JiRaska/open-bank-oss'
const KINDS: readonly EvidenceKind[] = ['gate', 'workflow', 'adr', 'path']
const LEVEL_RANK: Record<EvidenceLevel, number> = { none: 0, documented: 1, ci: 2, 'enforced-gate': 3 }

function blob(commit: string | null, file: string, line?: number): string {
  const ref = commit ?? 'main'
  return `${REPO}/blob/${ref}/${file}${line ? `#L${line}` : ''}`
}

function tree(commit: string | null, p: string): string {
  return `${REPO}/tree/${commit ?? 'main'}/${p}`
}

export function resolveEvidence(
  raw: { kind: string; ref: string },
  gates: Map<string, TraceInputGate>,
  adrSlugs: Record<string, string>,
  commit: string | null,
): TraceEvidence | null {
  const kind = raw.kind as EvidenceKind
  if (!KINDS.includes(kind)) return null
  const ref = String(raw.ref)
  switch (kind) {
    case 'gate': {
      const gate = gates.get(ref)
      return {
        kind, ref, label: gate?.name ?? ref,
        href: gate ? blob(commit, '.github/gates/gates.yaml', gate.line) : null,
        gateMode: gate?.mode ?? 'missing',
      }
    }
    case 'workflow':
      return { kind, ref, label: ref, href: blob(commit, `.github/workflows/${ref}`) }
    case 'adr': {
      const slug = adrSlugs[ref]
      return { kind, ref, label: `ADR-${ref}`, href: slug ? `/docs/adr/${slug}` : null }
    }
    case 'path':
      return { kind, ref, label: ref, href: tree(commit, ref) }
  }
}

export function evidenceLevel(refs: TraceEvidence[]): EvidenceLevel {
  let level: EvidenceLevel = 'none'
  for (const r of refs) {
    const l: EvidenceLevel = r.kind === 'gate'
      ? (r.gateMode === 'enforced' ? 'enforced-gate' : r.gateMode === 'advisory' ? 'ci' : 'none')
      : r.kind === 'workflow' ? 'ci' : 'documented'
    if (LEVEL_RANK[l] > LEVEL_RANK[level]) level = l
  }
  return level
}

/**
 * A positive claim (`enforced`, `partial`) needs evidence a machine re-runs: a gate or a workflow.
 * An ADR or a path documents intent, which is not the same as showing the control works. Weak
 * claims (`audit`, `planned`) are already honest about themselves and stand as declared.
 */
export function computeVerdict(declared: DeclaredStatus, level: EvidenceLevel): { verdict: Verdict; downgradeReason: TraceControl['downgradeReason'] } {
  if (declared === 'unknown') return { verdict: 'unverified', downgradeReason: null }
  if ((declared === 'enforced' || declared === 'partial') && LEVEL_RANK[level] < LEVEL_RANK.ci) {
    return { verdict: 'unverified', downgradeReason: 'no-machine-evidence' }
  }
  return { verdict: declared, downgradeReason: null }
}

export function buildComplianceTrace(inputs: TraceInputs): ComplianceTrace {
  const gates = new Map(inputs.gates.map(g => [g.id, g]))
  const controlIds = new Set(inputs.controls.map(c => c.id))

  const obligations: TraceObligation[] = inputs.obligations.map(o => {
    const listed = o.controls ?? []
    const known = listed.filter(id => controlIds.has(id))
    return { ...o, controls: known, unknownControls: listed.filter(id => !controlIds.has(id)), hasControl: known.length > 0 }
  })

  const obligationsByControl = new Map<string, string[]>()
  for (const o of obligations) {
    for (const cid of o.controls) obligationsByControl.set(cid, [...(obligationsByControl.get(cid) ?? []), o.id])
  }

  const controls: TraceControl[] = inputs.controls.map(c => {
    const { evidenceRefs: raw, ...rest } = c
    const refs = (raw ?? [])
      .map(r => resolveEvidence(r, gates, inputs.adrSlugs, inputs.commit))
      .filter((r): r is TraceEvidence => r !== null)
    const level = evidenceLevel(refs)
    return { ...rest, evidenceRefs: refs, evidenceLevel: level, ...computeVerdict(c.declaredStatus, level), obligationIds: obligationsByControl.get(c.id) ?? [] }
  })

  const byVerdict: Record<Verdict, number> = { enforced: 0, partial: 0, audit: 0, planned: 0, unknown: 0, unverified: 0 }
  for (const c of controls) byVerdict[c.verdict] += 1
  const isMachine = (c: TraceControl) => LEVEL_RANK[c.evidenceLevel] >= LEVEL_RANK.ci
  const obligationsByCoverage: Record<string, number> = {}
  for (const o of obligations) obligationsByCoverage[o.coverage.status] = (obligationsByCoverage[o.coverage.status] ?? 0) + 1

  return {
    asOf: { commit: inputs.commit, postureCollectedAt: inputs.postureCollectedAt },
    frameworks: inputs.frameworks.map(f => {
      const mine = controls.filter(c => c.frameworks.includes(f.id))
      return { ...f, controls: mine.length, machineEvidenced: mine.filter(isMachine).length }
    }),
    areas: inputs.areas,
    controls,
    obligations,
    summary: {
      controls: controls.length,
      byVerdict,
      machineEvidenced: controls.filter(isMachine).length,
      obligations: obligations.length,
      obligationsWithControl: obligations.filter(o => o.hasControl).length,
      obligationsByCoverage,
    },
  }
}
