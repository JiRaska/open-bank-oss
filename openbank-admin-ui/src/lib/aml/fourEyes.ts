// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Client contract for aml-service's four-eyes monitoring dispositions and suspicious-transaction
// reports (ADR-0371 §5, §10). The service is the authority on every rule here — who may approve
// (a different person than the maker), which states may follow which — and the acting person is
// always the authenticated principal, never a body field. This file only parses what comes back
// and lists the choices the UI may offer.

export const DISPOSITIONS = ['CLEAR', 'REPORT'] as const
export type Disposition = (typeof DISPOSITIONS)[number]

/** One outcome the officer can choose, as aml-service serves it at `/api/v1/aml/monitoring/outcomes`. */
export interface OutcomeEntry { code: string; label: string; meaning: string }

/**
 * Czech display labels by code. The SET of choosable codes always comes from aml-service (the
 * vocabulary `outcomeCode` is resolved against); this only translates. A code the service adds
 * without a translation is still offered, under the service's own English label.
 * `aml-four-eyes-contract.test.ts` reads aml-service's vocabulary file and fails on a missing
 * translation, so the gap is noticed rather than shipped.
 */
export const OUTCOME_LABELS_CS: Record<string, string> = {
  CONFIRMED_SUSPICION: 'Podezření potvrzeno',
  FALSE_POSITIVE_EXPLAINED_ACTIVITY: 'Falešný poplach: aktivita vysvětlena',
  FALSE_POSITIVE_DATA_QUALITY: 'Falešný poplach: kvalita dat',
  FALSE_POSITIVE_SCENARIO_TOO_BROAD: 'Falešný poplach: scénář je příliš široký',
  INSUFFICIENT_INFORMATION: 'Nedostatek informací',
}

export function outcomeLabel(entry: OutcomeEntry | undefined, code: string | null, language: 'cs' | 'en'): string {
  if (!code) return '—'
  if (language === 'cs' && OUTCOME_LABELS_CS[code]) return OUTCOME_LABELS_CS[code]
  return entry?.label ?? code
}

export function parseOutcomeCatalogue(raw: unknown): OutcomeEntry[] {
  const entries = obj(raw)?.entries
  if (!Array.isArray(entries)) throw new Error('Invalid outcome vocabulary')
  const parsed = entries.map(e => {
    const o = obj(e)
    const code = str(o?.code), label = str(o?.label)
    return code && label ? { code, label, meaning: str(o?.meaning) ?? '' } : null
  }).filter((e): e is OutcomeEntry => e !== null)
  if (parsed.length === 0) throw new Error('Empty outcome vocabulary')
  return parsed
}

/**
 * Outcomes that make sense for a disposition: REPORT with a false-positive outcome, or CLEAR
 * with confirmed suspicion, is incoherent. Codes outside both families are offered for both.
 */
export function outcomesFor(disposition: Disposition, all: readonly OutcomeEntry[]): OutcomeEntry[] {
  return disposition === 'REPORT'
    ? all.filter(o => !o.code.startsWith('FALSE_POSITIVE'))
    : all.filter(o => o.code !== 'CONFIRMED_SUSPICION')
}

export interface DispositionProposal {
  proposalId: string; caseId: string; disposition: string; outcomeCode: string; note: string | null
  state: string; proposedBy: string; proposedAt: string
  decidedBy: string | null; decidedAt: string | null; decisionReason: string | null
}

export interface StrReport {
  reportId: string; partyId: string; state: 'DRAFT' | 'APPROVED' | 'FILED' | 'WITHDRAWN'; narrative: string
  caseIds: string[]; draftedBy: string; draftedAt: string; approvedBy: string | null; approvedAt: string | null
  filingReference: string | null; filedAt: string | null; withdrawnBy: string | null; withdrawnAt: string | null
  outcomeCode: string | null; updatedAt: string
}

const REPORT_STATES = ['DRAFT', 'APPROVED', 'FILED', 'WITHDRAWN'] as const

const obj = (v: unknown): Record<string, unknown> | null =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
const str = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v : null)

export function parseProposal(raw: unknown): DispositionProposal | null {
  const o = obj(raw)
  if (!o) return null
  const proposalId = str(o.proposalId), caseId = str(o.caseId), disposition = str(o.disposition)
  const state = str(o.state), proposedBy = str(o.proposedBy), proposedAt = str(o.proposedAt)
  const outcomeCode = str(obj(o.outcome)?.code)
  if (!proposalId || !caseId || !disposition || !state || !proposedBy || !proposedAt || !outcomeCode) return null
  return {
    proposalId, caseId, disposition, outcomeCode, note: str(o.note), state, proposedBy, proposedAt,
    decidedBy: str(o.decidedBy), decidedAt: str(o.decidedAt), decisionReason: str(o.decisionReason),
  }
}

export function parseReport(raw: unknown): StrReport | null {
  const o = obj(raw)
  if (!o) return null
  const reportId = str(o.reportId), partyId = str(o.partyId), narrative = str(o.narrative)
  const draftedBy = str(o.draftedBy), draftedAt = str(o.draftedAt), updatedAt = str(o.updatedAt)
  const state = REPORT_STATES.find(s => s === o.state)
  if (!reportId || !partyId || !narrative || !draftedBy || !draftedAt || !updatedAt || !state) return null
  const caseIds = Array.isArray(o.caseIds) ? o.caseIds.filter((c): c is string => typeof c === 'string') : []
  return {
    reportId, partyId, state, narrative, caseIds, draftedBy, draftedAt, updatedAt,
    approvedBy: str(o.approvedBy), approvedAt: str(o.approvedAt), filingReference: str(o.filingReference),
    filedAt: str(o.filedAt), withdrawnBy: str(o.withdrawnBy), withdrawnAt: str(o.withdrawnAt),
    outcomeCode: str(obj(o.outcome)?.code),
  }
}

export const parseList = <T,>(raw: unknown, parse: (v: unknown) => T | null): T[] =>
  Array.isArray(raw) ? raw.map(parse).filter((v): v is T => v !== null) : []

/**
 * A failed four-eyes or report call as an officer should read it. 403 is the role (these
 * endpoints admit ROLE_COMPLIANCE only); 422 is the maker-checker rule; 409 a state
 * conflict. The service's message is the useful part, so it is passed through.
 */
export async function describeFailure(res: Response, t: (cs: string, en: string) => string): Promise<string> {
  if (res.status === 403) return t('Tato akce vyžaduje roli compliance (ROLE_COMPLIANCE).', 'This action requires the compliance role (ROLE_COMPLIANCE).')
  let detail = ''
  try { const body = obj(await res.clone().json()); detail = str(body?.message) ?? str(body?.error) ?? '' } catch { /* not JSON */ }
  // 422 is libs-runtime's mapping of IllegalStateException, which is what MakerCheckerViolation is:
  // the maker tried to approve their own proposal or report.
  if (res.status === 422) return `${t('Druhé schválení musí udělat jiná osoba než navrhovatel', 'The second approval must come from someone other than the maker')}${detail ? ` (${detail})` : ''}.`
  if (res.status === 409) return `${t('Stav se mezitím změnil', 'The state changed meanwhile')}${detail ? `: ${detail}` : ''}. ${t('Načtěte případ znovu.', 'Reload the case.')}`
  if (res.status === 400) return `${t('Neplatný požadavek', 'Invalid request')}${detail ? `: ${detail}` : ''}`
  return t('Akci se nepodařilo provést. Zkuste to znovu.', 'The action could not be completed. Try again.')
}
