// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { Bell, RefreshCw, Mail, AlertTriangle, CheckCircle2, Info, Clock, Check, X } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { useAuth } from '@/lib/auth/useAuth'
import { hasPermission } from '@/lib/auth/roles'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { opsMessageApi } from '@/lib/api'
import { PageHeader, StatusBadge } from '@/components/ui'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { readApprovalId } from '@/lib/approvals/triage'

const NOTIFICATION_SERVICE = '/api/svc/notification-service'

interface Notification {
  id: string; template: string; channel: string; recipient: string
  subject?: string | null; status: string; sentAt?: string | null; createdAt: string
}

interface NotificationPage {
  items: Notification[]
  total: number
  page: number
  size: number
}

const PAGE_SIZE = 20

const TEMPLATE_ICON: Record<string, React.ElementType> = {
  ACCOUNT_OPENED: CheckCircle2,
  TRANSACTION_COMPLETED: CheckCircle2,
  KYC_APPROVED: CheckCircle2,
  TRANSACTION_FAILED: AlertTriangle,
  KYC_REJECTED: AlertTriangle,
  ACCOUNT_FROZEN: AlertTriangle,
  GENERIC_NOTICE: Info,
  SUPPORT_FOLLOWUP: Mail,
}

interface NotificationsUnavailable {
  kind: UnavailableKind
  forbidden?: boolean
}

function isNotificationPage(value: unknown): value is NotificationPage {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<NotificationPage>
  if (!Array.isArray(candidate.items)
    || typeof candidate.total !== 'number'
    || !Number.isInteger(candidate.total)
    || candidate.total < 0
    || typeof candidate.page !== 'number'
    || !Number.isInteger(candidate.page)
    || candidate.page < 0
    || typeof candidate.size !== 'number'
    || !Number.isInteger(candidate.size)
    || candidate.size <= 0
    || candidate.items.length > candidate.size
    || (candidate.items.length > 0
      && candidate.page * candidate.size + candidate.items.length > candidate.total)) return false

  return candidate.items.every(item => Boolean(item)
      && typeof item === 'object'
      && typeof item.id === 'string'
      && typeof item.template === 'string'
      && typeof item.channel === 'string'
      && typeof item.recipient === 'string'
      && (item.subject == null || typeof item.subject === 'string')
      && typeof item.status === 'string'
      && (item.sentAt == null || typeof item.sentAt === 'string')
      && typeof item.createdAt === 'string')
}

