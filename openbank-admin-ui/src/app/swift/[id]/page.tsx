// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, RefreshCw, ChevronDown, ChevronRight } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl, classifyBffFailure } from '@/lib/services/bff'
import { readStashedRow } from '@/lib/services/rowHandoff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'

interface SwiftMessage {
  id: string
  messageType?: string
  senderBic?: string
  receiverBic?: string
  amount?: number
  currency?: string
  status?: string
  reference?: string
  createdAt?: string
  [k: string]: unknown
}

const STATUS_COLOR: Record<string, string> = {
  SENT: 'var(--success)', PROCESSING: 'var(--info-text)', PENDING: 'var(--warning)', FAILED: 'var(--danger)',
}

export default function SwiftDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { t, language } = useLanguage()

  const [message, setMessage] = useState<SwiftMessage | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [showRaw, setShowRaw] = useState(false)

  async function load() {
    setLoading(true)
    const stashed = readStashedRow<SwiftMessage>('swift', id)
    if (stashed) { setMessage(stashed); setUnavailable(null) }
    try {
      // No by-id backend endpoint — re-fetch the list and pick this id out.
      const res = await fetch(svcUrl('swift-service', '/api/v1/swift/messages'), { signal: AbortSignal.timeout(10_000), cache: 'no-store' })
      if (!res.ok) {
        if (!stashed) setUnavailable({ kind: await classifyBffFailure(res) })
        setLoading(false)
        return
      }
      const body = (await res.json()) as unknown
      const items = (Array.isArray(body) ? body : ((body as { messages?: unknown[] }).messages ?? [])) as SwiftMessage[]
      const found = items.find(m => m.id === id)
      if (found) { setMessage(found); setUnavailable(null) }
      else if (!stashed) setUnavailable({ kind: 'not_found' })
    } catch {
      if (!stashed) setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [id])

  return (
    <div>
      <PageHeader
        title={message?.messageType ?? t('SWIFT zpráva', 'SWIFT message')}
        subtitle={t('Detail SWIFT zprávy — ISO 20022', 'SWIFT message detail — ISO 20022')}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/swift" style={{ color: 'var(--text-tertiary)', textDecoration: 'none' }}>{t('SWIFT zprávy', 'SWIFT')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current mono" style={{ fontSize: '12px' }}>{id.slice(0, 12)}…</span></div>}
        actions={<div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          {message?.status && <span className="pill" style={{ background: `${STATUS_COLOR[message.status] ?? 'var(--text-muted)'}22`, color: STATUS_COLOR[message.status] ?? 'var(--text-muted)' }}>{message.status}</span>}
          <Link href="/swift" className="btn btn-secondary"><ArrowLeft size={13} aria-hidden="true" /> {t('Zpět', 'Back')}</Link>
          <button
            className="btn btn-secondary"
            type="button"
            onClick={load}
            disabled={loading}
            aria-busy={loading}
            aria-label={t('Obnovit SWIFT zprávu', 'Refresh SWIFT message')}
          >
            <RefreshCw size={13} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

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
