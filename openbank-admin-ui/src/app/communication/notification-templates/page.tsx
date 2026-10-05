// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { Bell, RefreshCw } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui/PageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'

type Template = 'ACCOUNT_OPENED' | 'ACCOUNT_CLOSED' | 'TRANSACTION_COMPLETED' | 'TRANSACTION_FAILED'
type Language = 'CS' | 'EN'
type Channel = 'EMAIL' | 'PUSH' | 'INBOX'
type Revision = {
  id: string
  template: Template
  language: Language
  channel: Channel
  revision: number
  subject: string
  body: string
  state: 'DRAFT' | 'PUBLISHED'
  createdBy: string
  createdAt: string
  publishedBy: string | null
  publishedAt: string | null
}

const BASE = '/api/svc/notification-service/api/v1/notification-templates'
const VARIABLES: Record<Template, string[]> = {
  ACCOUNT_OPENED: ['accountNumber'],
  ACCOUNT_CLOSED: ['accountNumber'],
  TRANSACTION_COMPLETED: ['amount', 'currency'],
  TRANSACTION_FAILED: ['amount', 'currency', 'reason'],
}
const SAMPLE: Record<string, string> = {
  accountNumber: 'CZ00••••1234', amount: '100.00', currency: 'CZK', reason: 'Sample reason',
}
const TEMPLATES = Object.keys(VARIABLES) as Template[]

export default function NotificationTemplatesPage() {
  const { t } = useLanguage()
  const [template, setTemplate] = useState<Template>('TRANSACTION_COMPLETED')
  const [language, setLanguage] = useState<Language>('CS')
  const [channel, setChannel] = useState<Channel>('EMAIL')
  const [subject, setSubject] = useState('')
  const [body, setBody] = useState('')
  const [revisions, setRevisions] = useState<Revision[]>([])
  const [preview, setPreview] = useState<{ subject: string; body: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const response = await fetch(`${BASE}?template=${encodeURIComponent(template)}`, { cache: 'no-store' })
      if (!response.ok) throw new Error(t('Služba šablon není dostupná.', 'Template service is unavailable.'))
      const rows = await response.json() as Revision[]
      setRevisions(rows)
      setError(null)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('Načtení selhalo.', 'Loading failed.'))
    }
  }, [template, t])

  useEffect(() => {
    const timer = window.setTimeout(() => { void load() }, 0)
    return () => window.clearTimeout(timer)
  }, [load])

  const draft = { template, language, channel, subject, body }
  const perform = async (path: string, payload?: unknown) => {
    setBusy(true)
    setError(null)
    try {
      const response = await fetch(`${BASE}${path}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload ?? {}),
      })
      if (!response.ok) {
        const detail = await response.json().catch(() => null) as { error?: string; message?: string } | null
        throw new Error(detail?.error ?? detail?.message ?? `${response.status}`)
      }
      const result = await response.json() as Revision | { subject: string; body: string }
      if (path === '/preview') setPreview(result as { subject: string; body: string })
      else await load()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('Akce selhala.', 'Action failed.'))
    } finally {
      setBusy(false)
    }
  }

  return <AuthGuard permission="communication:templates:manage">
    <div className="page">
      <PageHeader
        icon={<Bell size={22} />}
        title={t('Šablony zákaznických zpráv', 'Customer message templates')}
        subtitle={t('Verzované texty běžných notifikací. Identitu, proměnné a doručovací pravidla vlastní kód.',
          'Versioned copy for routine notifications. Code owns identities, variables and delivery rules.')}
        breadcrumb={<div className="breadcrumb"><Link href="/communication">{t('Komunikace', 'Communication')}</Link><span className="breadcrumb-sep">/</span><span>{t('Šablony', 'Templates')}</span></div>}
        actions={<button type="button" className="btn btn-secondary" onClick={() => void load()} disabled={busy}><RefreshCw size={15} /> {t('Obnovit', 'Refresh')}</button>}
      />
      {error && <div role="alert" className="card" style={{ color: 'var(--danger)' }}>{error}</div>}
      <div className="card" style={{ marginBottom: 16 }}>
        <div className="grid-4" style={{ gap: 12 }}>
          <label>{t('Typ', 'Template')}<select value={template} onChange={event => { setTemplate(event.target.value as Template); setPreview(null) }}>{TEMPLATES.map(value => <option key={value} value={value}>{value}</option>)}</select></label>
          <label>{t('Jazyk', 'Language')}<select value={language} onChange={event => { setLanguage(event.target.value as Language); setPreview(null) }}><option value="CS">Čeština</option><option value="EN">English</option></select></label>
          <label>{t('Kanál', 'Channel')}<select value={channel} onChange={event => { setChannel(event.target.value as Channel); setPreview(null) }}><option value="EMAIL">Email</option><option value="PUSH">Push</option><option value="INBOX">Inbox</option></select></label>
        </div>
        <p className="text-xs text-muted-foreground">{t('Povinné proměnné', 'Required variables')}: {VARIABLES[template].map(variable => `{{${variable}}}`).join(', ')}</p>
        <label className="block">{t('Předmět bez osobních údajů', 'Subject without personal data')}<input value={subject} maxLength={120} onChange={event => { setSubject(event.target.value); setPreview(null) }} /></label>
        <label className="block">{t('Text zprávy', 'Message body')}<textarea value={body} maxLength={2000} rows={5} onChange={event => { setBody(event.target.value); setPreview(null) }} /></label>
        <p className="text-xs text-muted-foreground">{t('Jen prostý text. Odkazy a HTML nejsou povoleny.', 'Plain text only. Links and HTML are not allowed.')}</p>
        <div className="flex gap-2">
          <button type="button" className="btn btn-secondary" disabled={busy} onClick={() => void perform('/preview', { draft, variables: Object.fromEntries(VARIABLES[template].map(key => [key, SAMPLE[key]])) })}>{t('Náhled', 'Preview')}</button>
          <button type="button" className="btn btn-primary" disabled={busy || !preview} onClick={() => void perform('', draft)}>{t('Uložit návrh', 'Save draft')}</button>
        </div>
        {preview && <div className="card" aria-label={t('Náhled zprávy', 'Message preview')}><strong>{preview.subject}</strong><pre className="whitespace-pre-wrap">{preview.body}</pre></div>}
      </div>
      <div className="card">
        <h2>{t('Revize', 'Revisions')}</h2>
        {revisions.length === 0 && <p>{t('Žádná revize.', 'No revisions yet.')}</p>}
        {revisions.map(revision => <div key={revision.id} className="border-b py-3">
          <div className="flex items-center justify-between"><strong>#{revision.revision} · {revision.language} · {revision.channel}</strong><span>{revision.state}</span></div>
          <p>{revision.subject}</p><p className="text-xs text-muted-foreground">{revision.body}</p>
          <p className="text-xs text-muted-foreground">{t('Vytvořil', 'Created by')}: {revision.createdBy}{revision.publishedBy ? ` · ${t('Publikoval', 'Published by')}: ${revision.publishedBy}` : ''}</p>
          {revision.state === 'DRAFT' && <button type="button" className="btn btn-secondary" disabled={busy} onClick={() => void perform(`/${revision.id}/publish`)}>{t('Publikovat jako druhý operátor', 'Publish as a different operator')}</button>}
        </div>)}
      </div>
    </div>
  </AuthGuard>
}
