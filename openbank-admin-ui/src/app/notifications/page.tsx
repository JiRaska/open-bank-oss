// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Suspense, useState, useEffect, useCallback } from 'react'
import { useSearchParams } from 'next/navigation'
import { Bell, RefreshCw, Mail, AlertTriangle, CheckCircle2, Info, Clock, Check, X } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { useAuth } from '@/lib/auth/useAuth'
import { hasPermission } from '@/lib/auth/roles'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { opsMessageApi } from '@/lib/api'
import { PageHeader } from '@/components/ui/PageHeader'
import { AuthGuard } from '@/components/auth/AuthGuard'

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

function NotificationsContent() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
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
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Oznámení', 'Notifications')}</span></div>}
        icon={<Bell size={18} aria-hidden="true" />}
        title={t('Oznámení', 'Notifications')}
        subtitle={t('Odchozí oznámení — e-maily, upozornění, webhooky', 'Outbound notification log — emails, alerts, webhooks')}
        actions={<button
          className="btn btn-secondary"
          type="button"
          onClick={load}
          disabled={loading}
          aria-busy={loading}
          aria-label={t('Obnovit oznámení', 'Refresh notifications')}
        >
          <RefreshCw aria-hidden="true" size={13} style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
          {t('Obnovit', 'Refresh')}
        </button>}
      />

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

      {canApprove && <Suspense fallback={null}><OperatorMessageApprovals /></Suspense>}

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
                  <td><div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}><Icon aria-hidden="true" size={13} style={{ color: 'var(--accent)' }} /><span className="tag">{n.type}</span></div></td>
                  <td><span className="tag">{n.channel}</span></td>
                  <td style={{ fontSize: '12px', fontFamily: 'var(--font-mono)' }}>{n.recipient}</td>
                  <td style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>{n.subject ?? '—'}</td>
                  <td>
                    <span className="pill" style={{ background: `${STATUS_COLOR[n.status] ?? 'var(--text-muted)'}22`, color: STATUS_COLOR[n.status] ?? 'var(--text-muted)' }}>
                      {n.status}
                    </span>
                  </td>
                  <td style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                    {n.sentAt ? new Date(n.sentAt).toLocaleString(dateLocale) : '—'}
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

export default function NotificationsPage() {
  return <AuthGuard permission="notifications:view"><NotificationsContent /></AuthGuard>
}

/**
 * Four-eyes checker surface for operator-initiated messages (ADR-0176 D5). A maker's compose
 * call (`POST /api/v1/notifications/messages`) is paused with 202 + a pending-approval id when
 * `AUTHZ_FOUR_EYES_ENFORCE=true`; a DIFFERENT operator decides it here via the single
 * `PATCH /api/v1/notifications/approvals/{id}` endpoint. SelfApprovalNotAllowedException refuses
 * a maker deciding their own request server-side (403).
 *
 * The unified Approval Centre discovers pending notification approvals through the backend list
 * endpoint and deep-links here with the selected approval id. Direct id entry remains available
 * for operational recovery and does not weaken the server-side maker-checker boundary.
 */
function OperatorMessageApprovals() {
  const { t } = useLanguage()
  const searchParams = useSearchParams()
  const [approvalId, setApprovalId] = useState(() => searchParams.get('approvalId')?.trim() ?? '')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null)

  const decide = async (approve: boolean) => {
    const id = approvalId.trim()
    if (!id) return
    setBusy(true); setResult(null)
    try {
      const decision = await opsMessageApi.decide(id, approve)
      setResult({ ok: true, text: `${t('Rozhodnutí uloženo', 'Decision recorded')}: ${decision.status}` })
      setApprovalId('')
    } catch {
      // Most often SelfApprovalNotAllowedException / already-decided — never surface the raw
      // backend message for a user-initiated write (graceful-state rule).
      setResult({ ok: false, text: t(
        'Rozhodnutí se nezdařilo. Zkontrolujte ID schválení — jiný operátor už možná rozhodl, nebo jste zprávu vytvořili vy.',
        'The decision failed. Check the approval id — another operator may have already decided it, or you composed this message yourself.',
      ) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div id="message-approvals" className="card" style={{ overflow: 'hidden', marginBottom: '16px', scrollMarginTop: '16px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '16px 20px', borderBottom: '1px solid var(--border)' }}>
        <Clock aria-hidden="true" size={15} style={{ color: 'var(--yellow)' }} />
        <span style={{ fontWeight: 600, fontSize: '13px' }}>
          {t('Schválení zpráv (princip čtyř očí)', 'Message approvals (four-eyes)')}
        </span>
      </div>
      <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: '10px' }}>
        <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
          {t(
            'Vyberte čekající notifikaci v Centru schvalování; její ID se zde doplní automaticky. Pro provozní obnovu můžete ID zadat ručně. Vlastní zprávu schválit nelze.',
            'Select a pending notification in the Approval Centre and its ID will be filled in here. You can enter an ID manually for operational recovery. You cannot approve your own message.',
          )}
        </span>
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
          <input
            aria-label={t('ID schválení notifikace', 'Notification approval ID')}
            className="input"
            style={{ flex: 1, minWidth: '240px', fontFamily: 'var(--font-mono)' }}
            value={approvalId}
            onChange={e => setApprovalId(e.target.value)}
            placeholder={t('ID schválení', 'Approval id')}
          />
          <button
            className="btn btn-secondary"
            style={{ color: 'var(--green)' }}
            onClick={() => decide(true)}
            disabled={busy || !approvalId.trim()}
          >
            <Check aria-hidden="true" size={13} /> {t('Schválit', 'Approve')}
          </button>
          <button
            className="btn btn-secondary"
            style={{ color: 'var(--red)' }}
            onClick={() => decide(false)}
            disabled={busy || !approvalId.trim()}
          >
            <X aria-hidden="true" size={13} /> {t('Zamítnout', 'Reject')}
          </button>
        </div>
        {result && (
          <span style={{ fontSize: '12px', color: result.ok ? 'var(--green)' : 'var(--red)' }}>{result.text}</span>
        )}
      </div>
    </div>
  )
}
