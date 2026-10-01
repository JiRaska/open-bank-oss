// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Shared human labels for the risk-engine's regulatory vocabulary (liquidity factors, capital
// exposure classes, IFRS 9 stages) and a plain-language rendering of regulatory citations.
//
// WHY: the engine speaks in wire keys (`lcr-retail-loan-inflow`, `corporate`, `STAGE_1`) and in
// English citations meant for an auditor. A page that prints those verbatim is unreadable for the
// person it is for. Every finance/risk page reuses these maps instead of re-deriving its own
// spelling; an unknown key falls back to the key itself (never to an empty cell) so a new engine
// value is visible rather than silently dropped.

import type { CountUnit } from './aggregate'

export type Bilingual = { cs: string; en: string }
type Lang = 'cs' | 'en'

const pick = (b: Bilingual | undefined, lang: Lang, fallback: string) => (b ? b[lang] : fallback)

/** Liquidity factor key → the regulatory CATEGORY a reader recognises. */
export const LIQUIDITY_CATEGORY: Record<string, Bilingual> = {
  'lcr-retail-stable-runoff': { cs: 'Stabilní retailové vklady', en: 'Stable retail deposits' },
  'lcr-retail-less-stable-runoff': { cs: 'Méně stabilní retailové vklady', en: 'Less stable retail deposits' },
  'lcr-operational-deposit-runoff': { cs: 'Provozní vklady', en: 'Operational deposits' },
  'lcr-other-contractual-outflow': { cs: 'Ostatní smluvní závazky splatné do 30 dní', en: 'Other contractual liabilities due within 30 days' },
  'lcr-central-bank-secured-outflow': { cs: 'Zajištěné financování od centrální banky', en: 'Secured funding from the central bank' },
  'lcr-retail-loan-inflow': { cs: 'Splátky úvěrů nefinančním klientům splatné do 30 dní', en: 'Loan repayments from non-financial customers due within 30 days' },
  'lcr-fi-inflow': { cs: 'Pohledávky za finančními institucemi', en: 'Claims on financial institutions' },
  'lcr-inflow-fi-placement-30d': { cs: 'Umístění u bank splatná do 30 dní', en: 'Placements with banks maturing within 30 days' },
  'lcr-operational-deposit-inflow': { cs: 'Provozní vklady u jiných bank', en: 'Operational deposits held at other banks' },
  'nsfr-asf-capital': { cs: 'Kapitál', en: 'Capital' },
  'nsfr-asf-retail-stable': { cs: 'Stabilní retailové vklady', en: 'Stable retail deposits' },
  'nsfr-asf-retail-less-stable': { cs: 'Méně stabilní retailové vklady', en: 'Less stable retail deposits' },
  'nsfr-asf-operational-deposit': { cs: 'Provozní vklady', en: 'Operational deposits' },
  'nsfr-asf-other': { cs: 'Ostatní závazky a výsledek běžného roku', en: 'Other liabilities and current-year result' },
  'nsfr-asf-central-bank-under-6m': { cs: 'Financování od centrální banky do 6 měsíců', en: 'Central-bank funding under 6 months' },
  'nsfr-rsf-cash-and-reserves': { cs: 'Hotovost a rezervy u centrální banky', en: 'Cash and central-bank reserves' },
  'nsfr-rsf-l1-securities': { cs: 'Cenné papíry úrovně 1', en: 'Level 1 securities' },
  'nsfr-rsf-l2a': { cs: 'Aktiva úrovně 2A', en: 'Level 2A assets' },
  'nsfr-rsf-l2b': { cs: 'Aktiva úrovně 2B', en: 'Level 2B assets' },
  'nsfr-rsf-fi-under-6m': { cs: 'Pohledávky za finančními institucemi do 6 měsíců', en: 'Claims on financial institutions under 6 months' },
  'nsfr-rsf-operational-deposit-at-fi': { cs: 'Provozní vklady u jiných bank', en: 'Operational deposits held at other banks' },
  'nsfr-rsf-loan-under-1y': { cs: 'Jistina úvěrů splatná do 1 roku', en: 'Loan principal due within 1 year' },
  'nsfr-rsf-loan-1y-low-rw': { cs: 'Jistina úvěrů splatná za 1 rok a déle (nízká riziková váha)', en: 'Loan principal due in 1 year or more (low risk weight)' },
  'nsfr-rsf-loan-1y-other': { cs: 'Jistina úvěrů splatná za 1 rok a déle', en: 'Loan principal due in 1 year or more' },
  'nsfr-rsf-other-asset': { cs: 'Ostatní aktiva a úvěry v selhání', en: 'Other assets and non-performing loans' },
}

