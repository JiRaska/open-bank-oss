// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Investment strategies (ADR-0334, openbank-pension-fund-service): target allocations per fund
// with rebalancing bands, and the governed change cycle — one operator proposes a new allocation
// with a reason and an effective date, a DIFFERENT operator approves or rejects it, and an approved
// change is applied once its effective date (after the participant-notice period) has arrived.
//
// FOUR-EYES: approve/reject is hidden on a change the viewer submitted. That is a courtesy, not
// the control — pension-fund-service refuses a self-approval whatever the UI shows.

'use client'

import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { useSession } from 'next-auth/react'
import { RefreshCw, Scale } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { fundUrl, getJson, PENSION_FUND, sendJson } from '@/components/pension/api'
import {
  fundListSchema, strategyChangeListSchema, strategyChangeSchema, strategyListSchema,
  type Allocation, type Fund, type Strategy, type StrategyChange,
} from '@/components/pension/contracts'
import { allocationProblem, canApplyChange, canDecideChange, refusalText, statusLabel } from '@/components/pension/model'
import { principalNameFromToken } from '@/components/balance-sheet/model'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionStrategiesPage() {
  return (
    <AuthGuard permission="pension:view">
      <Strategies />
    </AuthGuard>
  )
}

type Notice = { tone: 'success' | 'danger'; text: string }
type Row = { fundId: string; weight: string; lowerBand: string; upperBand: string }

const toRows = (allocations: Allocation[]): Row[] =>
  allocations.map(a => ({ fundId: a.fundId, weight: String(a.weight), lowerBand: String(a.lowerBand), upperBand: String(a.upperBand) }))
const toAllocations = (rows: Row[]): Allocation[] =>
  rows.map(r => ({ fundId: r.fundId, weight: Number(r.weight), lowerBand: Number(r.lowerBand), upperBand: Number(r.upperBand) }))

