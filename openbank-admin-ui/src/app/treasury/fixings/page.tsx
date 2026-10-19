// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury fixings (ADR-0344 D5): the reference rates due on a date and whether each was fixed,
// is still due, or was BLOCKED by the market-data quality gate (stale, jumped beyond its threshold,
// or missing). Back office runs the fixing, or proposes a manual fixing with a reason; a manual
// fixing is PENDING until a DIFFERENT person approves it, and the decision buttons are withheld
// from whoever proposed it.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSession } from 'next-auth/react'
import { Activity } from 'lucide-react'
import { z } from 'zod'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader, StatusBadge } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import { principalNameFromToken } from '@/lib/auth/principal'
import { bankToday } from '@/lib/format/bankDate'
import { hasPermission } from '@/lib/auth/roles'
import { wbGet, wbPost, wbUrl, type WbRead } from '@/components/treasury/wb/client'
import { fixingListSchema, fixingRunSchema, manualFixingSchema, type FixingRow } from '@/components/treasury/wb/types'
import { isAiPrincipal, mayDecide } from '@/components/treasury/wb/principal'
import { Field, Notice, ReadFailure, Section, td, tdNum, th, useFmt, writeFailureText } from '@/components/treasury/wb/ui'

export default function TreasuryFixingsPage() {
  return (
    <AuthGuard permission="treasury:workbench:view">
      <Fixings />
    </AuthGuard>
  )
}

const TONE: Record<string, Tone> = { FIXED: 'success', DUE: 'warning', BLOCKED: 'danger' }

function statusText(s: string, t: (cs: string, en: string) => string): string {
  if (s === 'FIXED') return t('Zafixováno', 'Fixed')
  if (s === 'DUE') return t('Ke zafixování', 'Due')
  if (s === 'BLOCKED') return t('Zablokováno', 'Blocked')
  if (s.includes('PENDING')) return t('Ruční fixing čeká', 'Manual fixing pending')
  return s
}

