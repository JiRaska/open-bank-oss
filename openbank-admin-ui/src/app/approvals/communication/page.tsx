// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Communication Studio — checker workbench (ADR-0285 D3/D6, phase 2).
//
// The commstyle.publish four-eyes queue: a ROLE_COMMS_APPROVER decides here, never on the
// editor page (that page cannot publish its own draft — see communication/edit/[personaKey]).
// Self-approval is refused server-side (CommunicationStyleService.publish, ApprovalStore.decide);
// this page does not try to pre-filter "your own drafts" out of the list, since a checker seeing
// their own pending item is the whole point of the segregation being visible, not hidden.

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, Check, Clock3, RefreshCw, X } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'

interface PendingApproval {
  id: string
  action: string
  resourceId: string | null
  status: string
  makerId: string | null
  createdAt: string | null
  decidedBy: string | null
}

const PROXY_BASE = '/api/svc/communication-service/api/v1/communications/approvals'

export default function CommunicationApprovalsPage() {
  const { t, language } = useLanguage()
  const [items, setItems] = useState<PendingApproval[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setUnavailable(null)
    try {
      const res = await fetch(`${PROXY_BASE}?limit=50`, { cache: 'no-store', signal: AbortSignal.timeout(8000) })
      if (!res.ok) { setUnavailable(await classifyBffFailure(res)); return }
      setItems((await res.json()) as PendingApproval[])
    } catch {
      setUnavailable('unreachable')
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { void load() }, [load])

  const decide = useCallback(async (id: string, approve: boolean) => {
    setBusyId(id); setMessage(null)
    try {
      const res = await fetch(`${PROXY_BASE}/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ approve }),
      })
      if (!res.ok) {
        // Most often SelfApprovalNotAllowedException / already-decided — never surface the raw
        // backend message for a user-initiated write (same rule the notifications page follows).
        setMessage({
          ok: false,
          text: t(
            'Rozhodnutí se nezdařilo. Koncept jste možná vytvořili vy sami, nebo už rozhodnutí padlo.',
            'The decision failed. You may have created this draft yourself, or it was already decided.',
          ),
        })
        return
      }
      setMessage({
        ok: true,
        text: approve
          ? t('Schváleno — navrhovatel teď musí publikaci zopakovat.', 'Approved — the maker must now retry the publish call.')
          : t('Zamítnuto.', 'Rejected.'),
      })
      await load()
    } catch {
      setMessage({ ok: false, text: t('Rozhodnutí se nezdařilo.', 'The decision failed.') })
    } finally { setBusyId(null) }
  }, [load, t])

  return (
    <AuthGuard permission="communication:style:decide">
      <div>
        <PageHeader
          breadcrumb={<div className="breadcrumb"><Link href="/approvals">{t('Schvalování', 'Approvals')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Komunikace', 'Communication')}</span></div>}
          title={t('Schvalování stylu (čtyři oči)', 'Style approvals (four-eyes)')}
          subtitle={t(
            'Fronta čekajících publikací verzí stylu, seřazená od nejstarší. Vlastní koncept nelze schválit.',
            'The pending queue of style version publish requests, oldest first. You cannot approve your own draft.',
          )}
          actions={<Link href="/approvals" className="btn btn-secondary"><ArrowLeft size={14} aria-hidden="true" />{t('Zpět do fronty', 'Back to queue')}</Link>}
        />

        {loading && (
          <div className="card" role="status" aria-live="polite" style={{ padding: 24, marginTop: 16, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: 10 }}>
            <RefreshCw size={16} aria-hidden="true" className="animate-spin" />
            {t('Načítám frontu…', 'Loading queue…')}
          </div>
        )}

        {!loading && unavailable && (
          <div className="card" style={{ marginTop: 16 }}>
            <DataUnavailable kind={unavailable} service="Communication" feature={t('fronta schvalování', 'approval queue')} lang={language}>
              <button type="button" className="btn btn-secondary" onClick={() => void load()}>
                <RefreshCw size={14} aria-hidden="true" />{t('Zkusit znovu', 'Retry')}
              </button>
            </DataUnavailable>
          </div>
        )}

        {!loading && !unavailable && items.length === 0 && (
          <div className="card" style={{ padding: 24, marginTop: 16, color: 'var(--text-secondary)' }}>
            {t('Žádné čekající schválení.', 'No pending approvals.')}
          </div>
        )}

        {!loading && !unavailable && items.length > 0 && (
          <div className="card" style={{ marginTop: 16, overflow: 'hidden' }}>
            <table className="table">
              <thead>
                <tr>
                  <th><Clock3 size={13} aria-hidden="true" /> {t('Vytvořeno', 'Created')}</th>
                  <th>{t('Akce', 'Action')}</th>
                  <th>{t('Navrhl', 'Maker')}</th>
                  <th>{t('Rozhodnutí', 'Decision')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map(item => (
                  <tr key={item.id}>
                    <td>{item.createdAt ? new Date(item.createdAt).toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'medium', timeStyle: 'short' }) : '—'}</td>
                    <td className="mono">{item.action}</td>
                    <td>{item.makerId ?? '—'}</td>
                    <td>
                      <div style={{ display: 'flex', gap: '6px' }}>
                        <button className="btn btn-secondary" style={{ color: 'var(--green)' }}
                          onClick={() => decide(item.id, true)} disabled={busyId === item.id}>
                          <Check size={13} aria-hidden="true" /> {t('Schválit', 'Approve')}
                        </button>
                        <button className="btn btn-secondary" style={{ color: 'var(--red)' }}
                          onClick={() => decide(item.id, false)} disabled={busyId === item.id}>
                          <X size={13} aria-hidden="true" /> {t('Zamítnout', 'Reject')}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {message && (
          <p style={{ marginTop: '12px', fontSize: '13px', color: message.ok ? 'var(--green)' : 'var(--red)' }}>{message.text}</p>
        )}
      </div>
    </AuthGuard>
  )
}
