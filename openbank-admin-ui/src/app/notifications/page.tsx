// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import { Bell, RefreshCw, Mail, AlertTriangle, CheckCircle2, Info } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'

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