function NotificationsContent() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const { roles } = useAuth()
  const canApprove = hasPermission(roles, 'opsmessage:approve')
  const [items, setItems]     = useState<Notification[]>([])
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(0)
  const [pagination, setPagination] = useState<{ total: number; page: number; size: number } | null>(null)
  const requestSequenceRef = useRef(0)
  const activeRequestRef = useRef<AbortController | null>(null)
  // Typed unavailable reason → renders the calm <DataUnavailable> panel instead
  // of leaking a raw "HTTP 404" string (admin-ui graceful-state rule).
  const [unavailable, setUnavailable] = useState<NotificationsUnavailable | null>(null)

  const load = useCallback(async (requestedPage: number) => {
    const requestSequence = ++requestSequenceRef.current
    activeRequestRef.current?.abort()
    const controller = new AbortController()
    activeRequestRef.current = controller
    let timedOut = false
    const timeout = window.setTimeout(() => {
      timedOut = true
      controller.abort()
    }, 5000)

    setLoading(true); setUnavailable(null)
    try {
      const res = await fetch(
        `${NOTIFICATION_SERVICE}/api/v1/notifications?page=${requestedPage}&size=${PAGE_SIZE}`,
        { signal: controller.signal },
      )
      if (requestSequence !== requestSequenceRef.current) return
      if (!res.ok) {
        const kind = await classifyBffFailure(res)
        if (requestSequence !== requestSequenceRef.current) return
        const authRefused = res.status === 401 || res.status === 403
        if (authRefused) {
          // Notification recipients and subjects are protected operational data. Once the
          // current session is refused, do not retain a previously authorized page in the DOM.
          setItems([])
          setPagination(null)
        }
        setUnavailable({
          kind: res.status === 403 ? 'unauthorized' : kind === 'not_found' ? 'error' : kind,
          forbidden: res.status === 403,
        })
        return
      }
      let data: unknown
      try {
        data = await res.json()
      } catch {
        if (requestSequence !== requestSequenceRef.current) return
        if (controller.signal.aborted) {
          if (timedOut) setUnavailable({ kind: 'unreachable' })
          return
        }
        setUnavailable({ kind: 'error' })
        return
      }
      if (requestSequence !== requestSequenceRef.current) return
      if (!isNotificationPage(data)) {
        setUnavailable({ kind: 'error' })
        return
      }
      if (data.page !== requestedPage || data.size !== PAGE_SIZE) {
        setUnavailable({ kind: 'error' })
        return
      }

      const lastPage = data.total === 0 ? 0 : Math.floor((data.total - 1) / data.size)
      if (requestedPage > lastPage) {
        setPage(lastPage)
        return
      }
      // Count and page reads are separate backend statements. A concurrent delete can leave an
      // otherwise in-range non-zero page empty; walk back once and obtain an authoritative page.
      if (data.total > 0 && data.items.length === 0) {
        if (requestedPage > 0) setPage(requestedPage - 1)
        else setUnavailable({ kind: 'error' })
        return
      }

      setItems(data.items)
      setPagination({ total: data.total, page: data.page, size: data.size })
    } catch {
      if (requestSequence !== requestSequenceRef.current) return
      // An effect cleanup or newer request deliberately aborts this read. Only a real
      // timeout/network failure should replace the last successful page with an error panel.
      if (controller.signal.aborted && !timedOut) return
      setUnavailable({ kind: 'unreachable' })
    } finally {
      window.clearTimeout(timeout)
      if (requestSequence === requestSequenceRef.current) {
        activeRequestRef.current = null
        setLoading(false)
      }
    }
  }, [])

  useEffect(() => {
    void load(page)
    return () => {
      requestSequenceRef.current += 1
      activeRequestRef.current?.abort()
    }
  }, [load, page])

  const refresh = useCallback(() => {
    if (page === 0) void load(0)
    else setPage(0)
  }, [load, page])

  const sentCount   = items.filter(n => n.status === 'SENT').length
  const failedCount = items.filter(n => n.status === 'FAILED').length
  const pendingCount = items.filter(n => n.status === 'PENDING' || n.status === 'QUEUED').length
  const rangeStart = !pagination || pagination.total === 0 || items.length === 0
    ? 0
    : pagination.page * pagination.size + 1
  const rangeEnd = !pagination || pagination.total === 0 || items.length === 0
    ? 0
    : Math.min(pagination.page * pagination.size + items.length, pagination.total)
  const hasNextPage = pagination
    ? (pagination.page + 1) * pagination.size < pagination.total
    : false

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
          onClick={refresh}
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
          { label: t('Odesláno na této stránce', 'Sent on this page'), value: sentCount, color: 'var(--green)' },
          { label: t('Selhalo na této stránce', 'Failed on this page'), value: failedCount, color: 'var(--red)' },
          { label: t('Čeká / Ve frontě na této stránce', 'Pending / Queued on this page'), value: pendingCount, color: 'var(--yellow)' },
        ].map(s => (
          <div key={s.label} className="stat-card">
            <div className="stat-value" style={{ color: s.color }}>{loading || !pagination ? '—' : s.value}</div>
            <div className="stat-label">{s.label}</div>
          </div>
        ))}
      </div>

      {canApprove && <OperatorMessageApprovals />}

      {unavailable && (
        <div className="card" style={{ padding: 0, marginBottom: '16px' }}>
          <DataUnavailable
            kind={unavailable.kind}
            service={t('Notification-service', 'Notification-service')}
            feature={t('Notifikace', 'Notifications')}
            lang={language}
            title={unavailable.forbidden ? t('Přístup odepřen', 'Access denied') : undefined}
            detail={unavailable.forbidden
              ? t(
                'Jste přihlášeni, ale vaše role nemá oprávnění číst log oznámení. Požádejte správce o potřebný přístup.',
                'You are signed in, but your role cannot read the notification log. Ask an administrator for the required access.',
              )
              : items.length > 0
                ? t('Zobrazen je poslední úspěšně načtený log; stav doručení se mohl změnit.', 'The last successfully loaded log is shown; delivery status may have changed.')
                : undefined}
            dense
          />
        </div>
      )}

      <div className="card" style={{ overflow: 'hidden' }}>
        <table className="data-table">
          <thead>
            <tr>
              <th>{t('Šablona', 'Template')}</th>
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
              const Icon = TEMPLATE_ICON[n.template] ?? Bell
              return (
                <tr key={n.id}>
                  <td><div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}><Icon aria-hidden="true" size={13} style={{ color: 'var(--accent)' }} /><span className="tag">{n.template}</span></div></td>
                  <td><span className="tag">{n.channel}</span></td>
                  <td style={{ fontSize: '12px', fontFamily: 'var(--font-mono)' }}>{n.recipient}</td>
                  <td style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>{n.subject ?? '—'}</td>
                  <td><StatusBadge status={n.status} /></td>
                  <td style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                    {n.sentAt ? new Date(n.sentAt).toLocaleString(dateLocale) : '—'}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
        {pagination && pagination.total > 0 && !unavailable && (
          <nav
            aria-label={t('Stránkování oznámení', 'Notifications pagination')}
            style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', padding: '12px 16px', borderTop: '1px solid var(--border)' }}
          >
            <button
              className="btn btn-secondary"
              type="button"
              aria-label={t('Předchozí stránka oznámení', 'Previous notifications page')}
              disabled={page === 0}
              onClick={() => setPage(current => Math.max(0, current - 1))}
            >
              {t('← Předchozí', '← Previous')}
            </button>
            <span role="status" aria-live="polite" style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
              {t(
                `Zobrazeno ${rangeStart}–${rangeEnd} z ${pagination.total} oznámení`,
                `Showing ${rangeStart}–${rangeEnd} of ${pagination.total} ${pagination.total === 1 ? 'notification' : 'notifications'}`,
              )}
            </span>
            <button
              className="btn btn-secondary"
              type="button"
              aria-label={t('Další stránka oznámení', 'Next notifications page')}
              disabled={loading || !hasNextPage}
              onClick={() => setPage(current => current + 1)}
            >
              {t('Další →', 'Next →')}
            </button>
          </nav>
        )}
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
 * The unified /approvals inbox owns the auto-loaded queue. This domain workbench accepts the
 * selected opaque id by deep link, but keeps the actual decision on the existing endpoint where
 * backend self-approval and maker-checker enforcement remain authoritative.
 */
function OperatorMessageApprovals() {
  const { t } = useLanguage()
  const [approvalId, setApprovalId] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => {
    const linkedApprovalId = readApprovalId(window.location.search)
    if (!linkedApprovalId) return
    const frame = requestAnimationFrame(() => setApprovalId(linkedApprovalId))
    return () => cancelAnimationFrame(frame)
  }, [])

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
    <div className="card" style={{ overflow: 'hidden', marginBottom: '16px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '16px 20px', borderBottom: '1px solid var(--border)' }}>
        <Clock aria-hidden="true" size={15} style={{ color: 'var(--yellow)' }} />
        <span style={{ fontWeight: 600, fontSize: '13px' }}>
          {t('Schválení zpráv (princip čtyř očí)', 'Message approvals (four-eyes)')}
        </span>
      </div>
      <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: '10px' }}>
        <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
          {t(
            'Zadejte ID schválení, které vám předal operátor odesílající zprávu, a rozhodněte. Vlastní zprávu schválit nelze.',
            'Enter the approval id the composing operator gave you, then decide. You cannot approve your own message.',
          )}
        </span>
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
          <input
            id="notification-approval-id"
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
