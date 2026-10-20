// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// One generic effective-dated reference-data editor, driven by the kind catalogue (referenceKinds.ts):
// records by key with their versions and status, a version timeline, what depends on a record, the
// pending queue with approve / reject / verify (never by or for the proposer, never by an AI
// principal), the verified badge, a form generated from the kind's field schema, and import /
// export in the same JSON shape as the list endpoint.
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSession } from 'next-auth/react'
import { z } from 'zod'
import { StatusBadge, Tabs, type TabItem } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import { principalNameFromToken } from '@/lib/auth/principal'
import { bankToday } from '@/lib/format/bankDate'
import { hasPermission } from '@/lib/auth/roles'
import { wbGet, wbGetText, wbPost, wbUrl, type WbRead } from './client'
import { isAiPrincipal, mayDecide } from './principal'
import { KIND_BY_ID, KIND_GROUPS, KINDS, keyOf, kindName, toFieldValues, toPayload, type FieldSpec, type FieldValues, type KindSpec } from './referenceKinds'
import {
  referenceDependenciesSchema, referenceHistorySchema, referenceListSchema, referenceRecordSchema, userActivitySchema, versionHistorySchema,
  type ReferenceRecord,
} from './types'
import { downloadText, Field, Notice, ReadFailure, recordLabel, Section, td, th, useFmt, writeFailureText } from './ui'

type ErrorMap = Record<string, string>
const STATUS_TONE: Record<string, Tone> = { PENDING: 'warning', ACTIVE: 'success', SUPERSEDED: 'neutral', REJECTED: 'danger' }
type View = string // a reference kind id, or one of the two read-only views: 'user-activity' | 'application-version-history'
type Tab = 'records' | 'pending' | 'io'

export function statusText(s: string, t: (cs: string, en: string) => string): string {
  switch (s) {
    case 'PENDING': return t('Čeká na schválení', 'Pending approval')
    case 'ACTIVE': return t('Platné', 'Active')
    case 'SUPERSEDED': return t('Nahrazeno', 'Superseded')
    case 'REJECTED': return t('Zamítnuto', 'Rejected')
    default: return s
  }
}

export function ReferenceWorkbench() {
  const { t } = useFmt()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const actor = principalNameFromToken(session?.user?.accessToken)
  const ai = isAiPrincipal(session?.user as { roles?: string[] } | undefined)
  const canPropose = hasPermission(roles, 'treasury:reference:propose')
  const canApprove = hasPermission(roles, 'treasury:reference:approve')

  const [view, setView] = useState<View>('calendar')
  const [tab, setTab] = useState<Tab>('records')
  const spec: KindSpec | undefined = KIND_BY_ID[view]

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(180px, 240px) 1fr', gap: 16, alignItems: 'start' }}>
      <nav aria-label={t('Druhy referenčních dat', 'Reference-data kinds')} className="card" style={{ fontSize: 13 }}>
        {KIND_GROUPS.map(g => (
          <div key={g.id} style={{ marginBottom: 8 }}>
            <div style={{ fontSize: 11, color: 'var(--text-secondary)', textTransform: 'uppercase', marginBottom: 2 }}>{t(g.cs, g.en)}</div>
            {KINDS.filter(k => k.group === g.id).map(k => (
              <button key={k.kind} type="button" className={view === k.kind ? 'btn btn-primary btn-sm' : 'btn btn-ghost btn-sm'} style={{ display: 'block', width: '100%', textAlign: 'left' }}
                aria-current={view === k.kind ? 'page' : undefined} onClick={() => { setView(k.kind); setTab('records') }}>{kindName(k, t)}</button>
            ))}
          </div>
        ))}
        <div style={{ fontSize: 11, color: 'var(--text-secondary)', textTransform: 'uppercase', marginBottom: 2 }}>{t('Jen ke čtení', 'Read-only')}</div>
        <button type="button" className={view === 'user-activity' ? 'btn btn-primary btn-sm' : 'btn btn-ghost btn-sm'} style={{ display: 'block', width: '100%', textAlign: 'left' }} onClick={() => setView('user-activity')}>{t('Aktivita uživatelů', 'User activity')}</button>
        <button type="button" className={view === 'application-version-history' ? 'btn btn-primary btn-sm' : 'btn btn-ghost btn-sm'} style={{ display: 'block', width: '100%', textAlign: 'left' }} onClick={() => setView('application-version-history')}>{t('Historie verzí aplikace', 'Application version history')}</button>
      </nav>

      <div>
        {spec ? (
          <KindView key={spec.kind} spec={spec} tab={tab} setTab={setTab} actor={actor} ai={ai} canPropose={canPropose} canApprove={canApprove} />
        ) : view === 'user-activity' ? <UserActivity /> : <VersionHistory />}
      </div>
    </div>
  )
}

