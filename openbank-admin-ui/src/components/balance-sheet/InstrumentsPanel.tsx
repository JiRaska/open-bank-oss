// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The snapshot's instrument table, written for a risk officer (#11107): business labels instead of
// enums, the obligor as an entity chip instead of a UUID, the instrument's credit-risk class,
// weight and RWA joined from the snapshot's own capital result, largest exposure first, with
// per-currency totals and a search box. Raw ids live in the expandable row detail.
//
// PRIVACY: risk-engine carries only an opaque party reference (counterpartyRef). The obligor's
// name is resolved here, from party-service through the BFF, and only for an operator holding
// `parties:view` — the same rule EntityChip (ADR-0231 D3) applies everywhere else. Without it the
// chip shows a shortened id and nothing is fetched.
'use client'

import { Fragment, useEffect, useMemo, useState } from 'react'
import { useSession } from 'next-auth/react'
import { ChevronDown, ChevronRight, Copy } from 'lucide-react'
import { EntityChip } from '@/components/entities/EntityChip'
import { hasPermission } from '@/lib/auth/roles'
import { svcUrl } from '@/lib/services/bff'
import { getJson, riskUrl } from './api'
import { capitalSchema, type Instrument } from './contracts'
import {
  buildRows, capitalByInstrument, exposureClassLabel, filterRows, formatDate, formatMoney, formatPercent,
  kindCounts, kindGroupLabel, sortByOutstanding, totalsByCurrency, type InstrumentCapital, type Lang,
} from './instruments'

const PAGE_ROWS = 50
const NAME_CONCURRENCY = 6

type Props = { runId: string; instruments: Instrument[]; lang: Lang }

const dash = (title: string) => <span title={title} style={{ color: 'var(--text-tertiary)', cursor: 'help' }}>—</span>