/** Capital exposure class wire key → Czech/English name. */
export const EXPOSURE_CLASS: Record<string, Bilingual> = {
  'sovereign-and-central-bank': { cs: 'Ústřední vlády a centrální banky', en: 'Sovereigns and central banks' },
  bank: { cs: 'Instituce', en: 'Institutions' },
  retail: { cs: 'Retailové expozice', en: 'Retail' },
  corporate: { cs: 'Podniky', en: 'Corporates' },
  defaulted: { cs: 'Expozice v selhání', en: 'Defaulted exposures' },
  cash: { cs: 'Hotovost', en: 'Cash' },
  'cash-items-in-collection': { cs: 'Hotovostní položky v inkasu', en: 'Cash items in collection' },
  'other-asset': { cs: 'Ostatní aktiva', en: 'Other assets' },
}

/** IFRS 9 stage → a sentence a non-specialist understands. */
export const IFRS9_STAGE: Record<string, Bilingual> = {
  STAGE_1: { cs: 'Fáze 1 — bez významného zhoršení', en: 'Stage 1 — no significant deterioration' },
  STAGE_2: { cs: 'Fáze 2 — významné zhoršení úvěrového rizika', en: 'Stage 2 — significant increase in credit risk' },
  STAGE_3: { cs: 'Fáze 3 — úvěr v selhání', en: 'Stage 3 — credit-impaired' },
}

export function liquidityCategoryLabel(factorKey: string | null | undefined, lang: Lang): string {
  if (!factorKey) return lang === 'cs' ? 'Nevážené položky' : 'Unweighted items'
  return pick(LIQUIDITY_CATEGORY[factorKey], lang, factorKey)
}
export function exposureClassLabel(key: string, lang: Lang): string {
  return pick(EXPOSURE_CLASS[key], lang, key)
}
export function ifrs9StageLabel(stage: string | null | undefined, lang: Lang): string {
  if (!stage) return lang === 'cs' ? 'Fáze IFRS 9 neuvedena' : 'IFRS 9 stage not reported'
  return pick(IFRS9_STAGE[stage], lang, stage)
}

export type PlainCitation = {
  /** The article reference in the reader's language, e.g. "čl. 32 odst. 3 písm. a) nařízení 2015/61". */
  article: string
  /** The engine marked the paragraph as not yet confirmed. Never rendered as text to the user. */
  unverified: boolean
  /** The engine's citation verbatim, for a tooltip / audit trail. */
  raw: string
}