function KindView({ spec, tab, setTab, actor, ai, canPropose, canApprove }: { spec: KindSpec; tab: Tab; setTab: (t: Tab) => void; actor: string | null; ai: boolean; canPropose: boolean; canApprove: boolean }) {
  const { t } = useFmt()
  const [status, setStatus] = useState('')
  const [asOf, setAsOf] = useState('')
  const [result, setResult] = useState<WbRead<ReferenceRecord[]> | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [editing, setEditing] = useState<ReferenceRecord | 'new' | null>(null)
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => { setResult(await wbGet(wbUrl(`/reference/${spec.kind}`, { status, asOf }), referenceListSchema)) }, [spec.kind, status, asOf])
  useEffect(() => { void load() }, [load])

  const rows = result?.ok ? result.data : null
  const pending = rows?.filter(r => r.status === 'PENDING') ?? []
  /** The newest version of each key — what the records table lists. */
  const latest = useMemo(() => {
    const m = new Map<string, ReferenceRecord>()
    for (const r of rows ?? []) { const cur = m.get(r.key); if (!cur || cur.version < r.version) m.set(r.key, r) }
    return [...m.values()].sort((a, b) => a.key.localeCompare(b.key))
  }, [rows])

  const decide = async (r: ReferenceRecord, action: 'approve' | 'reject' | 'verify') => {
    let body: unknown
    if (action === 'reject') {
      const reason = window.prompt(t('Důvod zamítnutí', 'Reason for rejection'))
      if (!reason?.trim()) return
      body = { reason: reason.trim() }
    }
    setBusy(true)
    const res = await wbPost(wbUrl(`/reference/${spec.kind}/${encodeURIComponent(r.key)}/versions/${r.version}/${action}`), body, referenceRecordSchema)
    setBusy(false)
    const label = action === 'approve' ? t('Schválení', 'Approve') : action === 'reject' ? t('Zamítnutí', 'Reject') : t('Ověření', 'Verify')
    if (res.ok) { setNotice({ tone: 'success', text: `${label}: ${t('hotovo.', 'done.')}` }); void load() }
    else setNotice({ tone: 'danger', text: writeFailureText(res, label, t) })
  }

  const tabs: TabItem<Tab>[] = [
    { id: 'records', label: t('Záznamy', 'Records') },
    { id: 'pending', label: t(`Ke schválení (${pending.length})`, `Pending queue (${pending.length})`) },
    { id: 'io', label: t('Import a export', 'Import & export') },
  ]

  return (
    <div>
      <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 8 }}>{kindName(spec, t)}</h2>
      {!spec.published && <p role="note" style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>{t('Pro tento druh služba zatím nezveřejnila pevné schéma; editor nabízí kód, název, popis a pokročilé JSON.', 'The service has not published a fixed schema for this kind; the editor offers code, name, description and advanced JSON.')}</p>}
      <Tabs items={tabs} value={tab} onChange={setTab} label={t('Referenční data', 'Reference data')} idPrefix={`ref-${spec.kind}`} style={{ marginBottom: 12 }} />
      {notice && <Notice tone={notice.tone}>{notice.text}</Notice>}
      {result && !result.ok && <ReadFailure result={result} what={kindName(spec, t)} />}

      {tab === 'records' && result?.ok && (
        <Section title={t('Záznamy', 'Records')} id="ref-records"
          actions={canPropose ? <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing('new')}>{t('Nový záznam', 'New record')}</button> : undefined}>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 8 }}>
            <Field label={t('Stav', 'Status')}>
              <select className="input" value={status} onChange={e => setStatus(e.target.value)}>
                <option value="">{t('Všechny', 'All')}</option>
                {['PENDING', 'ACTIVE', 'SUPERSEDED', 'REJECTED'].map(s => <option key={s} value={s}>{statusText(s, t)}</option>)}
              </select>
            </Field>
            <Field label={t('Platné k datu', 'Effective on')}><input className="input" type="date" value={asOf} onChange={e => setAsOf(e.target.value)} /></Field>
          </div>
          <p data-testid="record-count" style={{ fontSize: 12, marginBottom: 8 }}>{t(`Záznamů: ${latest.length}`, `Records: ${latest.length}`)}</p>
          {latest.length === 0 ? <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Žádné záznamy.', 'No records.')}</p> : (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{kindName(spec, t)}</caption>
              <thead><tr>{[t('Záznam', 'Record'), t('Verze', 'Version'), t('Stav', 'Status'), t('Platí od', 'Valid from'), t('Platí do', 'Valid to'), t('Ověřeno', 'Verified'), ''].map((h, i) => <th key={i} scope="col" style={th}>{h}</th>)}</tr></thead>
              <tbody>
                {latest.map(r => (
                  <tr key={r.key} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={td}>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setSelected(r.key)} aria-label={t(`Otevřít záznam ${recordLabel(r)}`, `Open record ${recordLabel(r)}`)}>{recordLabel(r)}</button>
                    </td>
                    <td style={td}>{r.version}</td>
                    <td style={td}><StatusBadge status={r.status} tone={STATUS_TONE[r.status] ?? 'neutral'} label={statusText(r.status, t)} /></td>
                    <td style={td}>{r.validFrom}</td>
                    <td style={td}>{r.validTo ?? '—'}</td>
                    <td style={td}>{r.verified ? <StatusBadge status="VERIFIED" tone="success" label={t('Ověřeno', 'Verified')} /> : <StatusBadge status="UNVERIFIED" tone="warning" label={t('Neověřeno', 'Unverified')} />}</td>
                    <td style={td}>{canPropose && <button type="button" className="btn btn-secondary btn-sm" onClick={() => setEditing(r)}>{t('Nová verze', 'New version')}</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Section>
      )}

      {tab === 'pending' && result?.ok && (
        <Section title={t('Fronta ke schválení', 'Pending queue')} id="ref-pending">
          {pending.length === 0 ? <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Nic nečeká na schválení.', 'Nothing is waiting for approval.')}</p> : (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Fronta ke schválení', 'Pending queue')}</caption>
              <thead><tr>{[t('Záznam', 'Record'), t('Verze', 'Version'), t('Navrhl', 'Proposed by'), t('Platí od', 'Valid from'), t('Důvod', 'Reason'), ''].map((h, i) => <th key={i} scope="col" style={th}>{h}</th>)}</tr></thead>
              <tbody>
                {pending.map(r => {
                  const may = canApprove && mayDecide(r.createdBy, actor, ai)
                  return (
                    <tr key={`${r.key}-${r.version}`} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={td}>{recordLabel(r)}</td>
                      <td style={td}>{r.version}</td>
                      <td style={td}>{r.createdBy}{r.createdByType === 'AI_AGENT' && <> <StatusBadge status="AI" tone="info" label={t('AI návrh', 'AI proposal')} /></>}</td>
                      <td style={td}>{r.validFrom}</td>
                      <td style={td}>{r.reason ?? '—'}</td>
                      <td style={{ ...td, whiteSpace: 'nowrap' }}>
                        {may && (
                          <>
                            <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void decide(r, 'approve')}>{t('Schválit', 'Approve')}</button>{' '}
                            <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void decide(r, 'reject')}>{t('Zamítnout', 'Reject')}</button>
                          </>
                        )}
                        {canApprove && !may && <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{ai ? t('AI nesmí schvalovat.', 'An AI principal may not approve.') : t('Navrhli jste — rozhodne jiná osoba.', 'You proposed it — a different person decides.')}</span>}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </Section>
      )}

      {tab === 'io' && <ImportExport spec={spec} canPropose={canPropose} onDone={load} setNotice={setNotice} />}

      {selected && <RecordDetail spec={spec} recordKey={selected} actor={actor} ai={ai} canApprove={canApprove} onClose={() => setSelected(null)} onDecide={decide} busy={busy} />}
      {editing && <RecordEditor spec={spec} base={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onProposed={() => { setEditing(null); setNotice({ tone: 'success', text: t('Verze navržena — čeká na schválení jinou osobou.', 'Version proposed — a different person must approve it.') }); void load() }} />}
    </div>
  )
}

function RecordDetail({ spec, recordKey, actor, ai, canApprove, onClose, onDecide, busy }: { spec: KindSpec; recordKey: string; actor: string | null; ai: boolean; canApprove: boolean; onClose: () => void; onDecide: (r: ReferenceRecord, a: 'approve' | 'reject' | 'verify') => void; busy: boolean }) {
  const { t, dateTime } = useFmt()
  const [history, setHistory] = useState<WbRead<z.infer<typeof referenceHistorySchema>> | null>(null)
  const [deps, setDeps] = useState<WbRead<z.infer<typeof referenceDependenciesSchema>> | null>(null)
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const [h, d] = await Promise.all([
        wbGet(wbUrl(`/reference/${spec.kind}/${encodeURIComponent(recordKey)}`), referenceHistorySchema, { notFound: 'missing' }),
        wbGet(wbUrl(`/reference/dependencies/${spec.kind}/${encodeURIComponent(recordKey)}`), referenceDependenciesSchema),
      ])
      if (!cancelled) { setHistory(h); setDeps(d) }
    })()
    return () => { cancelled = true }
  }, [spec.kind, recordKey])
  return (
    <Section title={t(`Záznam ${recordKey}`, `Record ${recordKey}`)} id="ref-detail" actions={<button type="button" className="btn btn-secondary btn-sm" onClick={onClose}>{t('Zavřít', 'Close')}</button>}>
      {history && !history.ok && <ReadFailure result={history} what={t('Historie záznamu', 'The record history')} />}
      {history?.ok && (
        <ol aria-label={t('Časová osa verzí', 'Version timeline')} style={{ fontSize: 13, paddingLeft: 18 }}>
          {history.data.versions.map(v => (
            <li key={v.version} style={{ marginBottom: 6 }}>
              <strong>{t(`Verze ${v.version}`, `Version ${v.version}`)}</strong>{' '}
              <StatusBadge status={v.status} tone={STATUS_TONE[v.status] ?? 'neutral'} label={statusText(v.status, t)} />{' '}
              {v.verified && <StatusBadge status="VERIFIED" tone="success" label={t('Ověřeno', 'Verified')} />}{' '}
              {history.data.active?.version === v.version && <StatusBadge status="CURRENT" tone="info" label={t('Aktuální', 'Current')} />}
              <div style={{ color: 'var(--text-secondary)' }}>
                {`${v.validFrom} → ${v.validTo ?? '…'} · ${t('navrhl', 'proposed by')} ${v.createdBy} (${dateTime(v.createdAt)})${v.approvedBy ? ` · ${t('schválil', 'approved by')} ${v.approvedBy}` : ''}${v.reason ? ` · ${v.reason}` : ''}${v.decisionReason ? ` · ${v.decisionReason}` : ''}${v.approvalNote ? ` · ${v.approvalNote}` : ''}`}
              </div>
              {v.status === 'ACTIVE' && !v.verified && canApprove && mayDecide(v.createdBy, actor, ai) && (
                <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => onDecide(v, 'verify')}>{t('Označit jako ověřené', 'Mark as verified')}</button>
              )}
            </li>
          ))}
        </ol>
      )}
      <h3 style={{ fontSize: 13, fontWeight: 600, margin: '12px 0 4px' }}>{t('Co na záznamu závisí', 'What depends on it')}</h3>
      {deps === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
      {deps && !deps.ok && <ReadFailure result={deps} what={t('Závislosti záznamu', 'Record dependencies')} />}
      {deps?.ok && (deps.data.dependants.length === 0 ? <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Nic na něm nezávisí.', 'Nothing depends on it.')}</p> : (
        <ul style={{ fontSize: 13 }}>{deps.data.dependants.map((d, i) => <li key={i}>{`${d.type}: ${d.id}${d.field ? ` (${d.field})` : ''}`}</li>)}</ul>
      ))}
    </Section>
  )
}

