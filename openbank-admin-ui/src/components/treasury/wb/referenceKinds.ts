// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The reference-data kind catalogue (ADR-0344 D3, contract 8.6): every effective-dated code list
// the treasury service keeps, with the field schema its editor renders. One generic editor is
// driven from this table; adding a kind is adding a row. A kind without a published payload schema
// gets code / name / description plus an "advanced JSON" escape hatch, so no list is unreachable.
type T = (cs: string, en: string) => string

export type FieldType = 'text' | 'number' | 'integer' | 'boolean' | 'date' | 'select' | 'list' | 'dates' | 'map' | 'buckets'
export type FieldSpec = {
  name: string
  cs: string
  en: string
  type: FieldType
  required?: boolean
  options?: readonly string[]
  /** Hint shown under the control. */
  hint?: [string, string]
}

export type KindSpec = {
  kind: string
  cs: string
  en: string
  group: 'parties' | 'market' | 'instruments' | 'accounts' | 'risk' | 'regulatory' | 'config'
  /** How the record key is derived from the payload. */
  key: (p: Record<string, unknown>) => string
  /** Payload fields that make up the key (shown first, required). */
  keyFields: string[]
  fields: FieldSpec[]
  /** True when the service publishes a payload schema for the kind (otherwise generic + JSON). */
  published: boolean
}

const str = (p: Record<string, unknown>, k: string) => (p[k] === null || p[k] === undefined ? '' : String(p[k]))
const byField = (name: string) => (p: Record<string, unknown>) => str(p, name)
const compound = (a: string, b: string) => (p: Record<string, unknown>) => `${str(p, a)}:${str(p, b)}`

const dayCounts = ['ACT/360', 'ACT/365F', 'ACT/ACT_ISDA', '30/360', '30E/360'] as const
const frequencies = ['ONCE', 'DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL', 'ANNUAL'] as const
const bdcs = ['UNADJUSTED', 'FOLLOWING', 'MODIFIED_FOLLOWING', 'PRECEDING', 'MODIFIED_PRECEDING'] as const

const code: FieldSpec = { name: 'code', cs: 'Kód', en: 'Code', type: 'text', required: true }
const id: FieldSpec = { name: 'id', cs: 'Identifikátor', en: 'Identifier', type: 'text', required: true }
const name: FieldSpec = { name: 'name', cs: 'Název', en: 'Name', type: 'text', required: true }
const description: FieldSpec = { name: 'description', cs: 'Popis', en: 'Description', type: 'text' }

function generic(kind: string, cs: string, en: string, group: KindSpec['group'], keyName: 'code' | 'id' = 'code', extra: FieldSpec[] = []): KindSpec {
  return { kind, cs, en, group, key: byField(keyName), keyFields: [keyName], fields: [keyName === 'code' ? code : id, name, description, ...extra], published: false }
}

