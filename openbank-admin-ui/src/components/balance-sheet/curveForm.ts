// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Guided curve-set upload (ADR-0313 D4, #11107). Pure logic behind CurveUploadForm: the index and
// tenor vocabulary risk-engine accepts, rate parsing as a Czech desk types it, clipboard paste from
// Excel, validation (errors block the upload, warnings only inform) and the mapping onto the
// unchanged POST /api/v1/risk/curve-sets contract.

import { CURVE_INDICES, type CurveIndexName } from './model'

export interface IndexInfo {
  id: CurveIndexName
  currency: 'CZK' | 'EUR'
  label: string
  cs: string
  en: string
}

/** Every index CurveIndex.kt accepts — and nothing else, so the picker cannot offer a 400. */
export const INDEX_INFO: readonly IndexInfo[] = [
  { id: 'CZEONIA', currency: 'CZK', label: 'CZEONIA', cs: 'Jednodenní (overnight) sazba ČNB. Diskontní křivka pro všechny toky v CZK.', en: 'CNB overnight rate. Discount curve for every CZK flow.' },
  { id: 'PRIBOR_1M', currency: 'CZK', label: 'PRIBOR 1M', cs: 'Mezibankovní sazba na 1 měsíc. Projekce úvěrů a vkladů plovoucích na PRIBOR 1M.', en: '1-month interbank rate. Projects loans/deposits floating on PRIBOR 1M.' },
  { id: 'PRIBOR_3M', currency: 'CZK', label: 'PRIBOR 3M', cs: 'Mezibankovní sazba na 3 měsíce. Nejčastější referenční sazba úvěrů v CZK.', en: '3-month interbank rate. The most common CZK loan reference.' },
  { id: 'PRIBOR_6M', currency: 'CZK', label: 'PRIBOR 6M', cs: 'Mezibankovní sazba na 6 měsíců. Projekce kontraktů plovoucích na PRIBOR 6M.', en: '6-month interbank rate. Projects contracts floating on PRIBOR 6M.' },
  { id: 'ESTR', currency: 'EUR', label: '€STR', cs: 'Jednodenní sazba ECB. Diskontní křivka pro všechny toky v EUR.', en: 'ECB overnight rate. Discount curve for every EUR flow.' },
  { id: 'EURIBOR_3M', currency: 'EUR', label: 'EURIBOR 3M', cs: 'Mezibankovní sazba EUR na 3 měsíce. Projekce kontraktů plovoucích na EURIBOR.', en: '3-month EUR interbank rate. Projects EURIBOR-floating contracts.' },
]

export const indexInfo = (id: string) => INDEX_INFO.find(i => i.id === id)

/** Standard money-market tenors. risk-engine's Tenor.parse refuses anything past one year. */
export const STANDARD_TENORS = ['ON', '1W', '1M', '3M', '6M', '1Y'] as const

const TENOR = /^(ON|([1-9][0-9]{0,2})([DWMY]))$/
const MAX = { D: 7, W: 52, M: 12, Y: 1 } as const

/** Approximate length in days, for ordering and the monotonicity hint only (never sent). */
export function tenorDays(tenor: string): number | null {
  const m = TENOR.exec(tenor.trim().toUpperCase())
  if (!m) return null
  if (m[1] === 'ON') return 1
  const n = Number(m[2])
  const unit = m[3] as keyof typeof MAX
  if (n > MAX[unit]) return null
  return unit === 'D' ? n : unit === 'W' ? n * 7 : unit === 'M' ? n * 30.4375 : n * 365.25
}

/** 1Y and 12M end on the same day — risk-engine refuses two quotes with one end date. */
const canonicalTenor = (t: string) => (t === '1Y' ? '12M' : t)

/** "3,5", "3.50 %", " -0,1" → number in percent; null when not a number. */
export function parseRate(raw: string): number | null {
  const s = raw.replace(/\s|%| /g, '').replace(',', '.')
  if (!/^[-+]?(\d+\.?\d*|\.\d+)$/.test(s)) return null
  return Number(s)
}

export interface GridRow { tenor: string; rate: string }

/**
 * Clipboard text from Excel/LibreOffice/a terminal: one row per line, tab/semicolon/space separated.
 * Two columns are TENOR RATE; three are INDEX TENOR RATE and route to that index. A header row
 * (no parseable rate) is skipped rather than reported.
 */