function Fixings() {
  const { t, num, dateTime } = useFmt()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const actor = principalNameFromToken(session?.user?.accessToken)
  const ai = isAiPrincipal(session?.user as { roles?: string[] } | undefined)
  const canRun = hasPermission(roles, 'treasury:backoffice:write')
  const canDecide = hasPermission(roles, 'treasury:decision:approve')

  const [date, setDate] = useState(() => bankToday())
  const [status, setStatus] = useState('')
  const [result, setResult] = useState<WbRead<FixingRow[]> | null>(null)
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [manual, setManual] = useState<FixingRow | null>(null)
  const [form, setForm] = useState({ rate: '', reason: '', source: 'MANUAL' })
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setResult(await wbGet(wbUrl('/fixings', { date, status }), fixingListSchema))
  }, [date, status])
  useEffect(() => { void load() }, [load])

  const run = async () => {
    setBusy(true)
    const res = await wbPost(wbUrl('/fixings/run', { date }), undefined, fixingRunSchema)
    setBusy(false)
    if (res.ok) {
      setNotice({ tone: 'success', text: t(`Fixing proběhl: zafixováno ${res.data.fixed.length}, zablokováno ${res.data.blocked.length}.`, `Fixing run: ${res.data.fixed.length} fixed, ${res.data.blocked.length} blocked.`) })
      void load()
    } else setNotice({ tone: 'danger', text: writeFailureText(res, t('Spuštění fixingu', 'Run fixings'), t) })
  }

  const propose = async () => {
    if (!manual) return
    if (!(form.rate.trim() !== '' && Number.isFinite(Number(form.rate)))) { setFormError(t('Zadejte sazbu jako číslo.', 'Enter the rate as a number.')); return }
    if (!form.reason.trim()) { setFormError(t('Důvod je povinný.', 'A reason is required.')); return }
    setFormError(null)
    setBusy(true)
    const res = await wbPost(wbUrl(`/fixings/${encodeURIComponent(manual.index)}/${encodeURIComponent(manual.fixingDate)}/manual`), { rate: Number(form.rate), reason: form.reason.trim(), source: form.source.trim() || 'MANUAL' }, manualFixingSchema)
    setBusy(false)
    if (res.ok) { setManual(null); setForm({ rate: '', reason: '', source: 'MANUAL' }); setNotice({ tone: 'success', text: t('Ruční fixing navržen — čeká na schválení jinou osobou.', 'Manual fixing proposed — a different person must approve it.') }); void load() }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Ruční fixing', 'Manual fixing'), t) })
  }

  const decide = async (row: FixingRow, action: 'approve' | 'reject') => {
    let body: unknown
    if (action === 'reject') {
      const reason = window.prompt(t('Důvod zamítnutí', 'Reason for rejection'))
      if (!reason?.trim()) return
      body = { reason: reason.trim() }
    }
    setBusy(true)
    const res = await wbPost(wbUrl(`/fixings/${encodeURIComponent(row.index)}/${encodeURIComponent(row.fixingDate)}/manual/${action}`), body, z.unknown())
    setBusy(false)
    const label = action === 'approve' ? t('Schválení fixingu', 'Approve fixing') : t('Zamítnutí fixingu', 'Reject fixing')
    if (res.ok) { setNotice({ tone: 'success', text: `${label}: ${t('hotovo.', 'done.')}` }); void load() }
    else setNotice({ tone: 'danger', text: writeFailureText(res, label, t) })
  }

  return (
    <div>
      <PageHeader title={t('Fixingy', 'Fixings')} subtitle={t('Referenční sazby ke zafixování a kontrola kvality tržních dat.', 'Reference rates due for fixing and the market-data quality gate.')} icon={<Activity size={20} aria-hidden="true" />} />
      <Section title={t('Výběr', 'Selection')} id="fixings-filter" actions={canRun ? <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void run()}>{t('Spustit fixing', 'Run fixings')}</button> : undefined}>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <Field label={t('Datum fixingu', 'Fixing date')}><input className="input" type="date" value={date} onChange={e => setDate(e.target.value)} /></Field>
          <Field label={t('Stav', 'Status')}>
            <select className="input" value={status} onChange={e => setStatus(e.target.value)}>
              <option value="">{t('Všechny', 'All')}</option>
              {['DUE', 'FIXED', 'BLOCKED'].map(s => <option key={s} value={s}>{statusText(s, t)}</option>)}
            </select>
          </Field>
        </div>
      </Section>
      {notice && <Notice tone={notice.tone}>{notice.text}</Notice>}

      <Section title={t('Fixingy k datu', 'Fixings for the date')} id="fixings-table">
        {result === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
        {result && !result.ok && <ReadFailure result={result} what={t('Fixingy', 'Fixings')} />}
        {result?.ok && (result.data.length === 0 ? <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('K tomuto datu nejsou žádné fixingy.', 'There are no fixings for this date.')}</p> : (
          <>
            <p data-testid="record-count" style={{ fontSize: 12, marginBottom: 8 }}>{t(`Záznamů: ${result.data.length}`, `Records: ${result.data.length}`)}</p>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Fixingy', 'Fixings')}</caption>
              <thead><tr>{[t('Index', 'Index'), t('Datum', 'Date'), t('Stav', 'Status')].map(h => <th key={h} scope="col" style={th}>{h}</th>)}<th scope="col" style={{ ...th, textAlign: 'right' }}>{t('Sazba %', 'Rate %')}</th>{[t('Zdroj', 'Source'), t('Pozorováno', 'Observed'), t('Obchodů', 'Deals'), t('Kvalita / důvod blokace', 'Quality / block reason'), ''].map((h, i) => <th key={i} scope="col" style={th}>{h}</th>)}</tr></thead>
              <tbody>
                {result.data.map(r => {
                  const pending = r.status.includes('PENDING')
                  return (
                    <tr key={`${r.index}-${r.fixingDate}`} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={td}>{r.index}</td>
                      <td style={td}>{r.fixingDate}</td>
                      <td style={td}><StatusBadge status={r.status} tone={pending ? 'warning' : (TONE[r.status] ?? 'neutral')} label={statusText(r.status, t)} /></td>
                      <td style={tdNum}>{r.rate != null ? num(r.rate, 6) : '—'}</td>
                      <td style={td}>{r.source ?? '—'}</td>
                      <td style={td}>{dateTime(r.observedAt)}</td>
                      <td style={td}>{r.dealCount ?? '—'}</td>
                      <td style={td}>
                        {r.quality?.stale && <StatusBadge status="STALE" tone="warning" label={t('Neaktuální', 'Stale')} />}{' '}
                        {r.quality?.missing && <StatusBadge status="MISSING" tone="danger" label={t('Chybí', 'Missing')} />}{' '}
                        {r.quality?.jumpBp != null && r.quality.jumpBp !== 0 && <span>{t(`skok ${num(r.quality.jumpBp, 1)} bp`, `jump ${num(r.quality.jumpBp, 1)} bp`)}</span>}
                        {r.blockReason ? ` ${r.blockReason}` : ''}
                      </td>
                      <td style={{ ...td, whiteSpace: 'nowrap' }}>
                        {canRun && (r.status === 'BLOCKED' || r.status === 'DUE') && (
                          <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setManual(r); setFormError(null) }} aria-label={t(`Ruční fixing ${r.index} ${r.fixingDate}`, `Manual fixing ${r.index} ${r.fixingDate}`)}>{t('Ruční fixing', 'Manual fixing')}</button>
                        )}
                        {pending && canDecide && mayDecide(r.proposedBy, actor, ai) && (
                          <>
                            <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void decide(r, 'approve')}>{t('Schválit', 'Approve')}</button>{' '}
                            <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void decide(r, 'reject')}>{t('Zamítnout', 'Reject')}</button>
                          </>
                        )}
                        {pending && !mayDecide(r.proposedBy, actor, ai) && <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{t('Rozhodne jiná osoba.', 'A different person decides.')}</span>}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </>
        ))}
        {manual && (
          <div role="dialog" aria-modal="false" aria-label={t('Ruční fixing', 'Manual fixing')} className="card" style={{ marginTop: 12 }}>
            <h3 style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>{t(`Ruční fixing: ${manual.index}, ${manual.fixingDate}`, `Manual fixing: ${manual.index}, ${manual.fixingDate}`)}</h3>
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
              <Field label={t('Sazba %', 'Rate %')} error={formError}><input className="input" inputMode="decimal" value={form.rate} onChange={e => setForm(f => ({ ...f, rate: e.target.value }))} /></Field>
              <Field label={t('Zdroj', 'Source')}><input className="input" value={form.source} onChange={e => setForm(f => ({ ...f, source: e.target.value }))} /></Field>
              <Field label={t('Důvod (povinný)', 'Reason (required)')}><input className="input" value={form.reason} onChange={e => setForm(f => ({ ...f, reason: e.target.value }))} /></Field>
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void propose()}>{t('Navrhnout fixing', 'Propose fixing')}</button>
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => setManual(null)}>{t('Zavřít', 'Close')}</button>
            </div>
          </div>
        )}
      </Section>
    </div>
  )
}