const ARTICLE = /^(EU 2015\/61|CRR)\s+Art\.\s*(\d+[a-z]?)((?:\(\w+\))*)/
const BCBS = /^BCBS\s+(d\d+)\s+((?:¶[\w()\-–,\s]+?))(?=\s*\(|$)/

/**
 * "EU 2015/61 Art. 32(3)(a) (monies due …, 50%)" → "čl. 32 odst. 3 písm. a) nařízení 2015/61".
 * The parenthetical explanation is dropped (the category label already says it in plain words),
 * and an UNVERIFIED marker is turned into a flag instead of text.
 */
export function plainCitation(citation: string, lang: Lang): PlainCitation {
  const raw = citation.trim()
  const unverified = /UNVERIFIED/i.test(raw)
  const m = ARTICLE.exec(raw)
  if (m) {
    const [, law, art, subs] = m
    const parts = [...subs.matchAll(/\((\w+)\)/g)].map(x => x[1])
    const odst = parts.find(p => /^\d+$/.test(p))
    const pism = parts.find(p => /^[a-z]+$/.test(p))
    const regulation = law === 'CRR' ? (lang === 'cs' ? 'nařízení CRR (575/2013)' : 'CRR (575/2013)') : (lang === 'cs' ? 'nařízení 2015/61' : 'Regulation 2015/61')
    const article = lang === 'cs'
      ? [`čl. ${art}`, odst && `odst. ${odst}`, pism && `písm. ${pism})`, regulation].filter(Boolean).join(' ')
      : `${regulation} Art. ${art}${subs}`
    return { article, unverified, raw }
  }
  const b = BCBS.exec(raw)
  if (b) {
    const paras = b[2].replace(/¶/g, '').trim()
    return { article: lang === 'cs' ? `BCBS ${b[1]}, odst. ${paras}` : `BCBS ${b[1]} ¶${paras}`, unverified, raw }
  }
  // Unknown shape: keep only the part before the explanation, never the UNVERIFIED marker.
  const head = raw.split(' (')[0].replace(/;?\s*paragraph UNVERIFIED/gi, '').trim()
  return { article: head, unverified, raw }
}

/** "44 úvěrů" / "1 úvěr" / "3 úvěry" — Czech plural agreement for counts shown next to a category. */
export function czCount(n: number, one: string, few: string, many: string): string {
  const word = n === 1 ? one : n >= 2 && n <= 4 ? few : many
  return `${n.toLocaleString('cs-CZ')} ${word}`
}

/** "44 úvěrů" / "62 klientských účtů" — what a category row is made of. */
export function categoryCountText(g: { count: number; unit: CountUnit }, lang: Lang): string {
  if (lang === 'en') {
    const noun = g.unit === 'loans' ? 'loan' : g.unit === 'accounts' ? 'customer account' : 'GL account'
    return `${g.count} ${noun}${g.count === 1 ? '' : 's'}`
  }
  if (g.unit === 'loans') return czCount(g.count, 'úvěr', 'úvěry', 'úvěrů')
  if (g.unit === 'accounts') return czCount(g.count, 'klientský účet', 'klientské účty', 'klientských účtů')
  return czCount(g.count, 'účet hlavní knihy', 'účty hlavní knihy', 'účtů hlavní knihy')
}

/** Capital-ratio "not computable" reason code (risk-engine openapi 1.18.0) → plain Czech/English. */
const RATIOS_NOT_COMPUTABLE: Record<string, Bilingual> = {
  'multi-currency': {
    cs: 'Kniha obsahuje více měn a kapitál (účty 6000–6060) se do jedné měny nepřepočítává, takže ho nelze poměřit s rizikově váženými aktivy. Poměry půjde spočítat, až bude kapitál přepočten kurzem ČNB stejně jako RWA, nebo když bude kniha jen v jedné měně.',
    en: 'The book holds several currencies and own funds (accounts 6000–6060) are not converted to one currency, so they cannot be set against risk-weighted assets. The ratios become computable once own funds are converted at the CNB fixing like RWA, or when the book is in one currency.',
  },
  'no-positions': { cs: 'Snímek neobsahuje žádné pozice.', en: 'The snapshot has no positions.' },
  'no-own-funds': {
    cs: 'Ve snímku není žádný kapitálový účet (6000–6060), takže chybí čitatel poměru. Účty se zařazují v nastavení risk-engine: openbank.risk.capital.sa.classification.',
    en: 'The snapshot has no own-funds account (6000–6060), so the ratio has no numerator. Accounts are classified in risk-engine: openbank.risk.capital.sa.classification.',
  },
  'zero-rwa': { cs: 'Rizikově vážená aktiva jsou nulová, poměr není definován.', en: 'Risk-weighted assets are zero; the ratio is undefined.' },
}

export function ratiosNotComputableText(code: string | null | undefined, lang: Lang): string | null {
  return code && RATIOS_NOT_COMPUTABLE[code] ? RATIOS_NOT_COMPUTABLE[code][lang] : null
}

/**
 * Drop sentences carrying the engine's internal UNVERIFIED review marker from an explanatory text.
 * The marker is a to-do for whoever verifies the citation, not information for the reader.
 */
export function withoutReviewMarkers(text: string): string {
  return text.replace(/[^.;]*\bUNVERIFIED\b[^.;]*[.;]?\s*/g, '').replace(/\s+\)/g, ')').trim()
}
