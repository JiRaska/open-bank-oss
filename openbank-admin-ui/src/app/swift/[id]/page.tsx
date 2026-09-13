// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, RefreshCw, ChevronDown, ChevronRight } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { svcUrl, classifyBffFailure } from '@/lib/services/bff'
import { clearStashedRow, readStashedRow } from '@/lib/services/rowHandoff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { StatusBadge } from '@/components/ui'
import { parseSwiftMessages, swiftStatusTone, type SwiftMessage } from '@/lib/swift/swiftMessageContract'

export default function SwiftDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { t, language } = useLanguage()

  const [message, setMessage] = useState<SwiftMessage | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [showRaw, setShowRaw] = useState(false)
  const requestRef = useRef<AbortController | null>(null)

  const load = useCallback(async (readHandoff = false) => {
    requestRef.current?.abort()
    const controller = new AbortController()
    requestRef.current = controller
    setLoading(true)
    if (readHandoff) setMessage(null)
    const stashed = readHandoff ? readStashedRow<SwiftMessage>('swift', id) : null
    if (stashed) {
      try {
        setMessage(parseSwiftMessages([stashed])[0] ?? null)
        setUnavailable(null)
      } catch {
        clearStashedRow('swift', id)
      }
    }
    const deadline = setTimeout(() => controller.abort(), 8_000)
    try {
      // The by-id endpoint exposes the domain model rather than this list response contract, so
      // re-fetch the validated queue shape and select the requested message.
      const res = await fetch(svcUrl('swift-service', '/api/v1/swift/messages'), { signal: controller.signal, cache: 'no-store' })
      if (requestRef.current !== controller) return
      if (!res.ok) {
        const kind = await classifyBffFailure(res)
        if (requestRef.current !== controller) return
        if (kind === 'unauthorized') {
          clearStashedRow('swift', id)
          setMessage(null)
        }
        setUnavailable({ kind })
        return
      }
      const body = (await res.json()) as unknown
      if (requestRef.current !== controller) return
      const items = parseSwiftMessages(body)
      const found = items.find(m => m.id === id)
      if (found) { setMessage(found); setUnavailable(null) }
      else {
        clearStashedRow('swift', id)
        setMessage(null)
        setUnavailable({ kind: 'not_found' })
      }
    } catch {
      if (requestRef.current === controller) setUnavailable({ kind: 'unreachable' })
    } finally {
      clearTimeout(deadline)
      if (requestRef.current === controller) {
        requestRef.current = null
        setLoading(false)
      }
    }
  }, [id])

  useEffect(() => {
    void load(true)
    return () => {
      const activeRequest = requestRef.current
      requestRef.current = null
      activeRequest?.abort()
    }
  }, [load])

  return (
    <AuthGuard permission="payment-rails:view">
    <div>
      <PageHeader
        title={message?.messageType ?? t('SWIFT zpráva', 'SWIFT message')}
        subtitle={t('Detail SWIFT zprávy — ISO 20022', 'SWIFT message detail — ISO 20022')}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/swift" style={{ color: 'var(--text-tertiary)', textDecoration: 'none' }}>{t('SWIFT zprávy', 'SWIFT')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current mono" style={{ fontSize: '12px' }}>{id.slice(0, 12)}…</span></div>}
        actions={<div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          {message?.status && <StatusBadge status={message.status} tone={swiftStatusTone(message.status)} />}
          <Link href="/swift" className="btn btn-secondary"><ArrowLeft size={13} aria-hidden="true" /> {t('Zpět', 'Back')}</Link>
          <button
            className="btn btn-secondary"
            type="button"
            onClick={() => void load(false)}
            disabled={loading}
            aria-busy={loading}
            aria-label={t('Obnovit SWIFT zprávu', 'Refresh SWIFT message')}
          >
            <RefreshCw size={13} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      {message && unavailable && <div role="status" aria-live="polite" style={{ marginBottom: 14 }}>
        <DataUnavailable kind={unavailable.kind} service={t('SWIFT-service', 'SWIFT-service')} feature={t('Aktualizace SWIFT zprávy', 'SWIFT message refresh')} lang={language} dense />
        <p style={{ margin: '6px 0 0', color: 'var(--text-tertiary)', fontSize: 11 }}>
          {t('Zobrazen je poslední ověřený snapshot; stav zprávy se mohl změnit.', 'Showing the last verified snapshot; the message status may have changed.')}
        </p>
      </div>}

      {loading && !message ? (
        <div role="status" aria-live="polite" style={{ padding: '40px 0', color: 'var(--text-tertiary)', fontSize: '13px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <RefreshCw size={14} aria-hidden="true" className="animate-spin" /> {t('Načítám zprávu…', 'Loading message…')}
        </div>
      ) : !message && unavailable ? (
        <div className="card"><DataUnavailable kind={unavailable.kind} service={t('SWIFT-service', 'SWIFT-service')} feature={t('SWIFT zpráva', 'SWIFT message')} lang={language} /></div>
      ) : message ? (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
          <div className="card">
            <div className="card-header"><span className="card-header-title">{t('Zpráva', 'Message')}</span></div>
            <DetailRows rows={[
              { label: t('ID zprávy', 'Message ID'), value: message.id, mono: true },
              { label: t('Typ zprávy', 'Message type'), value: message.messageType ?? '—', mono: true },
              { label: t('Reference', 'Reference'), value: message.reference ?? '—', mono: true },
              { label: t('Stav', 'Status'), value: message.status ?? '—' },
              { label: t('Vytvořeno', 'Created'), value: message.createdAt ? new Date(message.createdAt).toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB') : '—' },
            ]} />
          </div>
          <div className="card">
            <div className="card-header"><span className="card-header-title">{t('Směrování & částka', 'Routing & amount')}</span></div>
            <DetailRows rows={[
              { label: t('Odesílatel BIC', 'Sender BIC'), value: message.senderBic ?? '—', mono: true },
              { label: t('Příjemce BIC', 'Receiver BIC'), value: message.receiverBic ?? '—', mono: true },
              { label: t('Částka', 'Amount'), value: message.amount != null ? `${Number(message.amount).toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-US', { minimumFractionDigits: 2 })} ${message.currency ?? ''}` : '—' },
            ]} />
          </div>
          <div className="card" style={{ gridColumn: '1 / -1' }}>
            <button type="button" aria-expanded={showRaw} aria-controls="swift-raw-payload" aria-label={showRaw ? t('Skrýt surový payload', 'Hide raw payload') : t('Zobrazit surový payload', 'Show raw payload')} onClick={() => setShowRaw(s => !s)}
              style={{ width: '100%', display: 'flex', alignItems: 'center', gap: '6px', padding: '12px 18px', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>
              {showRaw ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
              {t('Surová data (JSON)', 'Raw payload (JSON)')}
            </button>
            {showRaw && (
              <pre id="swift-raw-payload" style={{ margin: 0, padding: '0 18px 18px', fontSize: '11px', fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)', overflowX: 'auto' }}>
                {JSON.stringify(message, null, 2)}
              </pre>
            )}
          </div>
        </div>
      ) : null}
    </div>
    </AuthGuard>
  )
}

function DetailRows({ rows }: { rows: { label: string; value: string; mono?: boolean }[] }) {
  return (
    <div style={{ padding: '4px 0' }}>
      {rows.map((row, i, arr) => (
        <div key={row.label} style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '16px',
          padding: '10px 18px', borderBottom: i < arr.length - 1 ? '1px solid var(--border)' : 'none',
        }}>
          <span style={{ fontSize: '12px', color: 'var(--text-secondary)', flexShrink: 0 }}>{row.label}</span>
          <span style={{
            fontSize: '12px', fontWeight: 500, color: 'var(--text-primary)', textAlign: 'right',
            fontFamily: row.mono ? 'var(--font-mono)' : 'inherit',
            maxWidth: '320px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}>{row.value}</span>
        </div>
      ))}
    </div>
  )
}