export const KINDS: KindSpec[] = [
  {
    kind: 'calendar', cs: 'Kalendáře svátků', en: 'Holiday calendars', group: 'market', key: byField('code'), keyFields: ['code'], published: true,
    fields: [
      code,
      { name: 'baseCalendar', cs: 'Základní kalendář', en: 'Base calendar', type: 'select', required: true, options: ['CERTIS', 'TARGET2', 'WEEKEND_ONLY'] },
      { name: 'extraHolidays', cs: 'Další svátky', en: 'Extra holidays', type: 'dates', hint: ['RRRR-MM-DD, oddělené čárkou', 'YYYY-MM-DD, comma separated'] },
      { name: 'removedHolidays', cs: 'Zrušené svátky', en: 'Removed holidays', type: 'dates' },
    ],
  },
  {
    kind: 'index', cs: 'Referenční indexy', en: 'Reference indices', group: 'market', key: byField('code'), keyFields: ['code'], published: true,
    fields: [
      code,
      { name: 'currency', cs: 'Měna', en: 'Currency', type: 'text', required: true },
      { name: 'tenor', cs: 'Splatnost (tenor)', en: 'Tenor', type: 'text', required: true },
      { name: 'dayCount', cs: 'Konvence dní', en: 'Day count', type: 'select', required: true, options: dayCounts },
      { name: 'fixingLagBusinessDays', cs: 'Předstih fixingu (pracovní dny)', en: 'Fixing lag (business days)', type: 'integer', required: true },
      { name: 'fixingCalendar', cs: 'Kalendář fixingu', en: 'Fixing calendar', type: 'text', required: true },
      { name: 'source', cs: 'Zdroj', en: 'Source', type: 'select', required: true, options: ['CNB', 'ECB', 'MANUAL', 'FEED'] },
      { name: 'staleAfterBusinessDays', cs: 'Neaktuální po (pracovní dny)', en: 'Stale after (business days)', type: 'integer' },
      { name: 'suspiciousJumpBp', cs: 'Podezřelý skok (bp)', en: 'Suspicious jump (bp)', type: 'integer' },
    ],
  },
  {
    kind: 'counterparty', cs: 'Protistrany', en: 'Counterparties', group: 'parties', key: byField('id'), keyFields: ['id'], published: true,
    fields: [
      id, name,
      { name: 'kind', cs: 'Druh', en: 'Kind', type: 'select', required: true, options: ['BANK', 'CENTRAL_BANK', 'CORPORATE', 'ISSUER'] },
      { name: 'lei', cs: 'LEI', en: 'LEI', type: 'text' },
      { name: 'countryCode', cs: 'Země (kód)', en: 'Country code', type: 'text', required: true },
      { name: 'limits', cs: 'Limity podle měny', en: 'Limits by currency', type: 'map', hint: ['jeden řádek: MĚNA=částka', 'one line each: CCY=amount'] },
      { name: 'nettingAgreementId', cs: 'Dohoda o nettingu', en: 'Netting agreement', type: 'text' },
    ],
  },
  {
    kind: 'ssi', cs: 'Pokyny k vypořádání (SSI)', en: 'Standard settlement instructions', group: 'parties', key: compound('counterpartyId', 'currency'), keyFields: ['counterpartyId', 'currency'], published: true,
    fields: [
      { name: 'counterpartyId', cs: 'Protistrana', en: 'Counterparty', type: 'text', required: true },
      { name: 'currency', cs: 'Měna', en: 'Currency', type: 'text', required: true },
      { name: 'rail', cs: 'Platební systém', en: 'Rail', type: 'select', required: true, options: ['CERTIS', 'SEPA', 'TARGET2', 'SWIFT'] },
      { name: 'beneficiaryAccount', cs: 'Účet příjemce (IBAN)', en: 'Beneficiary account (IBAN)', type: 'text', required: true },
      { name: 'bic', cs: 'BIC', en: 'BIC', type: 'text' },
      { name: 'intermediaryBic', cs: 'BIC zprostředkovatele', en: 'Intermediary BIC', type: 'text' },
    ],
  },
  {
    kind: 'netting', cs: 'Dohody o nettingu', en: 'Netting agreements', group: 'parties', key: byField('id'), keyFields: ['id'], published: true,
    fields: [
      id,
      { name: 'counterpartyId', cs: 'Protistrana', en: 'Counterparty', type: 'text', required: true },
      { name: 'currencies', cs: 'Měny (čárkou)', en: 'Currencies (comma separated)', type: 'list', required: true },
      { name: 'paymentNetting', cs: 'Netting plateb', en: 'Payment netting', type: 'boolean' },
    ],
  },
  {
    kind: 'security', cs: 'Cenné papíry (emise)', en: 'Securities master', group: 'instruments', key: byField('isin'), keyFields: ['isin'], published: true,
    fields: [
      { name: 'isin', cs: 'ISIN', en: 'ISIN', type: 'text', required: true },
      name,
      { name: 'issuerId', cs: 'Emitent', en: 'Issuer', type: 'text', required: true },
      { name: 'type', cs: 'Typ', en: 'Type', type: 'select', required: true, options: ['BOND', 'TREASURY_BILL', 'BILL_OF_EXCHANGE'] },
      { name: 'currency', cs: 'Měna', en: 'Currency', type: 'text', required: true },
      { name: 'issueDate', cs: 'Datum emise', en: 'Issue date', type: 'date', required: true },
      { name: 'maturityDate', cs: 'Datum splatnosti', en: 'Maturity date', type: 'date', required: true },
      { name: 'denomination', cs: 'Nominální hodnota', en: 'Denomination', type: 'number', required: true },
      { name: 'couponRate', cs: 'Kupón % p.a.', en: 'Coupon rate % p.a.', type: 'number' },
      { name: 'couponFrequency', cs: 'Frekvence kupónu', en: 'Coupon frequency', type: 'select', options: frequencies },
      { name: 'dayCount', cs: 'Konvence dní', en: 'Day count', type: 'select', required: true, options: dayCounts },
      { name: 'businessDayConvention', cs: 'Konvence pracovních dnů', en: 'Business-day convention', type: 'select', required: true, options: bdcs },
      { name: 'calendar', cs: 'Kalendář', en: 'Calendar', type: 'text', required: true },
    ],
  },
  {
    kind: 'book', cs: 'Portfolia (knihy)', en: 'Portfolios (books)', group: 'instruments', key: byField('id'), keyFields: ['id'], published: true,
    fields: [
      id,
      { name: 'parentId', cs: 'Nadřazené portfolio', en: 'Parent portfolio', type: 'text' },
      name,
      { name: 'purpose', cs: 'Účel', en: 'Purpose', type: 'select', required: true, options: ['BANKING_BOOK', 'TRADING_BOOK'] },
      { name: 'ifrs9Category', cs: 'Kategorie IFRS 9', en: 'IFRS 9 category', type: 'select', required: true, options: ['AMORTISED_COST', 'FVOCI', 'FVTPL'] },
    ],
  },
  {
    kind: 'bucket-set', cs: 'Skupiny časových pásem', en: 'Time-bucket groups', group: 'risk', key: byField('id'), keyFields: ['id'], published: true,
    fields: [id, name, { name: 'buckets', cs: 'Pásma', en: 'Buckets', type: 'buckets', required: true, hint: ['jeden řádek: popisek;od dní;do dní', 'one line each: label;from days;to days'] }],
  },
  {
    kind: 'regulatory-code', cs: 'Regulatorní kódy', en: 'Regulatory codes', group: 'regulatory', key: compound('list', 'code'), keyFields: ['list', 'code'], published: true,
    fields: [
      { name: 'list', cs: 'Seznam', en: 'List', type: 'select', required: true, options: ['CNB', 'DISIFE23', 'EMIR_PRODUCT', 'LCR', 'NSFR', 'TRADE_REPORTING', 'CLASSIFICATION'] },
      code,
      { name: 'label', cs: 'Popis', en: 'Label', type: 'text', required: true },
    ],
  },
  generic('party-group', 'Skupiny stran', 'Party groups', 'parties'),
  generic('party-type', 'Typy stran', 'Party types', 'parties'),
  generic('country', 'Země', 'Countries', 'parties'),
  generic('document-type', 'Typy dokumentů', 'Document types', 'config'),
  generic('account', 'Účty (peněžní, cenných papírů, nostro)', 'Accounts (cash, securities, nostro)', 'accounts', 'id', [
    { name: 'accountType', cs: 'Typ účtu', en: 'Account type', type: 'select', options: ['CASH', 'SECURITIES', 'NOSTRO'] },
    { name: 'currency', cs: 'Měna', en: 'Currency', type: 'text' },
    { name: 'glCode', cs: 'Účet hlavní knihy', en: 'GL account', type: 'text' },
  ]),
  generic('settlement-place', 'Místa vypořádání', 'Settlement places', 'accounts'),
  {
    kind: 'product-type', cs: 'Typy produktů', en: 'Product types', group: 'config', key: byField('code'), keyFields: ['code'], published: true,
    fields: [
      code,
      { name: 'permitted', cs: 'Povolen', en: 'Permitted', type: 'boolean' },
      { name: 'allowedCurrencies', cs: 'Povolené měny (čárkou)', en: 'Allowed currencies (comma separated)', type: 'list' },
      { name: 'maxPrincipal', cs: 'Maximální jistina podle měny', en: 'Maximum principal by currency', type: 'map' },
      { name: 'maxTenorDays', cs: 'Maximální doba (dny)', en: 'Maximum tenor (days)', type: 'integer' },
    ],
  },
  generic('loan-type', 'Typy úvěrů', 'Loan types', 'config'),
  generic('communication-channel', 'Komunikační kanály', 'Communication channels', 'config'),
  generic('market-factor', 'Tržní faktory', 'Market factors', 'market', 'code', [
    { name: 'factorType', cs: 'Druh faktoru', en: 'Factor type', type: 'select', options: ['RATE', 'FX', 'CURVE'] },
    { name: 'currency', cs: 'Měna', en: 'Currency', type: 'text' },
  ]),
  generic('own-capital-item', 'Položky vlastního kapitálu', 'Own capital items', 'risk'),
  generic('period-length', 'Délky období (tenory)', 'Period lengths (tenors)', 'config', 'code', [{ name: 'days', cs: 'Dny', en: 'Days', type: 'integer' }]),
  generic('scenario', 'Scénáře', 'Scenarios', 'risk'),
  generic('behavioural-scenario', 'Behaviorální scénáře', 'Behavioural scenarios', 'risk', 'code', [{ name: 'riskEngineRef', cs: 'Odkaz do risk-engine', en: 'Risk-engine reference', type: 'text' }]),
  generic('calculation-settings', 'Nastavení výpočtů', 'Calculation settings', 'config', 'code', [
    { name: 'roundingScale', cs: 'Zaokrouhlení (desetinná místa)', en: 'Rounding (decimal places)', type: 'integer' },
    { name: 'defaultDayCount', cs: 'Výchozí konvence dní', en: 'Default day count', type: 'select', options: dayCounts },
  ]),
  generic('limit-settings', 'Nastavení limitů', 'Limit settings', 'risk'),
  generic('derivative-conversion-factor', 'Konverzní faktory derivátů', 'Derivative conversion factors', 'risk', 'code', [
    { name: 'product', cs: 'Produkt', en: 'Product', type: 'text' }, { name: 'tenor', cs: 'Tenor', en: 'Tenor', type: 'text' }, { name: 'factor', cs: 'Faktor', en: 'Factor', type: 'number' },
  ]),
  generic('collateral-haircut', 'Haircuty zajištění', 'Collateral haircuts', 'risk', 'code', [
    { name: 'securityType', cs: 'Typ cenného papíru', en: 'Security type', type: 'text' }, { name: 'rating', cs: 'Rating', en: 'Rating', type: 'text' }, { name: 'haircutPercent', cs: 'Haircut %', en: 'Haircut %', type: 'number' },
  ]),
  generic('portfolio-group', 'Skupiny portfolií', 'Portfolio groups', 'instruments'),
  generic('accounting-settings', 'Účetní nastavení (pravidla zaúčtování, mapování GL)', 'Accounting settings (posting rules, GL mapping)', 'accounts'),
  generic('balance-sheet-side', 'Strany rozvahy', 'Balance-sheet sides', 'accounts'),
  generic('dealing-filter', 'Filtry obchodního přehledu', 'Dealing-view filters', 'config'),
  generic('internal-rate-sheet', 'Interní sazebníky', 'Internal rate sheets', 'market'),
]

