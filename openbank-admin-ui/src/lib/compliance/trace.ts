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
export type EvidenceKind = 'gate' | 'workflow' | 'test' | 'adr' | 'path'
/** Strongest evidence behind a control: an enforced gate runs on every PR and blocks it. */
export type EvidenceLevel = 'enforced-gate' | 'ci' | 'documented' | 'none'
export type Verdict = DeclaredStatus | 'unverified'
/** Whether the newest passing gate run is recent enough for the control's declared `max_evidence_age_days`. */
export type Freshness = 'fresh' | 'stale' | 'unknown' | 'not-required'
export type DowngradeReason = 'no-machine-evidence' | 'failing-gate' | 'stale-evidence' | 'evidence-age-unknown'

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
  /** Positive claims must be backed by a gate pass at most this many days old (ADR-0379 phase 2). */
  maxEvidenceAgeDays?: number
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
  /** Per-gate latest status and newest pass from the gate-health snapshot (ADR-0255); `null` when unavailable. */
  gateHealth: { collectedAt: string; gates: Record<string, { status: string; lastOkAt: string | null }> } | null
  /** Evaluation instant, passed in so the verdict is deterministic. */
  now: string
}

export interface TraceEvidence {
  kind: EvidenceKind
  ref: string
  label: string
  href: string | null
  /** Only for kind `gate`; `missing` when the id is not in the baked gate catalogue. */
  gateMode?: 'enforced' | 'advisory' | 'missing'
  /** Only for kind `gate`, from the gate-health snapshot: latest observed status and newest pass. */
  gateStatus?: string | null
  lastOkAt?: string | null
}

export interface TraceControl extends Omit<TraceInputControl, 'evidenceRefs'> {
  evidence: string
  evidenceRefs: TraceEvidence[]
  evidenceLevel: EvidenceLevel
  verdict: Verdict
  /** Why the verdict differs from the declared status, if it does. */
  downgradeReason: DowngradeReason | null
  freshness: Freshness
  /** Newest pass of a gate behind this control whose latest run did not fail. */
  newestEvidenceAt: string | null
  obligationIds: string[]
}

export interface TraceObligation extends TraceInputObligation {
  controls: string[]
  /** Controls listed on the obligation that the catalogue does not know (CI rejects these). */
  unknownControls: string[]
  hasControl: boolean
}

export interface ComplianceTrace {
  asOf: { commit: string | null; postureCollectedAt: string | null; gateHealthCollectedAt: string | null; gateHealthCurrent: boolean; evaluatedAt: string }
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
const KINDS: readonly EvidenceKind[] = ['gate', 'workflow', 'test', 'adr', 'path']
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
  gateHealth: TraceInputs['gateHealth'] = null,
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
        gateStatus: gateHealth?.gates[ref]?.status ?? null,
        lastOkAt: gateHealth?.gates[ref]?.lastOkAt ?? null,
      }
    }
    case 'workflow':
      return { kind, ref, label: ref, href: blob(commit, `.github/workflows/${ref}`) }
    case 'test':
      return { kind, ref, label: ref.split('/').pop() ?? ref, href: blob(commit, ref) }
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
      : r.kind === 'workflow' || r.kind === 'test' ? 'ci' : 'documented'
    if (LEVEL_RANK[l] > LEVEL_RANK[level]) level = l
  }
  return level
}

const DAY_MS = 24 * 60 * 60 * 1000
/**
 * A gate-health snapshot older than this says nothing about whether a gate is red now, so it is
 * treated as absent: every age-bound claim becomes `evidence-age-unknown` and no gate status is
 * shown. It equals the published-evidence reader's own data-age bound (publishedSnapshot.ts); the
 * collector publishes every 30 minutes, so a healthy pipeline is always inside it.
 */
export const GATE_HEALTH_MAX_AGE_MS = 2 * 60 * 60 * 1000

const FAILED = new Set(['failed', 'unfalsified'])

/** The snapshot to judge with, or `null` when it is missing or too old to describe the present. */
export function currentGateHealth(health: TraceInputs['gateHealth'], now: string): TraceInputs['gateHealth'] {
  if (!health) return null
  const age = Date.parse(now) - Date.parse(health.collectedAt)
  return Number.isFinite(age) && age >= 0 && age <= GATE_HEALTH_MAX_AGE_MS ? health : null
}