export function parsePaste(text: string, fallbackIndex: CurveIndexName): { rows: Record<string, GridRow[]>; skipped: number } {
  const rows: Record<string, GridRow[]> = {}
  let skipped = 0
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line) continue
    const cols = line.split(/\t|;|\s+/).map(c => c.trim()).filter(Boolean)
    let index: string = fallbackIndex
    let tenor: string
    let rate: string
    if (cols.length === 2) [tenor, rate] = cols
    else if (cols.length === 3) {
      const idx = normaliseIndex(cols[0])
      if (!idx) { skipped++; continue }
      ;[index, tenor, rate] = [idx, cols[1], cols[2]]
    } else { skipped++; continue }
    if (parseRate(rate) === null) { skipped++; continue }
    ;(rows[index] ??= []).push({ tenor: tenor.toUpperCase(), rate })
  }
  return { rows, skipped }
}

function normaliseIndex(raw: string): CurveIndexName | null {
  const k = raw.toUpperCase().replace('€', 'E').replace(/[\s-]/g, '_')
  return (CURVE_INDICES as readonly string[]).includes(k) ? (k as CurveIndexName) : null
}

export type RowIssue = 'tenor' | 'tenor-range' | 'rate' | 'rate-range' | 'duplicate'
export interface GridValidation {
  /** row index → blocking problem */
  errors: Record<number, RowIssue>
  /** row indices whose rate is lower than a shorter tenor's (inverted curve — legal, worth a look) */
  nonMonotonic: number[]
  /** rows that will be sent */
  filled: number
}

/** Empty rows are ignored; a half-filled one is an error. */
export function validateGrid(rows: GridRow[]): GridValidation {
  const errors: Record<number, RowIssue> = {}
  const seen = new Map<string, number>()
  const points: { i: number; days: number; rate: number }[] = []
  rows.forEach((r, i) => {
    const tenor = r.tenor.trim().toUpperCase()
    const rateText = r.rate.trim()
    if (!tenor && !rateText) return
    if (!TENOR.test(tenor)) { errors[i] = 'tenor'; return }
    const days = tenorDays(tenor)
    if (days === null) { errors[i] = 'tenor-range'; return }
    const rate = parseRate(rateText)
    if (rate === null) { errors[i] = 'rate'; return }
    if (rate < -5 || rate > 50) { errors[i] = 'rate-range'; return }
    const key = canonicalTenor(tenor)
    if (seen.has(key)) { errors[i] = 'duplicate'; return }
    seen.set(key, i)
    points.push({ i, days, rate })
  })
  points.sort((a, b) => a.days - b.days)
  const nonMonotonic: number[] = []
  for (let k = 1; k < points.length; k++) if (points[k].rate < points[k - 1].rate) nonMonotonic.push(points[k].i)
  return { errors, nonMonotonic, filled: points.length }
}

/** Sorted (days, %) points of a grid's valid rows, for the preview chart. */
export function previewPoints(rows: GridRow[]): { tenor: string; days: number; rate: number }[] {
  const v = validateGrid(rows)
  return rows
    .map((r, i) => ({ r, i }))
    .filter(({ r, i }) => !(i in v.errors) && r.tenor.trim() && r.rate.trim())
    .map(({ r }) => ({ tenor: r.tenor.trim().toUpperCase(), days: tenorDays(r.tenor) as number, rate: parseRate(r.rate) as number }))
    .sort((a, b) => a.days - b.days)
}

export type Provenance = 'synthetic' | 'production'

export interface SourceOption { id: string; cs: string; en: string; wire: string; indices?: CurveIndexName[]; sandboxOnly?: boolean }