export const KIND_BY_ID: Record<string, KindSpec> = Object.fromEntries(KINDS.map(k => [k.kind, k]))

export const KIND_GROUPS: { id: KindSpec['group']; cs: string; en: string }[] = [
  { id: 'parties', cs: 'Strany a vypořádání', en: 'Parties and settlement' },
  { id: 'market', cs: 'Trh', en: 'Market' },
  { id: 'instruments', cs: 'Nástroje a portfolia', en: 'Instruments and portfolios' },
  { id: 'accounts', cs: 'Účty a účetnictví', en: 'Accounts and accounting' },
  { id: 'risk', cs: 'Riziko a limity', en: 'Risk and limits' },
  { id: 'regulatory', cs: 'Regulatorní', en: 'Regulatory' },
  { id: 'config', cs: 'Konfigurace', en: 'Configuration' },
]

export const kindName = (k: KindSpec, t: T) => t(k.cs, k.en)

// ── form ↔ payload ──────────────────────────────────────────────────────────────────────────────
export type FieldValues = Record<string, string | boolean>

const csvList = (s: string) => s.split(',').map(x => x.trim()).filter(Boolean)

/** Payload → the strings the editor shows. */
export function toFieldValues(spec: KindSpec, payload: Record<string, unknown>): FieldValues {
  const out: FieldValues = {}
  for (const f of spec.fields) {
    const v = payload[f.name]
    switch (f.type) {
      case 'boolean': out[f.name] = v === true; break
      case 'list':
      case 'dates': out[f.name] = Array.isArray(v) ? v.join(', ') : ''; break
      case 'map': out[f.name] = v && typeof v === 'object' ? Object.entries(v as Record<string, unknown>).map(([k, x]) => `${k}=${String(x)}`).join('\n') : ''; break
      case 'buckets': out[f.name] = Array.isArray(v) ? v.map(b => { const o = b as Record<string, unknown>; return `${String(o.label ?? '')};${String(o.fromDays ?? '')};${o.toDays == null ? '' : String(o.toDays)}` }).join('\n') : ''; break
      default: out[f.name] = v === null || v === undefined ? '' : String(v)
    }
  }
  return out
}

