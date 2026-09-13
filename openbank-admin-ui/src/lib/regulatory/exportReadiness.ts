// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Export gate for the regulatory reporting surface (issue #5904).
 *
 * A regulatory export is an artefact people ACT on, so producing one from data the platform
 * could not honestly derive is worse than producing nothing. Before this gate the CSV/JSON
 * buttons were enabled in every state except `loading` — so an operator could download a file
 * whose every value cell read "Zdroj dat není dostupný" ("data source unavailable") and whose
 * filename, headers and shape were indistinguishable from a real return.
 *
 * The three words in the acceptance criterion map onto data that finrep-service actually emits;
 * none of this is invented here:
 *
 *   - MISSING     — the templates were never obtained: still loading, the report has no data
 *                   source wired at all, or the fetch failed. Also a template rendered with
 *                   zero cells, which the contract says can never happen (`FinrepTemplate`
 *                   always renders every row) and so means a malformed payload.
 *   - INCOMPLETE  — `CorepCell.isDataGap` / `CorepTemplate.hasDataGaps`. Contract-REQUIRED
 *                   fields (openbank-finrep-service/src/main/resources/openapi.yaml
 *                   `required: [templateId, period, cells, hasDataGaps]`), genuinely computed
 *                   by the backend mappers, and documented there as "MUST NOT be read as an
 *                   attested zero balance". It fires for an unclassified active FINREP account
 *                   or a COREP trial balance with no recognised capital source.
 *   - UNBALANCED  — `FinrepTemplate.isBalanced === false`, refined by the optional
 *                   `balanceVerdict` into the three distinct defects below.
 *
 * `isBalanced` is no longer hardcoded by its producers. #6010 (issue #5987) made it a real
 * computation: `F0101Mapper` and `F0200Mapper` both pass `TrialBalanceIdentity.holds(lines)`,
 * and F01.01 stopped deriving equity as `assets - liabilities` (it now sources EQUITY/INCOME/
 * EXPENSE), so the identity can be observed to fail instead of holding algebraically. #6163
 * (issue #6011) then added `balanceVerdict`, which says WHICH of two independent verdicts
 * objected - finrep's own identity check, and the balance flag the ledger sealed onto the
 * trial balance it served:
 *
 *   - `AGREED_BALANCED`    - both agree it balances. The only value for which `isBalanced` is true.
 *   - `AGREED_IMBALANCED`  - both agree it does not: a genuine accounting defect.
 *   - `SOURCES_DISAGREE`   - the trial-balance lines finrep received are not the lines the ledger
 *                            sealed (a truncated or filtered response, e.g. an unread page). An
 *                            EVIDENCE defect: the numbers cannot be attributed to the ledger at
 *                            all, so nothing can be concluded about whether they balance.
 *   - `LEDGER_FLAG_ABSENT` - the ledger response carried no verdict at all. A CONTRACT change,
 *                            kept separate so that "the contract moved" never reads as "the books
 *                            do not balance".
 *
 * The field is optional here on purpose: it is required by finrep's contract but this UI must
 * stay readable against a deployment that predates #6163. A `false` `isBalanced` with no verdict
 * blocks under the plain unbalanced reason.
 */

export type PreviewLike =
  | { status: 'idle' | 'loading' }
  | { status: 'unavailable'; kind: string }
  | { status: 'no-periods' }
  | { status: 'unsupported' }
  | { status: 'ready'; templates: ReadonlyArray<TemplateLike>; evidence?: 'FROZEN' | 'LIVE_PREVIEW' }

export interface CellLike {
  isDataGap?: boolean
  gapReason?: string | null
}

/**
 * Which of the two independent balance verdicts objected (finrep openapi.yaml, issue #6011).
 * Optional: a deployment predating #6163 serves `isBalanced` alone.
 */
export type BalanceVerdict = 'AGREED_BALANCED' | 'AGREED_IMBALANCED' | 'SOURCES_DISAGREE' | 'LEDGER_FLAG_ABSENT'

export interface TemplateLike {
  templateId: string
  cells: ReadonlyArray<CellLike>
  isBalanced?: boolean
  balanceVerdict?: BalanceVerdict
  hasDataGaps?: boolean
}

export type ExportBlockReason =
  /** Templates not fetched yet — idle or in flight. */
  | 'not_loaded'
  /** This catalogue report has no implemented data source at all. */
  | 'no_data_source'
  /** The fetch to finrep-service failed (unreachable, unauthorized, …). */
  | 'source_unavailable'
  /** Ledger has no immutable frozen month evidence from which a return may be rendered. */
  | 'no_closed_periods'
  /** Values are real, but sourced from a mutable working trial balance rather than frozen evidence. */
  | 'provisional_data'
  /** A template came back with no cells — malformed against the contract. */
  | 'missing_cells'
  /** At least one cell is a flagged data gap (`isDataGap`). */
  | 'data_gaps'
  /** A FINREP template reports its accounting identity does not hold, and the ledger agrees. */
  | 'unbalanced'
  /** finrep and the ledger disagree about the balance - the lines received are not the lines sealed. */
  | 'balance_sources_disagree'
  /** The ledger's trial-balance response carried no balance verdict at all. */
  | 'ledger_verdict_absent'

export type ExportReadiness =
  | { ok: true }
  | { ok: false; reason: ExportBlockReason; templateIds: string[] }

/**
 * Decide whether a regulatory export may be produced. Ordered most-fundamental first, so the
 * reason an operator is shown is the one they can act on: you cannot judge balance of data you
 * never received.
 */
export function evaluateExportReadiness(data: PreviewLike): ExportReadiness {
  if (data.status === 'unsupported') {
    return { ok: false, reason: 'no_data_source', templateIds: [] }
  }
  if (data.status === 'unavailable') {
    return { ok: false, reason: 'source_unavailable', templateIds: [] }
  }
  if (data.status === 'no-periods') {
    return { ok: false, reason: 'no_closed_periods', templateIds: [] }
  }
  // Everything that is not `ready` is data we do not have: idle or still in flight. Tested via
  // `!== 'ready'` rather than by listing the two so that a future PreviewData variant defaults to
  // BLOCKED — an unknown state must never fall through into producing a regulatory file.
  if (data.status !== 'ready') {
    return { ok: false, reason: 'not_loaded', templateIds: [] }
  }

  if (data.templates.length === 0) {
    return { ok: false, reason: 'missing_cells', templateIds: [] }
  }

  const empty = data.templates.filter(template => template.cells.length === 0)
  if (empty.length > 0) {
    return { ok: false, reason: 'missing_cells', templateIds: empty.map(template => template.templateId) }
  }

  // Unbalanced outranks data gaps: a return that does not balance is a supervisory defect on
  // its own, independent of how completely it was populated. All three non-balanced verdicts
  // block; they differ only in what the operator is being told to go and fix.
  const unbalanced = data.templates.filter(template => template.isBalanced === false)
  if (unbalanced.length > 0) {
    // Ordered by how fundamental the defect is, on the same principle as the checks above: a
    // missing verdict means the contract moved, and disagreeing sources mean the figures cannot
    // be attributed to the ledger at all - neither says anything about whether the books
    // balance, so neither may be reported to an operator as an accounting failure.
    const idsFor = (verdict: BalanceVerdict) =>
      unbalanced.filter(template => template.balanceVerdict === verdict).map(template => template.templateId)

    const absent = idsFor('LEDGER_FLAG_ABSENT')
    if (absent.length > 0) {
      return { ok: false, reason: 'ledger_verdict_absent', templateIds: absent }
    }
    const disagree = idsFor('SOURCES_DISAGREE')
    if (disagree.length > 0) {
      return { ok: false, reason: 'balance_sources_disagree', templateIds: disagree }
    }
    // AGREED_IMBALANCED, plus a template from a deployment that serves no verdict at all.
    return { ok: false, reason: 'unbalanced', templateIds: unbalanced.map(template => template.templateId) }
  }

  const gapped = data.templates.filter(
    template => template.hasDataGaps === true || template.cells.some(cell => cell.isDataGap === true),
  )
  if (gapped.length > 0) {
    return { ok: false, reason: 'data_gaps', templateIds: gapped.map(template => template.templateId) }
  }

  if (data.evidence === 'LIVE_PREVIEW') {
    return { ok: false, reason: 'provisional_data', templateIds: data.templates.map(template => template.templateId) }
  }

  return { ok: true }
}

/** Operator-facing copy for a block reason. Czech first, matching this surface's convention. */
export function blockReasonCopy(
  reason: ExportBlockReason,
  templateIds: string[],
  lang: 'cs' | 'en',
): { title: string; detail: string } {
  const list = templateIds.join(', ')
  const cs = lang === 'cs'
  switch (reason) {
    case 'not_loaded':
      return cs
        ? { title: 'Export je zablokován: data nejsou načtena', detail: 'Načtěte šablony pro zvolené referenční datum. Export se odemkne až nad ověřenými daty.' }
        : { title: 'Export blocked: data not loaded', detail: 'Load the templates for the chosen reference date. Export unlocks only over verified data.' }
    case 'no_data_source':
      return cs
        ? { title: 'Export je zablokován: výkaz nemá datový zdroj', detail: 'Tento katalogový výkaz zatím není napojen na žádnou službu. Export by obsahoval jen zástupné texty, ne hodnoty.' }
        : { title: 'Export blocked: report has no data source', detail: 'This catalogue report is not wired to any service yet. An export would carry placeholder text, not values.' }
    case 'source_unavailable':
      return cs
        ? { title: 'Export je zablokován: zdroj dat není dostupný', detail: 'finrep-service neodpověděla, takže hodnoty nelze ověřit. Zkuste načtení zopakovat.' }
        : { title: 'Export blocked: data source unavailable', detail: 'finrep-service did not answer, so the values cannot be verified. Try loading again.' }
    case 'no_closed_periods':
      return cs
        ? { title: 'Export je zablokován: chybí uzavřené období', detail: 'Ledger nemá žádné zmrazené měsíční období s neměnnou řádkovou evidencí (FROZEN / LINES_V1). Dokončete uzávěrku; živá předvaha se pro regulatorní výkaz nikdy nepoužije.' }
        : { title: 'Export blocked: no closed reporting period', detail: 'The ledger has no frozen monthly period with immutable line evidence (FROZEN / LINES_V1). Complete the close; a live trial balance is never used for a regulatory return.' }
    case 'provisional_data':
      return cs
        ? { title: 'Export je zablokován: pracovní náhled není zapečetěný', detail: `Šablona ${list} zobrazuje skutečné hodnoty z živé předvahy, ale období ještě nemá FROZEN / LINES_V1 evidenci. Hodnoty lze kontrolovat, nelze je vydat za regulatorní artefakt.` }
        : { title: 'Export blocked: working preview is not sealed', detail: `Template ${list} shows real values from the live trial balance, but the period has no FROZEN / LINES_V1 evidence yet. Values may be reviewed, not represented as a regulatory artefact.` }
    case 'missing_cells':
      return cs
        ? { title: 'Export je zablokován: šablona je prázdná', detail: `Šablona ${list} se vrátila bez buněk. Podle kontraktu se vykresluje vždy každý řádek, takže jde o vadnou odpověď.` }
        : { title: 'Export blocked: template is empty', detail: `Template ${list} returned no cells. The contract renders every row always, so this is a malformed response.` }
    case 'data_gaps':
      return cs
        ? { title: 'Export je zablokován: neúplná data', detail: `Šablona ${list} obsahuje buňky označené jako datová mezera (isDataGap) — vykázané nuly, které platforma neumí doložit. Nesmí se odeslat jako doložený zůstatek.` }
        : { title: 'Export blocked: incomplete data', detail: `Template ${list} contains cells flagged as data gaps (isDataGap) — reported zeros the platform cannot substantiate. They must not be filed as attested balances.` }
    case 'unbalanced':
      return cs
        ? { title: 'Export je zablokován: výkaz nevychází', detail: `Šablona ${list} hlásí, že její účetní identita neplatí, a ledger se s tím shoduje (balanceVerdict=AGREED_IMBALANCED). Jde o účetní vadu — rozdíl je nutné dohledat v hlavní knize.` }
        : { title: 'Export blocked: return does not balance', detail: `Template ${list} reports that its accounting identity does not hold, and the ledger agrees (balanceVerdict=AGREED_IMBALANCED). This is an accounting defect — the difference has to be traced in the general ledger.` }
    case 'balance_sources_disagree':
      return cs
        ? { title: 'Export je zablokován: zdroje se o vyváženosti neshodují', detail: `U šablony ${list} se rozchází vlastní kontrola finrepu s příznakem, který zapečetil ledger (balanceVerdict=SOURCES_DISAGREE). Řádky obratové předvahy, které finrep dostal, tedy nejsou ty, které ledger zapečetil — zkrácená nebo filtrovaná odpověď (např. nepřečtená stránka). Jde o vadu doložitelnosti, ne o účetní rozdíl: o vyváženosti výkazu zatím nelze říct nic.` }
        : { title: 'Export blocked: the two balance sources disagree', detail: `For template ${list}, finrep's own identity check and the flag the ledger sealed do not agree (balanceVerdict=SOURCES_DISAGREE). The trial-balance lines finrep received are therefore not the lines the ledger sealed — a truncated or filtered response, e.g. an unread page. This is an evidence defect, not an accounting one: nothing can yet be concluded about whether the return balances.` }
    case 'ledger_verdict_absent':
      return cs
        ? { title: 'Export je zablokován: ledger nevrátil verdikt o vyváženosti', detail: `Odpověď ledgeru pro šablonu ${list} neobsahovala žádný příznak vyváženosti (balanceVerdict=LEDGER_FLAG_ABSENT). Nezávislá kontrola tak chybí — jde o změnu kontraktu, kterou je nutné vyřešit v ledgeru, ne o nevycházející výkaz.` }
        : { title: 'Export blocked: the ledger returned no balance verdict', detail: `The ledger response for template ${list} carried no balance flag at all (balanceVerdict=LEDGER_FLAG_ABSENT). The independent check is therefore missing — this is a contract change to resolve in the ledger, not a return that fails to balance.` }
  }
}