/** `wire` is what risk-engine stores in `source` (free text, ≤256 chars). */
export const SOURCE_OPTIONS: readonly SourceOption[] = [
  { id: 'cnb-czeonia', wire: 'ČNB – fixing CZEONIA', indices: ['CZEONIA'], cs: 'Oficiální denní fixing CZEONIA zveřejněný ČNB (www.cnb.cz).', en: 'Official daily CZEONIA fixing published by the CNB.' },
  { id: 'cba-pribor', wire: 'ČBA – fixing PRIBOR', indices: ['PRIBOR_1M', 'PRIBOR_3M', 'PRIBOR_6M'], cs: 'Denní fixing PRIBOR (administrátor Česká bankovní asociace).', en: 'Daily PRIBOR fixing (administered by the Czech Banking Association).' },
  { id: 'ecb-estr', wire: 'ECB – €STR', indices: ['ESTR'], cs: 'Denní sazba €STR zveřejněná ECB.', en: 'Daily €STR published by the ECB.' },
  { id: 'emmi-euribor', wire: 'EMMI – EURIBOR', indices: ['EURIBOR_3M'], cs: 'Denní fixing EURIBOR (administrátor EMMI).', en: 'Daily EURIBOR fixing (administered by EMMI).' },
  { id: 'terminal', wire: 'Bloomberg/Refinitiv (ruční přepis)', cs: 'Kotace opsané z tržního terminálu. Uveďte v poznámce obrazovku, pokud je to potřeba.', en: 'Quotes copied by hand from a market terminal.' },
  { id: 'treasury-estimate', wire: 'Interní odhad treasury', cs: 'Odhad útvaru treasury, ne tržní fixing — např. když fixing k datu chybí.', en: "Treasury's own estimate, not a market fixing." },
  { id: 'sandbox-sample', wire: 'Ukázková syntetická data (sandbox)', sandboxOnly: true, cs: 'Vymyšlené hodnoty pro vyzkoušení v sandboxu. Nikdy nejde o tržní data.', en: 'Made-up values for trying the sandbox. Never market data.' },
  { id: 'other', wire: '', cs: 'Vlastní popis zdroje.', en: 'Describe the source yourself.' },
]

export const PROVENANCE_HELP: Record<Provenance, { cs: string; en: string }> = {
  production: {
    cs: 'Skutečné tržní kotace k uvedenému datu (oficiální fixing nebo terminál). Výpočty nad touto sadou jsou podkladem pro řízení rizik.',
    en: 'Real market quotes as of the date (official fixing or terminal). Calculations on this set feed risk management.',
  },
  synthetic: {
    cs: 'Vymyšlená nebo odhadnutá data pro test a ukázku. Každý výpočet nad sadou ponese štítek „syntetická“ a nesmí do reportingu.',
    en: 'Made-up or estimated data for testing and demos. Every result carries the "synthetic" badge and must not be reported.',
  },
}

/** Mirrors the regulatory page: only an explicit `production` build hides sandbox helpers. */
export const isSandboxEnvironment = (env: string | undefined) => (env?.trim() || 'unknown').toLowerCase() !== 'production'

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
export const todayIso = (now = new Date()) => iso(now)

/** The last weekday strictly before [now] (holidays are not modelled — the operator can change it). */
export function lastBusinessDay(now = new Date()): string {
  const d = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  do d.setDate(d.getDate() - 1); while (d.getDay() === 0 || d.getDay() === 6)
  return iso(d)
}

/** Plausible, clearly SYNTHETIC curves for the sandbox. Never offered in production. */
export const SAMPLE_CURVES: Partial<Record<CurveIndexName, GridRow[]>> = {
  CZEONIA: [['ON', '3,50'], ['1W', '3,51'], ['1M', '3,54'], ['3M', '3,58'], ['6M', '3,62'], ['1Y', '3,70']].map(([tenor, rate]) => ({ tenor, rate })),
  ESTR: [['ON', '1,92'], ['1W', '1,92'], ['1M', '1,94'], ['3M', '1,98'], ['6M', '2,03'], ['1Y', '2,10']].map(([tenor, rate]) => ({ tenor, rate })),
}

export interface FormState {
  asOf: string
  provenance: Provenance | ''
  sourceId: string
  sourceOther: string
  curves: Partial<Record<CurveIndexName, GridRow[]>>
}

/** The sandbox "fill sample" action: synthetic provenance is forced, never left to the operator. */
export function applySample(state: FormState): FormState {
  return { ...state, provenance: 'synthetic', sourceId: 'sandbox-sample', sourceOther: '', curves: structuredClone(SAMPLE_CURVES) }
}

export const sourceWire = (s: Pick<FormState, 'sourceId' | 'sourceOther'>) =>
  s.sourceId === 'other' ? s.sourceOther.trim() : (SOURCE_OPTIONS.find(o => o.id === s.sourceId)?.wire ?? '')

export type FormProblem = 'asOf' | 'asOf-future' | 'provenance' | 'source' | 'sample-production' | 'no-curve' | 'rows'

