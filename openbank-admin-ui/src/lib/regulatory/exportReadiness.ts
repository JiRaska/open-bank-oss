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
 *                   by `C0100Mapper`, and documented there as "MUST NOT be read as an attested
 *                   zero balance". This is the reason that actually fires today: the ledger's
 *                   chart of accounts has no capital-structure GL accounts, so every C 01.00
 *                   capital row is a flagged zero.
 *   - UNBALANCED  — `FinrepTemplate.isBalanced === false`.
 *
 * ⚠ KNOWN GAP, deliberately not papered over — tracked as #5987:
 * `isBalanced` is contract-required and serialised, but BOTH producers hardcode it —
 * `F0101Mapper.kt:44` and `F0200Mapper.kt:44` both pass the literal `isBalanced = true`, and
 * `FinrepTemplate.kt:17` defaults it to `true`. F01.01 additionally DERIVES equity as
 * `assets − liabilities`, so its accounting identity holds algebraically and cannot be observed
 * to fail. `FinrepService`'s own KDoc calls the value "computed ... and never looked at again";
 * the first half of that is not true today. So the `unbalanced` branch below is a real check
 * against the published contract that the current producer can never trigger. It is kept
 * because the contract permits `false` and a UI that ignored it would silently export an
 * unbalanced return the day the producer starts computing the field — but it must NOT be
 * counted as live coverage. The gate's falsifiable, fires-today reason is `data_gaps`.
 */

export type PreviewLike =
  | { status: 'idle' | 'loading' }
  | { status: 'unavailable'; kind: string }
  | { status: 'unsupported' }
  | { status: 'ready'; templates: ReadonlyArray<TemplateLike> }

export interface CellLike {
  isDataGap?: boolean
  gapReason?: string | null
}

export interface TemplateLike {
  templateId: string
  cells: ReadonlyArray<CellLike>
  isBalanced?: boolean
  hasDataGaps?: boolean
}

export type ExportBlockReason =
  /** Templates not fetched yet — idle or in flight. */
  | 'not_loaded'
  /** This catalogue report has no implemented data source at all. */
  | 'no_data_source'
  /** The fetch to finrep-service failed (unreachable, unauthorized, …). */
  | 'source_unavailable'
  /** A template came back with no cells — malformed against the contract. */
  | 'missing_cells'
  /** At least one cell is a flagged data gap (`isDataGap`). */
  | 'data_gaps'
  /** A FINREP template reports its accounting identity does not hold. */
  | 'unbalanced'

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
  // its own, independent of how completely it was populated.
  const unbalanced = data.templates.filter(template => template.isBalanced === false)
  if (unbalanced.length > 0) {
    return { ok: false, reason: 'unbalanced', templateIds: unbalanced.map(template => template.templateId) }
  }

  const gapped = data.templates.filter(
    template => template.hasDataGaps === true || template.cells.some(cell => cell.isDataGap === true),
  )
  if (gapped.length > 0) {
    return { ok: false, reason: 'data_gaps', templateIds: gapped.map(template => template.templateId) }
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
        ? { title: 'Export je zablokován: výkaz nevychází', detail: `Šablona ${list} hlásí, že její účetní identita neplatí (isBalanced=false).` }
        : { title: 'Export blocked: return does not balance', detail: `Template ${list} reports that its accounting identity does not hold (isBalanced=false).` }
  }
}
