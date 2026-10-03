// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Guided curve-set upload (ADR-0313 D4, #11107): pick indices from explained cards, type or paste
// rates into a tenor grid per index, choose a named source and an explained provenance, see the
// curve before sending. The POST body is the same as the old free-text form produced.

'use client'

import { useMemo, useState, type ClipboardEvent } from 'react'
import { Plus, Trash2, Wand2 } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { CurveIndexName } from './model'
import {
  INDEX_INFO, PROVENANCE_HELP, SOURCE_OPTIONS, STANDARD_TENORS, applySample, formProblems, indexInfo,
  lastBusinessDay, parsePaste, previewPoints, summary, todayIso, validateGrid,
  type FormProblem, type FormState, type GridRow, type Provenance, type RowIssue,
} from './curveForm'

const emptyGrid = (): GridRow[] => STANDARD_TENORS.map(tenor => ({ tenor, rate: '' }))
const label = { display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 } as const
const hint = { fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 } as const

export function initialFormState(now = new Date()): FormState {
  return { asOf: lastBusinessDay(now), provenance: '', sourceId: '', sourceOther: '', curves: {} }
}

export function CurveUploadForm({ sandbox, busy, onSubmit }: {
  sandbox: boolean
  busy: boolean
  onSubmit: (state: FormState) => Promise<boolean>
}) {
  const { t, language } = useLanguage()
  const lang = language === 'cs' ? 'cs' : 'en'
  const [state, setState] = useState<FormState>(() => initialFormState())
  const [active, setActive] = useState<CurveIndexName | null>(null)
  const [pasteNote, setPasteNote] = useState<string | null>(null)
  const [touched, setTouched] = useState(false)
  const today = todayIso()

  const selected = INDEX_INFO.filter(i => state.curves[i.id]).map(i => i.id)
  const tab = active && state.curves[active] ? active : selected[0] ?? null
  const problems = formProblems(state, today)
  const sum = summary(state)
  const sources = SOURCE_OPTIONS.filter(o => sandbox || !o.sandboxOnly)

  const toggleIndex = (id: CurveIndexName) => {
    if (!state.curves[id]) setActive(id)
    setState(s => {
      const curves = { ...s.curves }
      if (curves[id]) delete curves[id]
      else curves[id] = emptyGrid()
      return { ...s, curves }
    })
  }
  const setRows = (id: CurveIndexName, rows: GridRow[]) => setState(s => ({ ...s, curves: { ...s.curves, [id]: rows } }))

  const onPaste = (id: CurveIndexName) => (e: ClipboardEvent<HTMLElement>) => {
    const text = e.clipboardData.getData('text')
    if (!/[\t\n;]/.test(text.trim())) return // a single value pastes into the cell normally
    e.preventDefault()
    const { rows, skipped } = parsePaste(text, id)
    setState(s => {
      const curves = { ...s.curves }
      for (const [idx, pasted] of Object.entries(rows) as [CurveIndexName, GridRow[]][]) {
        const base = (curves[idx] ?? []).filter(r => r.rate.trim() || !pasted.some(p => p.tenor === r.tenor.toUpperCase()))
        const merged = base.map(r => pasted.find(p => p.tenor === r.tenor.toUpperCase()) ?? r)
        for (const p of pasted) if (!merged.some(r => r.tenor.toUpperCase() === p.tenor)) merged.push(p)
        curves[idx] = merged
      }
      return { ...s, curves }
    })
    const n = Object.values(rows).reduce((a, r) => a + r.length, 0)
    setPasteNote(t(`Vloženo ${n} řádků${skipped ? `, ${skipped} přeskočeno (záhlaví nebo nečitelné)` : ''}.`,
      `Pasted ${n} rows${skipped ? `, ${skipped} skipped (header or unreadable)` : ''}.`))
  }

  const problemText = (p: FormProblem) => ({
    asOf: t('Vyplňte datum, ke kterému kotace platí.', 'Fill in the date the quotes are valid for.'),
    'asOf-future': t('Datum nesmí být v budoucnosti.', 'The date cannot be in the future.'),
    provenance: t('Vyberte původ dat.', 'Choose the provenance.'),
    source: t('Vyberte zdroj kotací (u „Jiné…“ ho popište).', 'Choose a quote source (describe it for "Other…").'),
    'sample-production': t('Ukázková data nelze označit jako produkční.', 'Sample data cannot be labelled production.'),
    'no-curve': t('Vyberte alespoň jeden index a vyplňte aspoň jednu sazbu.', 'Pick at least one index and fill in at least one rate.'),
    rows: t('Opravte červeně označené řádky.', 'Fix the rows marked in red.'),
  })[p]

  const submit = async () => {
    setTouched(true)
    if (problems.length) return
    if (await onSubmit(state)) { setState(s => ({ ...s, curves: {} })); setTouched(false); setPasteNote(null) }
  }

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
        <h2 style={{ fontSize: 14, fontWeight: 600 }}>{t('Nahrát sadu křivek', 'Upload a curve set')}</h2>
        {sandbox && (
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setState(s => applySample(s)); setActive('CZEONIA') }}>
            <Wand2 size={14} aria-hidden="true" /> {t('Vyplnit ukázkovými hodnotami', 'Fill with sample values')}
          </button>
        )}
      </div>

      <fieldset style={{ border: 0, padding: 0, margin: '0 0 16px' }}>
        <legend style={{ fontSize: 13, fontWeight: 600, marginBottom: 6 }}>{t('1. Které křivky nahráváte?', '1. Which curves are you uploading?')}</legend>
        {(['CZK', 'EUR'] as const).map(ccy => (
          <div key={ccy} style={{ marginBottom: 8 }}>
            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 4 }}>{ccy}</div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 8 }}>
              {INDEX_INFO.filter(i => i.currency === ccy).map(i => {
                const on = !!state.curves[i.id]
                return (
                  <button key={i.id} type="button" aria-pressed={on} onClick={() => toggleIndex(i.id)} className="card"
                    style={{ textAlign: 'left', cursor: 'pointer', padding: 10, borderColor: on ? 'var(--primary, #6366f1)' : undefined, borderWidth: on ? 2 : 1 }}>
                    <div style={{ fontWeight: 600, fontSize: 13 }}>{on ? '✓ ' : ''}{i.label}</div>
                    <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{lang === 'cs' ? i.cs : i.en}</div>
                  </button>
                )
              })}
            </div>
          </div>
        ))}
      </fieldset>

      {tab && (
        <fieldset style={{ border: 0, padding: 0, margin: '0 0 16px' }}>
          <legend style={{ fontSize: 13, fontWeight: 600, marginBottom: 6 }}>{t('2. Sazby podle splatnosti (v %)', '2. Rates by tenor (in %)')}</legend>
          <div role="tablist" aria-label={t('Křivky v sadě', 'Curves in the set')} style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 8 }}>
            {selected.map(id => {
              const v = validateGrid(state.curves[id] ?? [])
              const bad = Object.keys(v.errors).length > 0
              return (
                <button key={id} role="tab" type="button" aria-selected={tab === id} onClick={() => setActive(id)}
                  className={`btn btn-sm ${tab === id ? 'btn-primary' : 'btn-secondary'}`}>
                  {indexInfo(id)?.label} ({v.filled}){bad ? ' ⚠' : ''}
                </button>
              )
            })}
          </div>
          <div role="tabpanel" style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'flex-start' }}>
            <TenorGrid
              rows={state.curves[tab] ?? []}
              onChange={rows => setRows(tab, rows)}
              onPaste={onPaste(tab)}
              indexLabel={indexInfo(tab)?.label ?? tab}
            />
            <CurvePreview rows={state.curves[tab] ?? []} title={indexInfo(tab)?.label ?? tab} />
          </div>
          <p style={hint}>
            {t('Tip: zkopírujte dva sloupce (splatnost, sazba) z Excelu a vložte je do tabulky. Desetinná čárka i tečka jsou v pořádku. ',
              'Tip: copy two columns (tenor, rate) from Excel and paste them into the grid. Decimal comma or point both work. ')}
            {t('Risk-engine zatím přijímá splatnosti do 1 roku (ON, 1W…12M, 1Y).', 'risk-engine currently accepts tenors up to one year (ON, 1W…12M, 1Y).')}
          </p>
          {pasteNote && <p role="status" style={hint}>{pasteNote}</p>}
        </fieldset>
      )}

      <fieldset style={{ border: 0, padding: 0, margin: '0 0 12px' }}>
        <legend style={{ fontSize: 13, fontWeight: 600, marginBottom: 6 }}>{t('3. Odkud a k jakému datu', '3. Source and date')}</legend>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 12 }}>
          <label style={label}>
            {t('K datu', 'As of')}
            <input type="date" className="input" value={state.asOf} max={today}
              onChange={e => setState(s => ({ ...s, asOf: e.target.value }))} aria-label={t('Datum sady křivek', 'Curve set as-of date')} />
            <span style={hint}>{t('Den, ke kterému kotace platí. Předvyplněn poslední pracovní den.', 'The day the quotes are valid for. Defaults to the last business day.')}</span>
          </label>
          <label style={label}>
            {t('Zdroj kotací', 'Quote source')}
            <select className="input" value={state.sourceId} aria-label={t('Zdroj kotací', 'Quote source')}
              onChange={e => setState(s => ({ ...s, sourceId: e.target.value }))}>
              <option value="">{t('— vyberte —', '— choose —')}</option>
              {sources.map(o => (
                <option key={o.id} value={o.id}>{o.id === 'other' ? t('Jiné…', 'Other…') : o.wire}</option>
              ))}
            </select>
            {state.sourceId === 'other' && (
              <input className="input" maxLength={256} value={state.sourceOther} placeholder={t('např. Reuters, obrazovka CZKFIX=', 'e.g. Reuters, page CZKFIX=')}
                onChange={e => setState(s => ({ ...s, sourceOther: e.target.value }))} aria-label={t('Vlastní zdroj kotací', 'Custom quote source')} />
            )}
            <span style={hint}>
              {state.sourceId
                ? (lang === 'cs' ? sources.find(o => o.id === state.sourceId)?.cs : sources.find(o => o.id === state.sourceId)?.en)
                : t('Kde jste sazby vzali — uloží se k sadě jako auditní stopa.', 'Where the rates came from — stored with the set as an audit trail.')}
            </span>
          </label>
          <label style={label}>
            {t('Původ dat', 'Provenance')}
            <select className="input" value={state.provenance} aria-label={t('Původ dat sady', 'Curve set provenance')}
              onChange={e => setState(s => ({ ...s, provenance: e.target.value as Provenance | '' }))}>
              <option value="">{t('— vyberte —', '— choose —')}</option>
              <option value="production">{t('Produkční (tržní data)', 'Production (market data)')}</option>
              <option value="synthetic">{t('Syntetická (test / ukázka)', 'Synthetic (test / demo)')}</option>
            </select>
            <span style={hint}>
              {state.provenance
                ? PROVENANCE_HELP[state.provenance][lang]
                : t('Rozhoduje, zda výsledky smí do reportingu. Nemá výchozí hodnotu záměrně.', 'Decides whether results may be reported. Deliberately has no default.')}
            </span>
          </label>
        </div>
      </fieldset>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <button type="button" className="btn btn-primary btn-sm" disabled={busy || (touched && problems.length > 0)} onClick={() => void submit()}>
          {busy ? t('Nahrávám…', 'Uploading…') : t('Nahrát sadu', 'Upload set')}
        </button>
        <span style={{ fontSize: 13 }} aria-live="polite">
          {sum.curves > 0
            ? t(`Nahrajete ${sum.curves} ${sum.curves === 1 ? 'křivku' : sum.curves < 5 ? 'křivky' : 'křivek'}, ${sum.points} ${sum.points === 1 ? 'bod' : sum.points < 5 ? 'body' : 'bodů'}.`,
              `You will upload ${sum.curves} curve${sum.curves === 1 ? '' : 's'}, ${sum.points} point${sum.points === 1 ? '' : 's'}.`)
            : t('Zatím žádná vyplněná křivka.', 'No curve filled in yet.')}
          {state.provenance === 'synthetic' && sum.curves > 0 && <strong> {t('Označeno jako SYNTETICKÁ data.', 'Labelled SYNTHETIC.')}</strong>}
        </span>
      </div>
      {touched && problems.length > 0 && (
        <ul role="alert" style={{ fontSize: 12, color: 'var(--danger-text)', marginTop: 8 }}>
          {problems.map(p => <li key={p}>{problemText(p)}</li>)}
        </ul>
      )}
    </div>
  )
}

