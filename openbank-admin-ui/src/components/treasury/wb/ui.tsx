// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Small building blocks shared by every workbench page: the explicit "not yet available on this
// deployment" state, a labelled field with its error, a section card, a name-first reference, a
// notice, and number/date formatting. Nothing here fetches.
import { cloneElement, useEffect, useId, useMemo, useState, type CSSProperties, type ReactElement, type ReactNode } from 'react'
import { CircleSlash } from 'lucide-react'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { wbGet, wbUrl, type Refusal, type WbRead, type WbWrite } from './client'
import { referenceListSchema, type ReferenceRecord } from './types'

export function useFmt() {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  return useMemo(() => ({
    t, language, locale,
    money: (v: number | null | undefined) => (v === null || v === undefined || !Number.isFinite(v) ? '—' : v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })),
    num: (v: number | null | undefined, digits = 4) => (v === null || v === undefined || !Number.isFinite(v) ? '—' : v.toLocaleString(locale, { maximumFractionDigits: digits })),
    dateTime: (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleString(locale) : '—'),
  }), [t, language, locale])
}

/** The explicit state for an endpoint this deployment does not serve yet — never a blank page. */
export function NotAvailable({ what }: { what: string }) {
  const { t } = useLanguage()
  return (
    <div role="note" className="card" data-testid="not-available" style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
      <CircleSlash size={22} aria-hidden="true" style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
      <div style={{ fontSize: 13 }}>
        <strong>{t('Zatím není dostupné v tomto nasazení', 'Not yet available on this deployment')}</strong>
        <div style={{ color: 'var(--text-secondary)', marginTop: 4 }}>
          {t(`${what} služba v tomto prostředí zatím nenabízí. Nic se nezobrazuje vymyšleného — stránka se naplní, jakmile bude funkce nasazena.`,
            `${what} is not served by the service in this environment yet. Nothing is invented here — the page fills in once the capability is deployed.`)}
        </div>
      </div>
    </div>
  )
}

/** Renders the right state for a failed read: not-available, forbidden, or the shared unavailable panel. */
export function ReadFailure({ result, what, service = 'treasury-service' }: { result: Extract<WbRead<unknown>, { ok: false }>; what: string; service?: string }) {
  const { t, language } = useLanguage()
  if (result.kind === 'not_available') return <NotAvailable what={what} />
  if (result.kind === 'forbidden') {
    return <div role="alert" className="card" style={{ fontSize: 13 }}>{t(`${what}: nemáte oprávnění tato data číst.`, `${what}: you are not permitted to read this data.`)}</div>
  }
  return <DataUnavailable kind={result.kind === 'unauthorized' ? 'unauthorized' : result.kind} service={service} feature={what} lang={language} />
}

export function Section({ title, children, actions, id }: { title: ReactNode; children: ReactNode; actions?: ReactNode; id?: string }) {
  return (
    <section className="card" style={{ marginBottom: 16, overflowX: 'auto' }} aria-labelledby={id ? `${id}-h` : undefined} id={id}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, marginBottom: 8, flexWrap: 'wrap' }}>
        <h2 id={id ? `${id}-h` : undefined} style={{ fontSize: 14, fontWeight: 600 }}>{title}</h2>
        {actions}
      </div>
      {children}
    </section>
  )
}

export const fieldStyle: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }
export const gridStyle: CSSProperties = { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12 }

/** A labelled control with its field-level error wired by htmlFor / aria-describedby. The single
 * child control receives the generated id, so the visible label is its accessible name. */
