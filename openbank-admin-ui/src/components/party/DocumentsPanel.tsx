// SPDX-License-Identifier: Apache-2.0

'use client'

import { useEffect, useState } from 'react'
import { Download, FileText, HelpCircle } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { StatusBadge } from '@/components/ui'
import { classifyBffFailure, svcUrl, type BffFailure } from '@/lib/services/bff'

interface PartyDocument {
  id: string; templateCode: string; templateVersion: string; contentType: string
  sizeBytes: number; status: string; caseRef: string | null; productRef: string | null
  retainUntil: string | null; createdAt: string
}

type State = { kind: 'loading' } | { kind: 'ok'; documents: PartyDocument[] } | { kind: 'unknown'; why: BffFailure }

export function DocumentsPanel({ partyId }: { partyId: string }) {
  const { t, language } = useLanguage()
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    let live = true
    ;(async () => {
      try {
        const res = await fetch(svcUrl('document-service', '/api/v1/documents', { partyRef: partyId }), { cache: 'no-store' })
        if (!res.ok) {
          const why = await classifyBffFailure(res)
          if (live) setState({ kind: 'unknown', why })
          return
        }
        const body = await res.json() as PartyDocument[]
        if (live) setState({ kind: 'ok', documents: Array.isArray(body) ? body : [] })
      } catch {
        if (live) setState({ kind: 'unknown', why: 'unreachable' })
      }
    })()
    return () => { live = false }
  }, [partyId])

  const fmt = (iso: string) => new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', {
    dateStyle: 'medium', timeStyle: 'short',
  }).format(new Date(iso))

  return <div className="card" style={{ padding: '16px 20px', marginBottom: 20 }}>
    <h2 className="section-title" style={{ marginBottom: 4 }}>{t('Dokumenty klienta', 'Customer documents')}</h2>
    <p style={{ margin: '0 0 12px', fontSize: 11, color: 'var(--text-secondary)' }}>
      {t('Zdroj: document-service. Stažení obsahu podléhá backendovému oprávnění a auditu.', 'Source: document-service. Content downloads remain subject to backend authorisation and audit.')}
    </p>
    {state.kind === 'loading' && <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Načítám…', 'Loading…')}</span>}
    {state.kind === 'ok' && state.documents.length === 0 && <div style={{ display: 'flex', gap: 8, alignItems: 'center', color: 'var(--text-secondary)', fontSize: 13 }}><FileText size={16} /> {t('Klient nemá žádné dokumenty', 'No documents for this customer')}</div>}
    {state.kind === 'ok' && state.documents.length > 0 && <div style={{ overflowX: 'auto' }}><table className="table">
      <thead><tr><th>{t('Dokument', 'Document')}</th><th>{t('Stav', 'Status')}</th><th>{t('Vytvořeno', 'Created')}</th><th>{t('Vazba', 'Reference')}</th><th>{t('Retence do', 'Retain until')}</th><th /></tr></thead>
      <tbody>{state.documents.map(doc => <tr key={doc.id}>
        <td><div style={{ fontWeight: 600 }}>{doc.templateCode}</div><div className="mono" style={{ fontSize: 10, color: 'var(--text-tertiary)' }}>v{doc.templateVersion} · {Math.ceil(doc.sizeBytes / 1024)} kB</div></td>
        <td><StatusBadge status={doc.status} /></td><td>{fmt(doc.createdAt)}</td>
        <td className="mono" style={{ fontSize: 10 }}>{doc.caseRef ?? doc.productRef ?? '—'}</td><td>{doc.retainUntil ?? '—'}</td>
        <td style={{ textAlign: 'right' }}><a className="btn btn-secondary btn-sm" href={svcUrl('document-service', `/api/v1/documents/${doc.id}/content`)} download><Download size={12} /> {t('Stáhnout', 'Download')}</a></td>
      </tr>)}</tbody>
    </table></div>}
    {state.kind === 'unknown' && <div style={{ display: 'flex', gap: 8, alignItems: 'center', color: 'var(--text-secondary)', fontSize: 13 }}><HelpCircle size={16} /> {t(`Dokumenty nelze zjistit (document-service: ${state.why}).`, `Documents unavailable (document-service: ${state.why}).`)}</div>}
  </div>
}