function TenorGrid({ rows, onChange, onPaste, indexLabel }: {
  rows: GridRow[]
  onChange: (rows: GridRow[]) => void
  onPaste: (e: ClipboardEvent<HTMLElement>) => void
  indexLabel: string
}) {
  const { t } = useLanguage()
  const v = useMemo(() => validateGrid(rows), [rows])
  const issue = (c: RowIssue) => ({
    tenor: t('neplatná splatnost (ON, 1W, 3M, 1Y…)', 'invalid tenor (ON, 1W, 3M, 1Y…)'),
    'tenor-range': t('max. 7D, 52W, 12M nebo 1Y', 'max 7D, 52W, 12M or 1Y'),
    rate: t('sazba musí být číslo, např. 3,5', 'rate must be a number, e.g. 3.5'),
    'rate-range': t('sazba mimo rozsah −5 % až 50 %', 'rate outside −5 % to 50 %'),
    duplicate: t('splatnost už v tabulce je (1Y = 12M)', 'tenor already listed (1Y = 12M)'),
  })[c]
  const set = (i: number, patch: Partial<GridRow>) => onChange(rows.map((r, k) => (k === i ? { ...r, ...patch } : r)))
  return (
    <div onPaste={onPaste} style={{ flex: '1 1 320px' }}>
      <table style={{ borderCollapse: 'collapse', fontSize: 13, width: '100%' }} aria-label={t(`Sazby ${indexLabel}`, `${indexLabel} rates`)}>
        <thead>
          <tr>
            <th scope="col" style={{ textAlign: 'left' }}>{t('Splatnost', 'Tenor')}</th>
            <th scope="col" style={{ textAlign: 'left' }}>{t('Sazba %', 'Rate %')}</th>
            <th scope="col"><span className="sr-only">{t('Akce', 'Actions')}</span></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const err = v.errors[i]
            const warn = v.nonMonotonic.includes(i)
            return (
              <tr key={i}>
                <td style={{ padding: '2px 4px' }}>
                  <input className="input" value={r.tenor} size={5} aria-label={t(`Splatnost řádek ${i + 1}`, `Tenor row ${i + 1}`)}
                    onChange={e => set(i, { tenor: e.target.value.toUpperCase() })} />
                </td>
                <td style={{ padding: '2px 4px' }}>
                  <input className="input" inputMode="decimal" value={r.rate} size={7} placeholder="3,50"
                    aria-label={t(`Sazba ${indexLabel} ${r.tenor || i + 1} v %`, `${indexLabel} ${r.tenor || i + 1} rate in %`)}
                    aria-invalid={!!err} style={err ? { borderColor: 'var(--danger)' } : undefined}
                    onChange={e => set(i, { rate: e.target.value })} />
                  {err && <div style={{ fontSize: 11, color: 'var(--danger-text)' }}>{issue(err)}</div>}
                  {!err && warn && <div style={{ fontSize: 11, color: 'var(--warning-text, #b45309)' }}>{t('nižší než kratší splatnost — zkontrolujte', 'lower than a shorter tenor — please check')}</div>}
                </td>
                <td style={{ padding: '2px 4px' }}>
                  <button type="button" className="btn btn-secondary btn-sm" aria-label={t(`Odebrat řádek ${r.tenor || i + 1}`, `Remove row ${r.tenor || i + 1}`)}
                    onClick={() => onChange(rows.filter((_, k) => k !== i))}>
                    <Trash2 size={12} aria-hidden="true" />
                  </button>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 6 }} onClick={() => onChange([...rows, { tenor: '', rate: '' }])}>
        <Plus size={12} aria-hidden="true" /> {t('Přidat splatnost', 'Add tenor')}
      </button>
    </div>
  )
}