function Strategies() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const canOperate = hasPermission(roles, 'pension:operate')
  const actor = principalNameFromToken(session?.user?.accessToken)
  const today = new Date().toISOString().slice(0, 10)

  const [strategies, setStrategies] = useState<Strategy[] | null>(null)
  const [funds, setFunds] = useState<Fund[]>([])
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [changes, setChanges] = useState<StrategyChange[] | null>(null)
  const [changesKind, setChangesKind] = useState<UnavailableKind | null>(null)
  const [rows, setRows] = useState<Row[]>([])
  const [reason, setReason] = useState('')
  const [effectiveDate, setEffectiveDate] = useState('')
  const [hint, setHint] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const load = useCallback(async () => {
    const [res, fs] = await Promise.all([getJson(fundUrl('/strategies'), strategyListSchema), getJson(fundUrl('/funds'), fundListSchema)])
    if (fs.ok) setFunds(fs.data)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setStrategies(null); return }
    setUnavailable(null)
    setStrategies(res.data)
    setSelected(prev => prev ?? res.data[0]?.id ?? null)
  }, [])

  const loadChanges = useCallback(async (strategyId: string) => {
    const res = await getJson(fundUrl(`/strategies/${encodeURIComponent(strategyId)}/changes`), strategyChangeListSchema)
    if (!res.ok) { setChangesKind(res.kind); setChanges(null); return }
    setChangesKind(null)
    setChanges(res.data)
  }, [])

  useEffect(() => { void load() }, [load])

  const strategy = strategies?.find(s => s.id === selected) ?? null
  useEffect(() => {
    if (!selected) return
    void loadChanges(selected)
    setRows(toRows(strategy?.allocations ?? []))
  }, [selected, strategy, loadChanges])

  const fundName = (id: string) => funds.find(f => f.id === id)?.name ?? t('Neznámý fond', 'Unknown fund')
  const setRow = (i: number, patch: Partial<Row>) => setRows(prev => prev.map((r, j) => (j === i ? { ...r, ...patch } : r)))

  const propose = async (e: FormEvent) => {
    e.preventDefault()
    if (!selected) return
    const allocations = toAllocations(rows)
    const problem = allocationProblem(allocations, t)
    if (problem) { setHint(problem); return }
    if (!reason.trim()) { setHint(t('Uveďte důvod změny.', 'Give a reason for the change.')); return }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(effectiveDate) || effectiveDate < today) {
      setHint(t('Datum účinnosti musí být dnes nebo později.', 'The effective date must be today or later.'))
      return
    }
    setHint(null)
    setBusy(true)
    setNotice(null)
    const res = await sendJson('POST', fundUrl(`/strategies/${encodeURIComponent(selected)}/changes`), { allocations, reason: reason.trim(), effectiveDate }, strategyChangeSchema)
    setBusy(false)
    setNotice(res.ok
      ? { tone: 'success', text: t('Změna předložena. Schválit ji musí jiná osoba.', 'Change submitted. A different person must approve it.') }
      : { tone: 'danger', text: refusalText(res, t('Návrh změny', 'Change proposal'), t) })
    if (res.ok) setReason('')
    void loadChanges(selected)
  }

  const act = async (change: StrategyChange, action: 'approve' | 'reject' | 'apply') => {
    setBusy(true)
    setNotice(null)
    const res = await sendJson('POST', fundUrl(`/strategy-changes/${encodeURIComponent(change.id)}/${action}`), undefined, strategyChangeSchema)
    setBusy(false)
    const label = action === 'approve' ? t('Schválení', 'Approval') : action === 'reject' ? t('Zamítnutí', 'Rejection') : t('Uplatnění', 'Application')
    setNotice(res.ok ? { tone: 'success', text: t(`${label} provedeno.`, `${label} done.`) } : { tone: 'danger', text: refusalText(res, label, t) })
    void load()
    if (selected) void loadChanges(selected)
  }

  return (
    <div>
      <PageHeader
        title={t('Investiční strategie', 'Investment strategies')}
        subtitle={t('Změnu alokace navrhuje jedna osoba, schvaluje druhá; účastníci jsou informováni před účinností.', 'One person proposes an allocation change, another approves it; participants are notified before it takes effect.')}
        icon={<Scale size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />

      {notice && (
        <div role="status" className="card" style={{ marginBottom: 16, borderColor: notice.tone === 'danger' ? 'var(--danger)' : 'var(--success)' }}>
          {notice.text}
        </div>
      )}

      <section className="card" style={{ marginBottom: 16 }}>
        {unavailable ? (
          <DataUnavailable kind={unavailable.kind} service={PENSION_FUND} feature={t('investiční strategie', 'investment strategies')} lang={language} dense />
        ) : strategies === null ? null : strategies.length === 0 ? (
          <DataUnavailable kind="no_data" service={PENSION_FUND} feature={t('investiční strategie', 'investment strategies')} lang={language} dense />
        ) : (
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {strategies.map(s => (
              <button key={s.id} type="button" className={`btn btn-sm ${s.id === selected ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setSelected(s.id)}>
                {s.name}{s.lifecycle ? ` · ${t('životní cyklus', 'lifecycle')}` : ''} · {statusLabel(s.status, t)}
              </button>
            ))}
          </div>
        )}
      </section>

      {strategy && (
        <section className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
          <h2 style={{ fontSize: 15, marginTop: 0 }}>{t(`Alokace — verze ${strategy.version ?? '—'}`, `Allocation — version ${strategy.version ?? '—'}`)}</h2>
          <form onSubmit={propose}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Fond', 'Fund')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Váha', 'Weight')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Dolní pásmo', 'Lower band')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Horní pásmo', 'Upper band')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={`${r.fundId}-${i}`}>
                    <td>
                      {canOperate ? (
                        <select className="input" value={r.fundId} onChange={e => setRow(i, { fundId: e.target.value })} aria-label={t('Fond', 'Fund')}>
                          {funds.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}
                          {!funds.some(f => f.id === r.fundId) && <option value={r.fundId}>{t('Neznámý fond', 'Unknown fund')}</option>}
                        </select>
                      ) : fundName(r.fundId)}
                    </td>
                    {(['weight', 'lowerBand', 'upperBand'] as const).map(k => (
                      <td key={k} style={{ textAlign: 'right' }}>
                        {canOperate
                          ? <input className="input" inputMode="decimal" value={r[k]} onChange={e => setRow(i, { [k]: e.target.value })} aria-label={k === 'weight' ? t('Váha', 'Weight') : k === 'lowerBand' ? t('Dolní pásmo', 'Lower band') : t('Horní pásmo', 'Upper band')} style={{ width: 90, textAlign: 'right' }} />
                          : r[k]}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
            {canOperate && (
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', marginTop: 12 }}>
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => setRows(prev => [...prev, { fundId: funds[0]?.id ?? '', weight: '0', lowerBand: '0', upperBand: '0' }])}>
                  {t('Přidat fond', 'Add fund')}
                </button>
                <label style={{ fontSize: 12 }}>{t('Důvod', 'Reason')}
                  <input className="input" value={reason} onChange={e => setReason(e.target.value)} aria-label={t('Důvod změny', 'Reason for the change')} style={{ width: 240 }} />
                </label>
                <label style={{ fontSize: 12 }}>{t('Účinnost od', 'Effective from')}
                  <input className="input" type="date" value={effectiveDate} onChange={e => setEffectiveDate(e.target.value)} aria-label={t('Účinnost od', 'Effective from')} />
                </label>
                <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>{t('Navrhnout změnu', 'Propose change')}</button>
              </div>
            )}
            {hint && <div role="alert" style={{ fontSize: 12, color: 'var(--danger-text)', marginTop: 8 }}>{hint}</div>}
          </form>
        </section>
      )}

      {selected && (
        <section className="card" style={{ overflowX: 'auto' }}>
          <h2 style={{ fontSize: 15, marginTop: 0 }}>{t('Změny strategie', 'Strategy changes')}</h2>
          {changesKind ? (
            <DataUnavailable kind={changesKind} service={PENSION_FUND} feature={t('změny strategie', 'strategy changes')} lang={language} dense />
          ) : changes === null ? null : changes.length === 0 ? (
            <DataUnavailable kind="no_data" service={PENSION_FUND} feature={t('změny strategie', 'strategy changes')} lang={language} dense />
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Navrhovaná alokace', 'Proposed allocation')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Důvod', 'Reason')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Účinnost', 'Effective')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Oznámení účastníkům', 'Participant notice')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Navrhl / rozhodl', 'Submitted / decided by')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Rozhodnutí', 'Decision')}</th>
                </tr>
              </thead>
              <tbody>
                {changes.map(c => (
                  <tr key={c.id}>
                    <td>{c.proposedAllocations.map(a => `${fundName(a.fundId)} ${a.weight}`).join(', ')}</td>
                    <td>{c.reason}</td>
                    <td>{c.effectiveDate}</td>
                    <td>{c.participantNotificationDate ?? '—'}</td>
                    <td>{`${c.submittedBy} / ${c.decidedBy ?? '—'}`}</td>
                    <td>{statusLabel(c.status, t)}</td>
                    <td>
                      {canOperate && c.status === 'PENDING_APPROVAL' && !canDecideChange(c, actor) && (
                        <span style={{ fontSize: 12 }}>{t('Váš návrh — schválit jej musí jiná osoba.', 'Your proposal — a different person must approve it.')}</span>
                      )}
                      <div style={{ display: 'flex', gap: 6 }}>
                        {canOperate && canDecideChange(c, actor) && (
                          <>
                            <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void act(c, 'approve')}>{t('Schválit', 'Approve')}</button>
                            <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void act(c, 'reject')}>{t('Zamítnout', 'Reject')}</button>
                          </>
                        )}
                        {canOperate && canApplyChange(c, today) && (
                          <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void act(c, 'apply')}>{t('Uplatnit', 'Apply')}</button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </div>
  )
}