function FieldControl({ spec, f, values, onChange, error }: { spec: KindSpec; f: FieldSpec; values: FieldValues; onChange: (v: string | boolean) => void; error?: string }) {
  const { t } = useFmt()
  const label = `${t(f.cs, f.en)}${f.required ? ' *' : ''}`
  const v = values[f.name]
  const hint = f.hint ? t(f.hint[0], f.hint[1]) : undefined
  void spec
  if (f.type === 'boolean') {
    return <Field label={label} error={error}><input type="checkbox" checked={v === true} onChange={e => onChange(e.target.checked)} /></Field>
  }
  if (f.type === 'select') {
    return (
      <Field label={label} error={error}>
        <select className="input" value={String(v ?? '')} onChange={e => onChange(e.target.value)}>
          <option value="">{t('— vyberte —', '— select —')}</option>
          {f.options?.map(o => <option key={o} value={o}>{o}</option>)}
        </select>
      </Field>
    )
  }
  if (f.type === 'map' || f.type === 'buckets') {
    return <Field label={label} error={error} hint={hint}><textarea className="input" rows={3} value={String(v ?? '')} onChange={e => onChange(e.target.value)} /></Field>
  }
  return (
    <Field label={label} error={error} hint={hint}>
      <input className="input" type={f.type === 'date' ? 'date' : 'text'} inputMode={f.type === 'number' || f.type === 'integer' ? 'decimal' : undefined} value={String(v ?? '')} onChange={e => onChange(e.target.value)} />
    </Field>
  )
}

