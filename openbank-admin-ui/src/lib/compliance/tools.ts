// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Read-only tools over the compliance trace (ADR-0379 phase 3), and the citation contract for any
// model answer about it. Pure: the MCP route and the in-page assistant call these with a trace that
// src/lib/compliance/trace.ts already computed, so a tool can never produce a verdict the page
// would not. No tool writes anything. Statuses change only through a reviewed PR to the YAML.

import type { ComplianceTrace, TraceControl, TraceObligation } from './trace'

export interface ToolSpec {
  name: string
  description: string
  inputSchema: { type: 'object'; properties: Record<string, { type: string; description: string }>; required?: string[]; additionalProperties: false }
}

export const COMPLIANCE_TOOLS: readonly ToolSpec[] = [
  {
    name: 'compliance_list_obligations',
    description: 'List regulatory obligations with their coverage status and linked control ids. Optional filters: area, coverage status.',
    inputSchema: {
      type: 'object',
      properties: {
        area: { type: 'string', description: 'Obligation area id, e.g. ict, aml, payments' },
        coverage: { type: 'string', description: 'Coverage status, e.g. missing, filed-return' },
      },
      additionalProperties: false,
    },
  },
  {
    name: 'compliance_get_trace',
    description: 'Get the full obligation -> control -> evidence chain for one obligation id or one control id.',
    inputSchema: {
      type: 'object',
      properties: { id: { type: 'string', description: 'An obligation id or a control id' } },
      required: ['id'],
      additionalProperties: false,
    },
  },
  {
    name: 'compliance_list_gaps',
    description: 'List unverified controls with the reason, and reporting obligations with no backend output (missing or bff-only).',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
  {
    name: 'compliance_get_evidence',
    description: 'Get the evidence references, evidence strength, age and computed verdict of one control.',
    inputSchema: {
      type: 'object',
      properties: { controlId: { type: 'string', description: 'A control id from the catalogue' } },
      required: ['controlId'],
      additionalProperties: false,
    },
  },
]

export class ToolInputError extends Error {}

function str(args: Record<string, unknown>, key: string, required: boolean): string | undefined {
  const v = args[key]
  if (v === undefined || v === null || v === '') {
    if (required) throw new ToolInputError(`argument '${key}' is required`)
    return undefined
  }
  if (typeof v !== 'string' || v.length > 200) throw new ToolInputError(`argument '${key}' must be a string of at most 200 characters`)
  return v
}

const controlSummary = (c: TraceControl) => ({
  ref: `control:${c.id}`, id: c.id, title: c.title, declaredStatus: c.declaredStatus, verdict: c.verdict,
  downgradeReason: c.downgradeReason, evidenceLevel: c.evidenceLevel, freshness: c.freshness,
  newestEvidenceAt: c.newestEvidenceAt, frameworks: c.frameworks, obligationIds: c.obligationIds,
})

const obligationSummary = (o: TraceObligation) => ({
  ref: `obligation:${o.id}`, id: o.id, area: o.area, name: o.name, authority: o.authority,
  legalBasis: o.legalBasis, coverage: o.coverage.status, cadence: o.cadence,
  cadenceConfidence: o.cadenceConfidence, controls: o.controls,
})

/** Every result carries the snapshot it was computed from, so an answer can be dated. */
export function callComplianceTool(trace: ComplianceTrace, name: string, rawArgs: unknown): unknown {
  const args = (rawArgs && typeof rawArgs === 'object' && !Array.isArray(rawArgs) ? rawArgs : {}) as Record<string, unknown>
  const spec = COMPLIANCE_TOOLS.find(t => t.name === name)
  if (!spec) throw new ToolInputError(`unknown tool '${name}'`)
  const unknown = Object.keys(args).filter(k => !(k in spec.inputSchema.properties))
  if (unknown.length) throw new ToolInputError(`unknown argument(s): ${unknown.join(', ')}`)
  const asOf = trace.asOf

  switch (name) {
    case 'compliance_list_obligations': {
      const area = str(args, 'area', false)
      const coverage = str(args, 'coverage', false)
      const rows = trace.obligations
        .filter(o => (!area || o.area === area) && (!coverage || o.coverage.status === coverage))
        .map(obligationSummary)
      return { asOf, count: rows.length, obligations: rows }
    }
    case 'compliance_get_trace': {
      const id = str(args, 'id', true) as string
      const obligation = trace.obligations.find(o => o.id === id)
      if (obligation) {
        const controls = trace.controls.filter(c => obligation.controls.includes(c.id))
        return { asOf, obligation: obligationSummary(obligation), controls: controls.map(c => ({ ...controlSummary(c), evidenceRefs: c.evidenceRefs })) }
      }
      const control = trace.controls.find(c => c.id === id)
      if (control) {
        return { asOf, control: { ...controlSummary(control), evidenceRefs: control.evidenceRefs }, obligations: trace.obligations.filter(o => o.controls.includes(id)).map(obligationSummary) }
      }
      throw new ToolInputError(`no obligation or control with id '${id}'`)
    }
    case 'compliance_list_gaps':
      return {
        asOf,
        unverifiedControls: trace.controls.filter(c => c.verdict === 'unverified').map(controlSummary),
        uncoveredObligations: trace.obligations.filter(o => o.coverage.status === 'missing' || o.coverage.status === 'bff-only').map(obligationSummary),
      }
    case 'compliance_get_evidence': {
      const controlId = str(args, 'controlId', true) as string
      const control = trace.controls.find(c => c.id === controlId)
      if (!control) throw new ToolInputError(`no control with id '${controlId}'`)
      return { asOf, ...controlSummary(control), evidence: control.evidence, evidenceRefs: control.evidenceRefs }
    }
  }
  throw new ToolInputError(`unknown tool '${name}'`)
}

// ── Row selection (the in-page assistant) ───────────────────────────────────────────────────
//
// The model writes no prose that reaches the reader. Classifying free text for status claims
// cannot be closed (synonyms, homoglyphs, symbols), so the model's only job is retrieval: pick the
// trace rows relevant to a question and return their tokens. The page renders those rows'
// computed facts and authored text from the YAML. The worst a wrong pick can do is show true rows
// that do not answer the question. It cannot show a false one.

const TOKEN = /\[\[(control|obligation):([^\]\n]{1,120})\]\]/g
export const MAX_SELECTED_ROWS = 8