/**
 * The quotes as entered (simple money-market rates). Bootstrapping to zero rates happens in
 * risk-engine; for ≤1Y the two differ by basis points, so this is the shape check, not the curve.
 */
function CurvePreview({ rows, title }: { rows: GridRow[]; title: string }) {
  const { t } = useLanguage()
  const pts = previewPoints(rows)
  const W = 320, H = 180, P = 32
  if (pts.length === 0) {
    return <div style={{ flex: '1 1 260px', fontSize: 12, color: 'var(--text-secondary)' }}>{t('Náhled křivky se zobrazí po vyplnění sazeb.', 'The curve preview appears once rates are filled in.')}</div>
  }
  const xs = pts.map(p => Math.log(p.days + 1))
  const x0 = Math.log(2), x1 = Math.max(Math.log(366.25 + 1), ...xs)
  const lo = Math.min(...pts.map(p => p.rate)), hi = Math.max(...pts.map(p => p.rate))
  const pad = Math.max((hi - lo) * 0.15, 0.05)
  const y = (r: number) => H - P - ((r - (lo - pad)) / (hi - lo + 2 * pad)) * (H - 2 * P)
  const x = (v: number) => P + ((v - x0) / (x1 - x0)) * (W - 2 * P)
  const path = pts.map((p, i) => `${i ? 'L' : 'M'}${x(xs[i]).toFixed(1)},${y(p.rate).toFixed(1)}`).join(' ')
  const fmt = (n: number) => n.toLocaleString('cs-CZ', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return (
    <figure style={{ flex: '1 1 260px', margin: 0 }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label={t(`Náhled kotací ${title}`, `${title} quotes preview`)}>
        <line x1={P} y1={H - P} x2={W - P} y2={H - P} stroke="#94a3b8" />
        <line x1={P} y1={P} x2={P} y2={H - P} stroke="#94a3b8" />
        <text x={P - 4} y={y(hi) + 4} fontSize="10" textAnchor="end" fill="#64748b">{fmt(hi)}</text>
        <text x={P - 4} y={y(lo) + 4} fontSize="10" textAnchor="end" fill="#64748b">{fmt(lo)}</text>
        <path d={path} fill="none" stroke="#6366f1" strokeWidth={2} />
        {pts.map((p, i) => (
          <g key={p.tenor}>
            <circle cx={x(xs[i])} cy={y(p.rate)} r={3} fill="#6366f1" />
            <text x={x(xs[i])} y={H - P + 14} fontSize="10" textAnchor="middle" fill="#64748b">{p.tenor}</text>
          </g>
        ))}
      </svg>
      <figcaption style={{ fontSize: 11, color: 'var(--text-secondary)' }}>
        {t('Zadané kotace v % (převod na nulovou křivku provede risk-engine).', 'Quotes as entered, in % (risk-engine bootstraps the zero curve).')}
      </figcaption>
    </figure>
  )
}