/** True when any gate the control cites is red in the latest observed run. */
export function anyCitedGateFailing(refs: TraceEvidence[]): boolean {
  return refs.some(r => r.kind === 'gate' && r.gateStatus != null && FAILED.has(r.gateStatus))
}

/**
 * Evidence age counts only gates: they are the one kind whose runs are observed (gate-health
 * snapshot). A gate whose latest observed run failed does not vouch for the control today, however
 * recent its last pass was.
 */
export function evidenceFreshness(refs: TraceEvidence[], maxAgeDays: number | undefined, now: string): { freshness: Freshness; newestEvidenceAt: string | null } {
  const passes = refs
    .filter(r => r.kind === 'gate' && r.lastOkAt && !(r.gateStatus != null && FAILED.has(r.gateStatus)))
    .map(r => r.lastOkAt as string)
    .filter(t => Number.isFinite(Date.parse(t)))
    .sort((a, b) => Date.parse(a) - Date.parse(b))
  const newestEvidenceAt = passes.length ? passes[passes.length - 1] : null
  if (!maxAgeDays) return { freshness: 'not-required', newestEvidenceAt }
  if (!newestEvidenceAt) return { freshness: 'unknown', newestEvidenceAt }
  const fresh = Date.parse(now) - Date.parse(newestEvidenceAt) <= maxAgeDays * DAY_MS
  return { freshness: fresh ? 'fresh' : 'stale', newestEvidenceAt }
}

/**
 * A positive claim (`enforced`, `partial`) needs evidence a machine re-runs: a gate, a workflow or
 * a test. An ADR or a path documents intent, which is not the same as showing the control works.
 * Where the control declares a maximum evidence age, the newest passing gate run must also be
 * within it, and an age that cannot be established counts against the claim. Weak claims
 * (`audit`, `planned`) are already honest about themselves and stand as declared.
 */
export function computeVerdict(declared: DeclaredStatus, level: EvidenceLevel, freshness: Freshness = 'not-required', failingGate = false): { verdict: Verdict; downgradeReason: DowngradeReason | null } {
  if (declared === 'unknown') return { verdict: 'unverified', downgradeReason: null }
  const positive = declared === 'enforced' || declared === 'partial'
  if (positive && LEVEL_RANK[level] < LEVEL_RANK.ci) return { verdict: 'unverified', downgradeReason: 'no-machine-evidence' }
  // One red cited gate is enough: another gate passing does not prove the part this one checks.
  if (positive && failingGate) return { verdict: 'unverified', downgradeReason: 'failing-gate' }
  if (positive && freshness === 'stale') return { verdict: 'unverified', downgradeReason: 'stale-evidence' }
  if (positive && freshness === 'unknown') return { verdict: 'unverified', downgradeReason: 'evidence-age-unknown' }
  return { verdict: declared, downgradeReason: null }
}

export function buildComplianceTrace(inputs: TraceInputs): ComplianceTrace {
  const gateHealth = currentGateHealth(inputs.gateHealth, inputs.now)
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
      .map(r => resolveEvidence(r, gates, inputs.adrSlugs, inputs.commit, gateHealth))
      .filter((r): r is TraceEvidence => r !== null)
    const level = evidenceLevel(refs)
    const age = evidenceFreshness(refs, c.maxEvidenceAgeDays, inputs.now)
    return {
      ...rest, evidenceRefs: refs, evidenceLevel: level, ...age,
      ...computeVerdict(c.declaredStatus, level, age.freshness, anyCitedGateFailing(refs)),
      obligationIds: obligationsByControl.get(c.id) ?? [],
    }
  })

  const byVerdict: Record<Verdict, number> = { enforced: 0, partial: 0, audit: 0, planned: 0, unknown: 0, unverified: 0 }
  for (const c of controls) byVerdict[c.verdict] += 1
  const isMachine = (c: TraceControl) => LEVEL_RANK[c.evidenceLevel] >= LEVEL_RANK.ci
  const obligationsByCoverage: Record<string, number> = {}
  for (const o of obligations) obligationsByCoverage[o.coverage.status] = (obligationsByCoverage[o.coverage.status] ?? 0) + 1

  return {
    asOf: { commit: inputs.commit, postureCollectedAt: inputs.postureCollectedAt, gateHealthCollectedAt: inputs.gateHealth?.collectedAt ?? null, gateHealthCurrent: gateHealth !== null, evaluatedAt: inputs.now },
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