function RecordEditor({ spec, base, onClose, onProposed }: { spec: KindSpec; base: ReferenceRecord | null; onClose: () => void; onProposed: () => void }) {
  const { t } = useFmt()
  const [values, setValues] = useState<FieldValues>(() => toFieldValues(spec, base?.payload ?? {}))
  const [validFrom, setValidFrom] = useState(() => bankToday())
  const [reason, setReason] = useState('')
  const [advanced, setAdvanced] = useState(false)
  const [json, setJson] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    let payload: Record<string, unknown>
    let errs: ErrorMap = {}
    if (advanced) {
      try { payload = JSON.parse(json) as Record<string, unknown> } catch { setFormError(t('JSON není platný.', 'The JSON is not valid.')); return }
    } else {
      const r = toPayload(spec, values, t)
      payload = r.payload
      errs = r.errors
    }
    if (!reason.trim()) errs.reason = t('Důvod je povinný.', 'A reason is required.')
    const key = keyOf(spec, payload)
    if (!key && !errs.reason && Object.keys(errs).length === 0) errs[spec.keyFields[0]] = t('Povinné pole.', 'Required field.')
    setErrors(errs)
    if (Object.keys(errs).length) return
    // A new version of an existing record keeps its key; a key edit would be a different record.
    if (base && key !== base.key) { setFormError(t('Klíč existujícího záznamu nelze změnit; založte nový záznam.', 'The key of an existing record cannot change; create a new record.')); return }
    setFormError(null)
    setBusy(true)
    const res = await wbPost(wbUrl(`/reference/${spec.kind}`), { key, validFrom, payload, reason: reason.trim() }, referenceRecordSchema)
    setBusy(false)
    if (res.ok) onProposed()
    else setFormError(writeFailureText(res, t('Návrh verze', 'Propose version'), t))
  }

  return (
    <Section title={base ? t(`Nová verze: ${base.key}`, `New version: ${base.key}`) : t('Nový záznam', 'New record')} id="ref-editor"
      actions={<button type="button" className="btn btn-secondary btn-sm" onClick={onClose}>{t('Zavřít', 'Close')}</button>}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
        {!advanced && spec.fields.map(f => (
          <FieldControl key={f.name} spec={spec} f={f} values={values} error={errors[f.name]}
            onChange={v => { setValues(s => ({ ...s, [f.name]: v })); setErrors(e => { const n = { ...e }; delete n[f.name]; return n }) }} />
        ))}
        {advanced && (
          <Field label={t('Obsah (JSON)', 'Payload (JSON)')} error={formError}><textarea className="input" rows={8} value={json} onChange={e => setJson(e.target.value)} /></Field>
        )}
        <Field label={t('Platí od', 'Valid from')}><input className="input" type="date" value={validFrom} onChange={e => setValidFrom(e.target.value)} /></Field>
        <Field label={t('Důvod změny (povinný)', 'Reason for change (required)')} error={errors.reason}><input className="input" value={reason} onChange={e => setReason(e.target.value)} /></Field>
      </div>
      <label style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
        <input type="checkbox" checked={advanced} onChange={e => { setAdvanced(e.target.checked); if (e.target.checked) setJson(JSON.stringify(toPayload(spec, values, t).payload, null, 2)) }} /> {t('Pokročilé: upravit JSON', 'Advanced: edit JSON')}
      </label>
      {formError && !advanced && <p role="alert" style={{ color: 'var(--danger-text)', fontSize: 12, marginTop: 8 }}>{formError}</p>}
      <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
        <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void submit()}>{t('Navrhnout verzi', 'Propose version')}</button>
      </div>
    </Section>
  )
}