/** Rows, one per line, each with the only token the model may answer with. */
export function buildSelectionContext(trace: ComplianceTrace): string {
  const lines = [
    `Compliance trace as of commit ${trace.asOf.commit ?? 'unknown'}.`,
    `Reply with ONLY the tokens of the rows relevant to the question, one per line, at most ${MAX_SELECTED_ROWS}, most relevant first.`,
    'Write no other text: no words, no punctuation, no explanation. Anything besides tokens makes the reply void.',
    '',
  ]
  for (const c of trace.controls) lines.push(`[[control:${c.id}]] ${c.title} — ${c.category}; frameworks ${c.frameworks.join(', ')}; ${c.references.join(', ')}`)
  for (const o of trace.obligations) lines.push(`[[obligation:${o.id}]] ${o.name.en} — area ${o.area}; authority ${o.authority}; ${o.legalBasis}`)
  return lines.join('\n')
}

export interface Selection {
  ok: boolean
  /** Resolving tokens in the order given, deduplicated and capped. */
  selected: string[]
  /** Tokens that name no row. */
  unknown: string[]
  /** True when the reply carried anything other than tokens and whitespace. */
  extraText: boolean
}

/** A reply is usable only when it is nothing but tokens, at least one, and every token resolves. */
export function parseSelection(reply: string, trace: ComplianceTrace): Selection {
  const known = new Set([...trace.controls.map(c => `control:${c.id}`), ...trace.obligations.map(o => `obligation:${o.id}`)])
  const selected: string[] = []
  const unknown: string[] = []
  for (const m of reply.matchAll(TOKEN)) {
    const token = `${m[1]}:${m[2]}`
    if (!known.has(token)) { if (!unknown.includes(token)) unknown.push(token) } else if (!selected.includes(token)) selected.push(token)
  }
  // Separators allowed between tokens: ASCII whitespace and commas only. Anything else, including
  // invisible format characters, counts as text the model was told not to write.
  const extraText = /[^ \t\r\n,]/.test(reply.replace(TOKEN, ''))
  return { ok: selected.length > 0 && unknown.length === 0 && !extraText, selected: selected.slice(0, MAX_SELECTED_ROWS), unknown, extraText }
}

/** What the page shows for each selected row: computed facts and authored YAML text only. */
export function selectedRows(trace: ComplianceTrace, selected: string[]) {
  return selected.map(ref => {
    const [kind, id] = ref.split(':')
    if (kind === 'control') {
      const c = trace.controls.find(x => x.id === id)!
      return {
        ref, kind: 'control' as const, title: c.title, verdict: c.verdict, declaredStatus: c.declaredStatus,
        evidenceLevel: c.evidenceLevel, downgradeReason: c.downgradeReason, evidence: c.evidence, obligationIds: c.obligationIds,
      }
    }
    const o = trace.obligations.find(x => x.id === id)!
    return { ref, kind: 'obligation' as const, title: o.name.en, titleCs: o.name.cs, coverage: o.coverage.status, legalBasis: o.legalBasis, controls: o.controls }
  })
}