export function Field({ label, error, hint, children }: { label: string; error?: string | null; hint?: ReactNode; children: ReactElement<{ id?: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean }> }) {
  const id = useId()
  const describedBy = [error ? `${id}-err` : null, hint ? `${id}-hint` : null].filter(Boolean).join(' ') || undefined
  return (
    <div style={fieldStyle}>
      <label htmlFor={id}>{label}</label>
      {cloneElement(children, { id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {hint && <span id={`${id}-hint`} style={{ color: 'var(--text-tertiary)' }}>{hint}</span>}
      {error && <span id={`${id}-err`} role="alert" data-field-error style={{ color: 'var(--danger-text)' }}>{error}</span>}
    </div>
  )
}

export function Notice({ tone, children }: { tone: 'success' | 'danger' | 'warning'; children: ReactNode }) {
  const color = tone === 'danger' ? 'var(--danger)' : tone === 'warning' ? 'var(--warning)' : 'var(--success)'
  return <div role={tone === 'danger' ? 'alert' : 'status'} className="card" style={{ marginBottom: 16, borderColor: color, fontSize: 13 }}>{children}</div>
}

/** Name first, id second and small (open issues #11713/#11715). */
export function NameWithId({ name, id }: { name?: string | null; id: string }) {
  const primary = name && name !== id ? name : null
  if (!primary) return <span className="mono" title={id}>{id}</span>
  return (
    <span style={{ display: 'inline-flex', flexDirection: 'column', lineHeight: 1.25 }}>
      <span>{primary}</span>
      <span className="mono" title={id} style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{id}</span>
    </span>
  )
}

export const th: CSSProperties = { textAlign: 'left', padding: '4px 8px', whiteSpace: 'nowrap' }
export const td: CSSProperties = { padding: '4px 8px', verticalAlign: 'top' }
export const tdNum: CSSProperties = { ...td, textAlign: 'right', whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }

/** Human message for a refused write (kept readable; the code is secondary). */
export function writeFailureText(res: Extract<WbWrite<unknown>, { ok: false }>, action: string, t: (cs: string, en: string) => string): string {
  const detail = res.message ? ` — ${res.message}` : ''
  if (res.kind === 'not_available') return t(`${action}: tuto akci toto nasazení zatím nenabízí.`, `${action}: this deployment does not offer this action yet.`)
  if (res.kind === 'forbidden') return t(`${action}: nemáte oprávnění k tomuto kroku.`, `${action}: you are not permitted to take this step.`)
  if (res.kind === 'unavailable') return t(`${action}: treasury-service je nedostupný.`, `${action}: treasury-service is unavailable.`)
  switch (res.code) {
    case 'FOUR_EYES_VIOLATION': return t(`${action}: schválit musí jiná osoba než navrhovatel${detail}`, `${action}: a different person from the proposer must decide${detail}`)
    case 'SENIOR_APPROVAL_REQUIRED': return t(`${action}: zvýšení limitu musí schválit senior schvalovatel${detail}`, `${action}: a raised limit needs a senior approver${detail}`)
    case 'ACTOR_NOT_PERMITTED': return t(`${action}: tento krok smí provést jen člověk${detail}`, `${action}: only a person may take this step${detail}`)
    case 'REFERENCE_NOT_EFFECTIVE': return t(`${action}: odkazovaná referenční data nejsou k datu obchodu platná${detail}`, `${action}: referenced reference data is not effective on the trade date${detail}`)
    case 'REFERENCE_BACKDATED': return t(`${action}: protistrana ani SSI nesmí začínat před dneškem${detail}`, `${action}: a counterparty or SSI version may not start before today${detail}`)
    case 'PRODUCT_LIMIT_BREACHED': return t(`${action}: obchod je mimo produktový limit${detail}`, `${action}: outside the product limit${detail}`)
    case 'INVALID_STATE': return t(`${action}: položka je v jiném stavu, obnovte stránku${detail}`, `${action}: the item is in a different state, refresh${detail}`)
    default: return t(`${action}: žádost byla odmítnuta${detail}`, `${action}: the request was refused${detail}`)
  }
}
export type { Refusal }

/** Active reference records of one kind as select options; null while loading or when not served. */
export function useRefOptions(kind: string, asOf?: string): { options: { key: string; label: string }[] | null; loaded: boolean } {
  const [state, setState] = useState<{ options: { key: string; label: string }[] | null; loaded: boolean }>({ options: null, loaded: false })
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const res = await wbGet(wbUrl(`/reference/${kind}`, { status: 'ACTIVE', asOf }), referenceListSchema)
      if (cancelled) return
      setState(res.ok
        ? { options: dedupe(res.data).map(r => ({ key: r.key, label: recordLabel(r) })), loaded: true }
        : { options: null, loaded: true })
    })()
    return () => { cancelled = true }
  }, [kind, asOf])
  return state
}

function dedupe(rows: ReferenceRecord[]): ReferenceRecord[] {
  const seen = new Map<string, ReferenceRecord>()
  for (const r of rows) if (!seen.has(r.key) || (seen.get(r.key)?.version ?? 0) < r.version) seen.set(r.key, r)
  return [...seen.values()]
}

/** Display name of a reference record: payload.name / label, else the key. */
export function recordLabel(r: Pick<ReferenceRecord, 'key' | 'payload'>): string {
  const p = r.payload
  const name = typeof p.name === 'string' ? p.name : typeof p.label === 'string' ? p.label : null
  return name && name !== r.key ? `${name} (${r.key})` : r.key
}

/** Trigger a browser download of text content. */
export function downloadText(filename: string, text: string, type = 'text/csv') {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

export function csvCell(v: unknown): string {
  const s = v === null || v === undefined ? '' : String(v)
  return /[",\n;]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}