function ImportExport({ spec, canPropose, onDone, setNotice }: { spec: KindSpec; canPropose: boolean; onDone: () => void; setNotice: (n: { tone: 'success' | 'danger'; text: string }) => void }) {
  const { t } = useFmt()
  const exportKind = async () => {
    const res = await wbGetText(wbUrl(`/reference/${spec.kind}/export`))
    if (res.ok) downloadText(`reference-${spec.kind}.json`, res.text, 'application/json')
    else setNotice({ tone: 'danger', text: res.kind === 'not_available' ? t('Export zatím toto nasazení nenabízí.', 'This deployment does not offer export yet.') : t('Export se nepodařil.', 'The export failed.') })
  }
  const importKind = async (file: File) => {
    let rows: unknown
    try { rows = JSON.parse(await file.text()) } catch { rows = null }
    if (!Array.isArray(rows) || rows.length === 0) { setNotice({ tone: 'danger', text: t('Soubor musí být neprázdné JSON pole ve tvaru exportu.', 'The file must be a non-empty JSON array in the export shape.') }); return }
    const proposals = rows.map(r => { const o = r as Record<string, unknown>; return { key: o.key, validFrom: o.validFrom, validTo: o.validTo ?? null, payload: o.payload, reason: o.reason ?? 'import' } })
    const res = await wbPost(wbUrl(`/reference/${spec.kind}/import`), proposals, z.array(referenceRecordSchema))
    if (res.ok) { setNotice({ tone: 'success', text: t(`Import: ${res.data.length} návrhů čeká na schválení.`, `Import: ${res.data.length} proposals are waiting for approval.`) }); onDone() }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Import', 'Import'), t) })
  }
  return (
    <Section title={t('Import a export', 'Import and export')} id="ref-io">
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
        <button type="button" className="btn btn-secondary btn-sm" onClick={() => void exportKind()}>{t('Exportovat (JSON)', 'Export (JSON)')}</button>
        <Field label={t('Importovat soubor (JSON z exportu)', 'Import a file (JSON from an export)')}>
          <input className="input" type="file" accept=".json,application/json" disabled={!canPropose} onChange={e => { const f = e.target.files?.[0]; if (f) void importKind(f) }} />
        </Field>
      </div>
      <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>{t('Import vytvoří návrhy; nic se neaktivuje bez schválení jinou osobou. Jedna chybná položka odmítne celý import.', 'Import creates proposals; nothing activates without a different person’s approval. One invalid item rejects the whole import.')}</p>
    </Section>
  )
}

