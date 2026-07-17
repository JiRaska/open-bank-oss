// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import { Bell, RefreshCw, Mail, AlertTriangle, CheckCircle2, Info, Clock, Check, X } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { useAuth } from '@/lib/auth/useAuth'
import { hasPermission } from '@/lib/auth/roles'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { opsMessageApi } from '@/lib/api'

const NOTIFICATION_SERVICE = '/api/svc/notification-service'

interface Notification {
  id: string; type: string; channel: string; recipient: string
  subject?: string; status: string; sentAt?: string; createdAt: string
  payload?: Record<string, unknown>
}

const TYPE_ICON: Record<string, React.ElementType> = {
  EMAIL: Mail, ALERT: AlertTriangle, SUCCESS: CheckCircle2, INFO: Info,
}
const STATUS_COLOR: Record<string, string> = {
  SENT: 'var(--green)', FAILED: 'var(--red)', PENDING: 'var(--yellow)', QUEUED: 'var(--accent)',
}

export default function NotificationsPage() {
  const { t, language } = useLanguage()
  const { roles } = useAuth()
  const canApprove = hasPermission(roles, 'opsmessage:approve')
  const [items, setItems]     = useState<Notification[]>([])
  const [loading, setLoading] = useState(true)
  // Typed unavailable reason → renders the calm <DataUnavailable> panel instead
  // of leaking a raw "HTTP 404" string (admin-ui graceful-state rule).
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setUnavailable(null)
    try {
      const res = await fetch(`${NOTIFICATION_SERVICE}/api/v1/notifications`, { signal: AbortSignal.timeout(5000) })
      if (!res.ok) {
        const kind = await classifyBffFailure(res)
        setItems([])
        // A genuine 404/405 on the log endpoint means "no notifications yet",
        // not a broken app — degrade to the calm empty state.
        setUnavailable({ kind: res.status === 405 || kind === 'not_found' ? 'no_data' : kind })
        return
      }
      const data = await res.json()
      setItems(Array.isArray(data) ? data : data.items ?? [])
    } catch {
      // Timeout / abort / network — the BFF or notification-service didn't answer.
      setItems([])
      setUnavailable({ kind: 'unreachable' })
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const sentCount   = items.filter(n => n.status === 'SENT').length
  const failedCount = items.filter(n => n.status === 'FAILED').length
  const pendingCount = items.filter(n => n.status === 'PENDING' || n.status === 'QUEUED').length

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Oznámení', 'Notifications')}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Bell size={18} style={{ color: 'var(--accent)' }} />
            {t('Oznámení', 'Notifications')}
          </h1>
          <p className="page-subtitle">{t('Odchozí oznámení — e-maily, upozornění, webhooky', 'Outbound notification log — emails, alerts, webhooks')}</p>
        </div>
        <button className="btn btn-secondary" onClick={load} disabled={loading}>
          <RefreshCw size={13} style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
          {t('Obnovit', 'Refresh')}
        </button>
      </div>

      {/* Stats */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '12px', marginBottom: '20px' }}>
        {[
          { label: t('Odesláno', 'Sent'), value: sentCount, color: 'var(--green)' },
          { label: t('Selhalo', 'Failed'), value: failedCount, color: 'var(--red)' },
          { label: t('Čeká / Ve frontě', 'Pending / Queued'), value: pendingCount, color: 'var(--yellow)' },
        ].map(s => (
          <div key={s.label} className="stat-card">
            <div className="stat-value" style={{ color: s.color }}>{loading ? '—' : s.value}</div>
            <div className="stat-label">{s.label}</div>
          </div>
        ))}
      </div>

      {canApprove && <DecideApproval />}

      {unavailable && (
        <div className="card" style={{ padding: 0, marginBottom: '16px' }}>
          <DataUnavailable
            kind={unavailable.kind}
            service={t('Notification-service', 'Notification-service')}
            feature={t('Notifikace', 'Notifications')}
            lang={language}
            dense
          />
        </div>
      )}

      <div className="card" style={{ overflow: 'hidden' }}>
        <table className="data-table">
          <thead>
            <tr>
              <th>{t('Typ', 'Type')}</th>
              <th>{t('Kanál', 'Channel')}</th>
              <th>{t('Příjemce', 'Recipient')}</th>
              <th>{t('Předmět', 'Subject')}</th>
              <th>{t('Stav', 'Status')}</th>
              <th>{t('Odesláno', 'Sent At')}</th>
            </tr>
          </thead>
          <tbody>
            {loading && Array.from({ length: 5 }).map((_, i) => (
              <tr key={i}>{Array.from({ length: 6 }).map((_, j) => <td key={j}><div className="skeleton" style={{ height: '14px', width: j === 2 ? '160px' : '80px' }} /></td>)}</tr>
            ))}
            {!loading && !unavailable && items.length === 0 && (
              <tr><td colSpan={6} style={{ padding: 0 }}>
                <DataUnavailable
                  kind="no_data"
                  feature={t('Notifikace', 'Notifications')}
                  lang={language}
                  detail={t('Žádné notifikace nenalezeny.', 'No notifications found.')}
                  dense
                />
              </td></tr>
            )}
            {!loading && items.map(n => {
              const Icon = TYPE_ICON[n.type] ?? Bell
              return (
                <tr key={n.id}>
                  <td><div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}><Icon size={13} style={{ color: 'var(--accent)' }} /><span className="tag">{n.type}</span></div></td>
                  <td><span className="tag">{n.channel}</span></td>
                  <td style={{ fontSize: '12px', fontFamily: 'var(--font-mono)' }}>{n.recipient}</td>
                  <td style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>{n.subject ?? '—'}</td>
                  <td>
                    <span className="pill" style={{ background: `${STATUS_COLOR[n.status] ?? 'var(--text-muted)'}22`, color: STATUS_COLOR[n.status] ?? 'var(--text-muted)' }}>
                      {n.status}
                    </span>
                  </td>
                  <td style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                    {n.sentAt ? new Date(n.sentAt).toLocaleString() : '—'}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/**
 * Decide a pending operator-message approval (ADR-0176 D5, #1368). There is no list-pending
 * endpoint — ApprovalStore itself cannot be listed (ApprovalResource's own KDoc), and #1368
 * did not add a persisted-draft table an earlier, superseded design did. A checker therefore
 * needs the approval id told to them out-of-band by the maker (chat, verbally); this is a
 * decide-by-id form, not a browsable queue.
 *
 * A DIFFERENT operator than the one who composed the message must decide it —
 * ApprovalStore.decide refuses it server-side if the same operator tries (self-approval). This
 * form doesn't try to guess who composed which message, it just surfaces that rejection as a
 * plain error if it happens.
 */
function DecideApproval() {
  const { t } = useLanguage()
  const [approvalId, setApprovalId] = useState('')
  const [busy, setBusy] = useState<'approve' | 'reject' | null>(null)
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null)

  const decide = async (approve: boolean) => {
    const id = approvalId.trim()
    if (!id) return
    setBusy(approve ? 'approve' : 'reject')
    setResult(null)
    try {
      const decided = await opsMessageApi.decide(id, approve)
      setResult({
        ok: true,
        message: approve
          ? t('Schváleno. Autor teď může zprávu znovu odeslat.', 'Approved. The maker can now retry sending it.')
          : t('Zamítnuto — zpráva se neodešle.', 'Rejected — the message will not be sent.'),
      })
      void decided
      setApprovalId('')
    } catch {
      // Most often "no pending approval with id=..." (already decided, or a typo) or a
      // self-approval refusal — never surface the raw backend message for a user-initiated write.
      setResult({
        ok: false,
        message: t(
          'Rozhodnutí se nezdařilo. Zkontrolujte approvalId — buď už bylo rozhodnuto, nebo jste zprávu sami vytvořili.',
          'The decision failed. Check the approval id — it may already be decided, or you composed this message yourself.',
        ),
      })
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="card" style={{ padding: '16px 20px', marginBottom: '16px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
        <Clock size={15} style={{ color: 'var(--yellow)' }} />
        <span style={{ fontWeight: 600, fontSize: '13px' }}>
          {t('Rozhodnout o schválení zprávy', 'Decide a message approval')}
        </span>
      </div>
      <p style={{ fontSize: '12px', color: 'var(--text-muted)', margin: '0 0 10px' }}>
        {t(
          'Autor zprávy vám sdělí approvalId (mimo tuto appku). Zde ho vložte a rozhodněte.',
          'The message\'s author gives you the approvalId out of band. Paste it here and decide.',
        )}
      </p>
      <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
        <input
          type="text"
          value={approvalId}
          onChange={e => setApprovalId(e.target.value)}
          placeholder="approvalId"
          style={{ flex: 1, fontSize: '12px', fontFamily: 'var(--font-mono)', padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-primary)' }}
        />
        <button
          className="btn btn-secondary"
          style={{ color: 'var(--green)' }}
          onClick={() => decide(true)}
          disabled={!approvalId.trim() || busy !== null}
        >
          <Check size={13} /> {busy === 'approve' ? t('Schvaluji…', 'Approving…') : t('Schválit', 'Approve')}
        </button>
        <button
          className="btn btn-secondary"
          style={{ color: 'var(--red)' }}
          onClick={() => decide(false)}
          disabled={!approvalId.trim() || busy !== null}
        >
          <X size={13} /> {busy === 'reject' ? t('Zamítám…', 'Rejecting…') : t('Zamítnout', 'Reject')}
        </button>
      </div>
      {result && (
        <div style={{ marginTop: '8px', fontSize: '12px', color: result.ok ? 'var(--green)' : 'var(--red)' }}>
          {result.message}
        </div>
      )}
    </div>
  )
}