export type PayloadResult = { payload: Record<string, unknown>; errors: Record<string, string> }

/** Editor strings → payload, with per-field errors (required, number, date, line formats). */
export function toPayload(spec: KindSpec, values: FieldValues, t: T): PayloadResult {
  const payload: Record<string, unknown> = {}
  const errors: Record<string, string> = {}
  const isoDate = /^\d{4}-\d{2}-\d{2}$/
  for (const f of spec.fields) {
    const raw = values[f.name]
    const s = typeof raw === 'string' ? raw.trim() : ''
    const missing = () => { errors[f.name] = t('Povinné pole.', 'Required field.') }
    switch (f.type) {
      case 'boolean': payload[f.name] = raw === true; break
      case 'number':
      case 'integer':
        if (s === '') { if (f.required) missing(); else payload[f.name] = null; break }
        if (!Number.isFinite(Number(s)) || (f.type === 'integer' && !Number.isInteger(Number(s)))) errors[f.name] = f.type === 'integer' ? t('Celé číslo.', 'A whole number.') : t('Neplatné číslo.', 'Invalid number.')
        else payload[f.name] = Number(s)
        break
      case 'date':
        if (s === '') { if (f.required) missing(); break }
        if (!isoDate.test(s)) errors[f.name] = t('Datum ve tvaru RRRR-MM-DD.', 'A date as YYYY-MM-DD.')
        else payload[f.name] = s
        break
      case 'list':
        if (s === '' && f.required) missing()
        payload[f.name] = csvList(s)
        break
      case 'dates': {
        const list = csvList(s)
        if (list.some(d => !isoDate.test(d))) errors[f.name] = t('Data ve tvaru RRRR-MM-DD.', 'Dates as YYYY-MM-DD.')
        payload[f.name] = list
        break
      }
      case 'map': {
        const entries = s.split('\n').map(l => l.trim()).filter(Boolean).map(l => l.split('='))
        if (entries.some(([k, v]) => !k?.trim() || v === undefined || !Number.isFinite(Number(v)))) errors[f.name] = t('Každý řádek: MĚNA=částka.', 'Each line: CCY=amount.')
        else payload[f.name] = Object.fromEntries(entries.map(([k, v]) => [k.trim(), Number(v)]))
        break
      }
      case 'buckets': {
        const rows = s.split('\n').map(l => l.trim()).filter(Boolean).map(l => l.split(';').map(x => x.trim()))
        if (rows.length === 0) { if (f.required) missing(); break }
        if (rows.some(r => !r[0] || !/^\d+$/.test(r[1] ?? '') || (r[2] ?? '') !== '' && !/^\d+$/.test(r[2]))) errors[f.name] = t('Každý řádek: popisek;od dní;do dní.', 'Each line: label;from days;to days.')
        else payload[f.name] = rows.map(r => ({ label: r[0], fromDays: Number(r[1]), toDays: (r[2] ?? '') === '' ? null : Number(r[2]) }))
        break
      }
      case 'select':
        if (s === '') { if (f.required) missing(); else payload[f.name] = null; break }
        payload[f.name] = s
        break
      default:
        if (s === '') { if (f.required) missing(); else payload[f.name] = null; break }
        payload[f.name] = s
    }
  }
  return { payload, errors }
}

/** The record key a payload implies, or '' while a key field is still empty. */
export function keyOf(spec: KindSpec, payload: Record<string, unknown>): string {
  if (spec.keyFields.some(k => str(payload, k) === '')) return ''
  return spec.key(payload)
}