function UserActivity() {
  const { t, dateTime } = useFmt()
  const [r, setR] = useState<WbRead<z.infer<typeof userActivitySchema>> | null>(null)
  useEffect(() => { void (async () => setR(await wbGet(wbUrl('/reference/user-activity'), userActivitySchema)))() }, [])
  return (
    <Section title={t('Aktivita uživatelů', 'User activity')} id="ref-activity">
      {r === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
      {r && !r.ok && <ReadFailure result={r} what={t('Aktivita uživatelů', 'User activity')} />}
      {r?.ok && (r.data.length === 0 ? <p style={{ fontSize: 13 }}>{t('Žádná aktivita.', 'No activity.')}</p> : (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Aktivita uživatelů', 'User activity')}</caption>
          <thead><tr>{[t('Čas', 'When'), t('Kdo', 'Who'), t('Akce', 'Action'), t('Druh', 'Kind'), t('Záznam', 'Record'), t('Verze', 'Version')].map(h => <th key={h} scope="col" style={th}>{h}</th>)}</tr></thead>
          <tbody>{r.data.map((a, i) => <tr key={i} style={{ borderTop: '1px solid var(--border)' }}><td style={td}>{dateTime(a.at)}</td><td style={td}>{a.actor}</td><td style={td}>{a.action}</td><td style={td}>{KIND_BY_ID[a.kind] ? kindName(KIND_BY_ID[a.kind], t) : a.kind}</td><td style={td}>{a.key}</td><td style={td}>{a.version}</td></tr>)}</tbody>
        </table>
      ))}
    </Section>
  )
}

function VersionHistory() {
  const { t, dateTime } = useFmt()
  const [r, setR] = useState<WbRead<z.infer<typeof versionHistorySchema>> | null>(null)
  useEffect(() => { void (async () => setR(await wbGet(wbUrl('/reference/application-version-history'), versionHistorySchema)))() }, [])
  return (
    <Section title={t('Historie verzí aplikace', 'Application version history')} id="ref-versions">
      {r === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
      {r && !r.ok && <ReadFailure result={r} what={t('Historie verzí', 'The version history')} />}
      {r?.ok && (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Historie verzí aplikace', 'Application version history')}</caption>
          <thead><tr>{[t('Verze', 'Version'), t('Popis', 'Description'), t('Nasazeno', 'Installed')].map(h => <th key={h} scope="col" style={th}>{h}</th>)}</tr></thead>
          <tbody>{r.data.map((v, i) => <tr key={i} style={{ borderTop: '1px solid var(--border)' }}><td style={td}>{v.version}</td><td style={td}>{v.description}</td><td style={td}>{dateTime(v.installedOn)}</td></tr>)}</tbody>
        </table>
      )}
    </Section>
  )
}