export function InstrumentsPanel({ runId, instruments, lang }: Props) {
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)
  const locale = lang === 'cs' ? 'cs-CZ' : 'en-GB'
  const { data: session } = useSession()
  const canSeeNames = hasPermission(session?.user?.roles ?? [], 'parties:view')

  const [capital, setCapital] = useState<Map<string, InstrumentCapital> | null>(null)
  const [capitalFailed, setCapitalFailed] = useState(false)
  const [names, setNames] = useState<ReadonlyMap<string, string>>(new Map())
  const [query, setQuery] = useState('')
  const [showAll, setShowAll] = useState(false)
  const [open, setOpen] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const res = await getJson(riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(runId)}/capital`), capitalSchema)
      if (cancelled) return
      if (res.ok) { setCapital(capitalByInstrument(res.data)); setCapitalFailed(false) } else { setCapital(null); setCapitalFailed(true) }
    })()
    return () => { cancelled = true }
  }, [runId])

  const partyIds = useMemo(
    () => [...new Set(instruments.map(i => i.counterpartyRef).filter((v): v is string => !!v))].sort(),
    [instruments],
  )
  useEffect(() => {
    if (!canSeeNames || partyIds.length === 0) return
    const ctrl = new AbortController()
    const found = new Map<string, string>()
    const queue = [...partyIds]
    const worker = async () => {
      for (let id = queue.shift(); id !== undefined; id = queue.shift()) {
        try {
          const r = await fetch(svcUrl('party-service', `/api/v1/parties/${encodeURIComponent(id)}`), { signal: ctrl.signal, cache: 'no-store' })
          if (!r.ok) continue
          const d = await r.json() as Record<string, unknown>
          const name = typeof d.legalName === 'string' ? d.legalName : typeof d.tradingName === 'string' ? d.tradingName : null
          if (name) found.set(id, name)
        } catch { if (ctrl.signal.aborted) return }
      }
    }
    void Promise.all(Array.from({ length: Math.min(NAME_CONCURRENCY, partyIds.length) }, worker))
      .then(() => { if (!ctrl.signal.aborted) setNames(new Map(found)) })
    return () => ctrl.abort()
  }, [canSeeNames, partyIds])

  const visibleNames = useMemo(() => (canSeeNames ? names : new Map<string, string>()), [canSeeNames, names])
  const all = useMemo(() => sortByOutstanding(buildRows(instruments, lang, capital, visibleNames)), [instruments, lang, capital, visibleNames])
  const filtered = useMemo(() => filterRows(all, query), [all, query])
  const totals = useMemo(() => totalsByCurrency(filtered), [filtered])
  const shown = showAll || query ? filtered : filtered.slice(0, PAGE_ROWS)
  const counts = kindCounts(instruments)

  const rwaUnknown = capitalFailed
    ? t('Kapitálový výpočet snímku není dostupný — RWA a riziková váha nelze uvést.', 'The snapshot capital result is unavailable — RWA and risk weight cannot be stated.')
    : capital === null
      ? t('Kapitálový výpočet se načítá.', 'Loading the capital result.')
      : t('Kapitálový výpočet tento nástroj neuvádí (např. pasivum mimo úvěrové riziko).', 'The capital result lists no exposure for this instrument (e.g. a liability outside credit risk).')

  const copy = (v: string) => { void navigator.clipboard?.writeText(v).catch(() => undefined) }
  const th = (label: string, right = false) => <th scope="col" style={{ textAlign: right ? 'right' : 'left', whiteSpace: 'nowrap', padding: '4px 6px' }}>{label}</th>
  const td = { padding: '4px 6px', verticalAlign: 'top' as const }
  const tdR = { ...td, textAlign: 'right' as const, whiteSpace: 'nowrap' as const }

  return (
    <>
      <p style={{ fontSize: 13, marginBottom: 8 }} data-testid="instrument-kind-counts">
        {counts.map(([kind, n]) => `${kindGroupLabel(kind, lang)}: ${n.toLocaleString(locale)}`).join(' · ')}
      </p>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }}>
        <input
          type="search" value={query} onChange={e => setQuery(e.target.value)}
          placeholder={t('Hledat podle reference, dlužníka nebo ID…', 'Search by reference, obligor or id…')}
          aria-label={t('Hledat nástroje', 'Search instruments')}
          className="input" style={{ minWidth: 260, fontSize: 13 }}
        />
        {query && <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t(`Nalezeno ${filtered.length} z ${all.length}`, `${filtered.length} of ${all.length} match`)}</span>}
      </div>
      {!canSeeNames && partyIds.length > 0 && (
        <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>
          {t('Jména dlužníků vyžadují oprávnění k zobrazení klientů; zobrazen je zkrácený identifikátor klienta.',
            'Obligor names require the party-view permission; a shortened party id is shown instead.')}
        </p>
      )}
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }} aria-label={t('Nástroje snímku', 'Snapshot instruments')}>
        <thead>
          <tr>
            {th(t('Reference', 'Reference'))}{th(t('Dlužník', 'Obligor'))}{th(t('Druh', 'Kind'))}
            {th(t('Nesplacená jistina', 'Outstanding principal'), true)}{th(t('Sazba', 'Rate'), true)}{th(t('Splatnost', 'Maturity'))}
            {th(t('Třída expozice', 'Exposure class'))}{th(t('Riz. váha', 'Risk weight'), true)}{th('RWA', true)}
          </tr>
        </thead>
        <tbody>
          {shown.length === 0 && (
            <tr><td colSpan={9} style={{ ...td, color: 'var(--text-secondary)' }}>{t('Hledání neodpovídá žádný nástroj.', 'No instrument matches the search.')}</td></tr>
          )}
          {shown.map(r => {
            const i = r.instrument
            const expanded = open === i.id
            const rt = i.rateTerms
            return (
              <Fragment key={i.id}>
                <tr data-instrument-id={i.id} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={td}>
                    <button
                      type="button" onClick={() => setOpen(expanded ? null : i.id)} aria-expanded={expanded}
                      title={i.contractNumber
                        ? t(`Detail nástroje ${i.id}. Reference je číslo smlouvy.`, `Instrument detail ${i.id}. The reference is the contract number.`)
                        : t(`Detail nástroje ${i.id}. Bez čísla smlouvy, reference je zkrácené ID.`, `Instrument detail ${i.id}. No contract number; the reference is a shortened id.`)}
                      style={{ display: 'inline-flex', alignItems: 'center', gap: 4, background: 'none', border: 'none', padding: 0, cursor: 'pointer', color: 'inherit', font: 'inherit', fontWeight: 600 }}
                    >
                      {expanded ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
                      {r.reference}
                    </button>
                  </td>
                  <td style={td}>
                    {i.counterpartyRef
                      ? <EntityChip
                          type="party" id={i.counterpartyRef}
                          // The panel resolves names itself, once per party; a label always set keeps the
                          // chip from issuing a second lookup per row.
                          label={canSeeNames ? r.obligorName ?? `${t('Klient', 'Party')} ${i.counterpartyRef.slice(0, 8)}…` : undefined}
                        />
                      : dash(t('Snímek k nástroji neuvádí dlužníka.', 'The snapshot names no obligor for this instrument.'))}
                  </td>
                  <td style={td}>{r.kindLabel}</td>
                  <td style={tdR}>{formatMoney(i.outstanding, i.currency, locale)}</td>
                  <td style={tdR}>
                    {rt?.currentAnnualRate != null
                      ? <span title={rt.rateType === 'FLOATING' ? `${rt.index ?? '?'} + ${rt.spread != null ? formatPercent(rt.spread, locale) : '?'}` : t('Pevná sazba', 'Fixed rate')}>
                          {formatPercent(rt.currentAnnualRate, locale)}{rt.rateType === 'FLOATING' ? ` (${rt.index ?? t('pohyblivá', 'floating')})` : ''}
                        </span>
                      : dash(t('Snímek neobsahuje úrokové podmínky nástroje (starší snímek nebo nástroj bez sazby).', 'The snapshot carries no rate terms for this instrument (older snapshot or no rate).'))}
                  </td>
                  <td style={{ ...td, whiteSpace: 'nowrap' }}>
                    {i.maturityDate ? formatDate(i.maturityDate, locale) : dash(t('Bez smluvní splatnosti (nebo ji zdroj neuvádí).', 'No contractual maturity (or the source does not state one).'))}
                  </td>
                  <td style={td}>{r.capital ? exposureClassLabel(r.capital.exposureClass, lang) : dash(rwaUnknown)}</td>
                  <td style={tdR}>{r.capital ? formatPercent(r.capital.riskWeight, locale) : dash(rwaUnknown)}</td>
                  <td style={tdR}>{r.capital ? formatMoney(r.capital.rwa, i.currency, locale) : dash(rwaUnknown)}</td>
                </tr>
                {expanded && (
                  <tr data-testid="instrument-detail">
                    <td colSpan={9} style={{ ...td, background: 'var(--surface-2)', fontSize: 12 }}>
                      <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '2px 12px', margin: 0 }}>
                        <dt>{t('ID nástroje', 'Instrument id')}</dt>
                        <dd style={{ margin: 0 }}><IdWithCopy id={i.id} onCopy={copy} label={t('Kopírovat', 'Copy')} /></dd>
                        <dt>{t('ID dlužníka', 'Obligor id')}</dt>
                        <dd style={{ margin: 0 }}>{i.counterpartyRef ? <IdWithCopy id={i.counterpartyRef} onCopy={copy} label={t('Kopírovat', 'Copy')} /> : '—'}</dd>
                        <dt>{t('Účet hlavní knihy', 'GL account')}</dt><dd style={{ margin: 0 }}>{i.glAccountCode ?? '—'}</dd>
                        <dt>{t('Stupeň IFRS 9', 'IFRS 9 stage')}</dt><dd style={{ margin: 0 }}>{i.ifrs9Stage ?? '—'}</dd>
                        <dt>{t('Datum čerpání', 'Value date')}</dt><dd style={{ margin: 0 }}>{i.valueDate ? formatDate(i.valueDate, locale) : '—'}</dd>
                        {i.loan && (<>
                          <dt>{t('Příští splátka', 'Next due')}</dt><dd style={{ margin: 0 }}>{i.loan.nextDueDate ? formatDate(i.loan.nextDueDate, locale) : '—'}</dd>
                          <dt>{t('Zbývající splátky', 'Remaining installments')}</dt><dd style={{ margin: 0 }}>{i.loan.remainingPeriods.toLocaleString(locale)}</dd>
                        </>)}
                        {r.capital && (<>
                          <dt>EAD</dt><dd style={{ margin: 0 }}>{formatMoney(r.capital.ead, i.currency, locale)}</dd>
                        </>)}
                      </dl>
                    </td>
                  </tr>
                )}
              </Fragment>
            )
          })}
        </tbody>
        <tfoot>
          {totals.map(tot => (
            <tr key={tot.currency} data-testid={`instrument-total-${tot.currency}`} style={{ borderTop: '2px solid var(--border)', fontWeight: 600 }}>
              <td style={td} colSpan={3}>{t(`Celkem ${tot.currency} (${tot.count})`, `Total ${tot.currency} (${tot.count})`)}</td>
              <td style={tdR}>{formatMoney(tot.outstanding, tot.currency, locale)}</td>
              <td style={td} colSpan={4} />
              <td style={tdR}>
                {tot.rwaKnown === 0
                  ? dash(rwaUnknown)
                  : <span title={tot.rwaKnown < tot.count ? t(`Součet jen za ${tot.rwaKnown} z ${tot.count} nástrojů`, `Sum over ${tot.rwaKnown} of ${tot.count} instruments only`) : undefined}>
                      {formatMoney(tot.rwa, tot.currency, locale)}{tot.rwaKnown < tot.count ? '*' : ''}
                    </span>}
              </td>
            </tr>
          ))}
        </tfoot>
      </table>
      {!showAll && !query && filtered.length > PAGE_ROWS && (
        <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 8 }} onClick={() => setShowAll(true)}>
          {t(`Zobrazit všech ${filtered.length}`, `Show all ${filtered.length}`)}
        </button>
      )}
    </>
  )
}

function IdWithCopy({ id, onCopy, label }: { id: string; onCopy: (v: string) => void; label: string }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <code style={{ fontSize: 12 }}>{id}</code>
      <button type="button" onClick={() => onCopy(id)} aria-label={`${label} ${id}`} title={label}
        style={{ background: 'none', border: 'none', padding: 0, cursor: 'pointer', color: 'var(--accent)' }}>
        <Copy size={12} aria-hidden="true" />
      </button>
    </span>
  )
}