/** Everything that blocks the upload, in display order. */
export function formProblems(s: FormState, today = todayIso()): FormProblem[] {
  const p: FormProblem[] = []
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s.asOf)) p.push('asOf')
  else if (s.asOf > today) p.push('asOf-future')
  if (!s.provenance) p.push('provenance')
  const src = sourceWire(s)
  if (!src || src.length > 256) p.push('source')
  if (s.sourceId === 'sandbox-sample' && s.provenance === 'production') p.push('sample-production')
  const grids = Object.values(s.curves).filter((r): r is GridRow[] => !!r)
  const results = grids.map(validateGrid)
  if (!results.some(v => v.filled > 0)) p.push('no-curve')
  if (results.some(v => Object.keys(v.errors).length > 0)) p.push('rows')
  return p
}

/** The unchanged POST body: rates in percent → decimal simple rate (3.5 → 0.035). */
export function toPayload(s: FormState) {
  const curves: Record<string, { tenor: string; rate: number }[]> = {}
  for (const [index, rows] of Object.entries(s.curves)) {
    if (!rows) continue
    const quotes = rows
      .filter(r => r.tenor.trim() && r.rate.trim())
      .map(r => ({ tenor: r.tenor.trim().toUpperCase(), rate: Math.round((parseRate(r.rate) as number) * 1e6) / 1e8 }))
    if (quotes.length) curves[index] = quotes
  }
  return { asOf: s.asOf, provenance: s.provenance, source: sourceWire(s), curves }
}

export function summary(s: FormState): { curves: number; points: number } {
  const counts = Object.values(s.curves).map(r => (r ? validateGrid(r).filled : 0)).filter(n => n > 0)
  return { curves: counts.length, points: counts.reduce((a, b) => a + b, 0) }
}

/** risk-engine's 400 texts (CurveSetResource, Tenor.parse, CurveBootstrap) rendered for a person. */
export function explainServerError(message: string | null, lang: 'cs' | 'en'): string {
  const m = message ?? ''
  const rules: [RegExp, (x: RegExpExecArray) => [string, string]][] = [
    [/tenor '([^']+)'.*exceeds one year/, x => [`Splatnost ${x[1]} je delší než 1 rok — risk-engine zatím přijímá jen peněžní trh do 1Y.`, `Tenor ${x[1]} is longer than one year — risk-engine accepts money-market tenors up to 1Y only.`]],
    [/tenor '([^']+)' is not ON/, x => [`Splatnost „${x[1]}“ není platná (ON, 1W, 3M, 1Y…).`, `Tenor "${x[1]}" is not valid (ON, 1W, 3M, 1Y…).`]],
    [/tenor '([^']+)': use W\/M/, x => [`Splatnost ${x[1]}: delší než týden zadejte v týdnech nebo měsících.`, `Tenor ${x[1]}: use weeks or months beyond a week.`]],
    [/curve (\w+): two quotes end on (\S+)/, x => [`Křivka ${x[1]}: dvě splatnosti končí stejný den (${x[2]}), např. 1Y a 12M. Ponechte jen jednu.`, `Curve ${x[1]}: two tenors end on ${x[2]} (e.g. 1Y and 12M). Keep one.`]],
    [/rate (\S+) is not a fraction/, () => ['Sazba je mimo rozsah — zadávejte v procentech, např. 3,5.', 'Rate out of range — enter percent, e.g. 3.5.']],
    [/implies a negative DF/, () => ['Sazba je tak záporná, že z ní nelze spočítat diskontní faktor.', 'A rate is so negative no discount factor exists.']],
    [/unknown curve index '([^']+)'/, x => [`Index ${x[1]} risk-engine nezná.`, `risk-engine does not know index ${x[1]}.`]],
    [/'asOf'/, () => ['Datum „K datu“ chybí nebo není platné.', 'The as-of date is missing or invalid.']],
    [/'source'/, () => ['Zdroj kotací chybí nebo je delší než 256 znaků.', 'The quote source is missing or longer than 256 characters.']],
    [/'provenance'/, () => ['Vyberte původ dat.', 'Choose a provenance.']],
  ]
  for (const [re, f] of rules) {
    const x = re.exec(m)
    if (x) return f(x)[lang === 'cs' ? 0 : 1]
  }
  return lang === 'cs' ? `risk-engine sadu odmítl${m ? `: ${m}` : '.'}` : `risk-engine refused the set${m ? `: ${m}` : '.'}`
}
