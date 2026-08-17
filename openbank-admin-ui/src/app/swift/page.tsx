// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { Globe, Search, CheckCircle2, Clock, RefreshCw, AlertTriangle, ChevronRight } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import { stashRow } from '@/lib/services/rowHandoff'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'

interface SwiftMessage {
  id: string; messageType: string; senderBic: string; receiverBic: string
  amount: number; currency: string; status: string; createdAt: string; reference: string
}

const STATUS_COLORS: Record<string, { bg: string; text: string; border: string }> = {
  SENT:       { bg: 'var(--success-bg)',  text: 'var(--success-text)',  border: 'var(--success-border)' },
  PENDING:    { bg: 'var(--warning-bg)',  text: 'var(--warning-text)',  border: 'var(--warning-border)' },
  FAILED:     { bg: 'var(--danger-bg)',   text: 'var(--danger-text)',   border: 'var(--danger-border)' },
  PROCESSING: { bg: 'var(--info-bg)',     text: 'var(--info-text)',     border: 'var(--info-border)' },
}

export default function SwiftPage() {
  const { t, language } = useLanguage()
  const router = useRouter()
  const [search, setSearch] = useState('')
  const { data, loading, unavailable, waking } = useServiceResource<SwiftMessage[]>(
    svcUrl('swift-service', '/api/v1/swift/messages'),
    { select: (raw) => (Array.isArray(raw) ? (raw as SwiftMessage[]) : ((raw as { messages?: SwiftMessage[] }).messages ?? [])) },
  )
  const messages = data ?? []

  const filtered = messages.filter(m =>
    m.senderBic?.toLowerCase().includes(search.toLowerCase()) ||
    m.receiverBic?.toLowerCase().includes(search.toLowerCase()) ||
    m.reference?.toLowerCase().includes(search.toLowerCase()) ||
    m.messageType?.includes(search.toUpperCase())
  )

  return (
    <AuthGuard>
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '28px' }}>
          <div>
            <h1 style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '4px' }}>
              {t('SWIFT zprávy', 'SWIFT Messaging')}
            </h1>
            <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              {t('Mezinárodní platby a SWIFT MT/MX zprávy — ISO 20022', 'International payments and SWIFT MT/MX messages — ISO 20022')}
            </p>
          </div>
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <ServiceStatusBadge
              label="swift-service :8122"
              loading={loading}
              waking={waking}
              unavailable={unavailable}
              copy={{
                up: t('swift-service běží', 'swift-service is up'),
                idle: t('swift-service spí (scale-to-zero), probouzí se…', 'swift-service idle (scaled to zero), waking…'),
                down: t('swift-service neodpovídá', 'swift-service is not responding'),
                checking: t('Zjišťuji stav služby…', 'Checking service…'),
              }}
            />
            <span role="status" style={{ padding: '5px 9px', borderRadius: '6px', border: '1px solid var(--warning-border)', background: 'var(--warning-bg)', color: 'var(--warning-text)', fontSize: '11px', fontWeight: 600 }}>
              {t('Odeslání zprávy není připojeno', 'Message submission is not connected')}
            </span>
          </div>
        </div>

        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Zpráv celkem', 'Total messages'), value: messages.length, icon: <Globe size={16} />, color: 'var(--accent)' },
            { label: t('Odesláno', 'Sent'), value: messages.filter(m => m.status === 'SENT').length, icon: <CheckCircle2 size={16} />, color: 'var(--success)' },
            { label: t('Čeká', 'Pending'), value: messages.filter(m => m.status === 'PENDING' || m.status === 'PROCESSING').length, icon: <Clock size={16} />, color: 'var(--warning)' },
            { label: t('Chyby', 'Errors'), value: messages.filter(m => m.status === 'FAILED').length, icon: <AlertTriangle size={16} />, color: 'var(--danger)' },
          ].map(k => (
            <div key={k.label} className="stat-card">
              <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: `${k.color}18`,
                display: 'flex', alignItems: 'center', justifyContent: 'center', color: k.color, marginBottom: '10px' }}>{k.icon}</div>
              <div style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>{k.value}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{k.label}</div>
            </div>
          ))}
        </div>

        <div className="card">
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '10px', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={13} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat BIC, referenci, typ zprávy…', 'Search BIC, reference, message type…')}
                style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                  border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
            </div>
          </div>
          {loading ? (
            <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám…', 'Loading…')}</div>
            </div>
          ) : unavailable ? (
            <DataUnavailable kind={unavailable.kind} service={t('SWIFT-service', 'SWIFT-service')} feature={t('SWIFT zprávy', 'SWIFT messages')} lang={language} />
          ) : filtered.length === 0 ? (
            <DataUnavailable kind="no_data" feature={t('SWIFT zprávy', 'SWIFT messages')} lang={language}
              detail={messages.length === 0
                ? t('Služba běží, zatím žádné SWIFT zprávy.', 'The service is running; no SWIFT messages yet.')
                : t('Žádné výsledky pro zadaný filtr.', 'No results for the applied filter.')} />
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                {[t('Typ', 'Type'), t('Odesílatel BIC', 'Sender BIC'), t('Příjemce BIC', 'Recipient BIC'), t('Částka', 'Amount'), t('Reference', 'Reference'), t('Status', 'Status'), t('Datum', 'Date')].map(h => (
                  <th key={h} style={{ padding: '10px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>
                ))}
                <th aria-label={t('Detail', 'Detail')} style={{ width: '36px' }} />
              </tr></thead>
              <tbody>{filtered.map(m => {
                const sc = STATUS_COLORS[m.status] ?? STATUS_COLORS.PENDING
                return (
                  <tr key={m.id} style={{ borderBottom: '1px solid var(--border)', cursor: 'pointer' }}
                    onClick={() => { stashRow('swift', m.id, m); router.push(`/swift/${m.id}`) }}
                    title={t('Zobrazit detail zprávy', 'View message detail')}
                    onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                    onMouseLeave={e => (e.currentTarget.style.background = '')}>
                    <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700, color: 'var(--accent)' }}>{m.messageType}</td>
                    <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--text-primary)' }}>{m.senderBic}</td>
                    <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--text-primary)' }}>{m.receiverBic}</td>
                    <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '13px', color: 'var(--text-primary)' }}>
                      {m.amount?.toLocaleString('cs-CZ', { minimumFractionDigits: 2 })} {m.currency}
                    </td>
                    <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-tertiary)' }}>{m.reference}</td>
                    <td style={{ padding: '12px 16px' }}>
                      <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600,
                        background: sc.bg, color: sc.text, border: `1px solid ${sc.border}` }}>{m.status}</span>
                    </td>
                    <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{m.createdAt ? new Date(m.createdAt).toLocaleDateString('cs-CZ') : '—'}</td>
                    <td style={{ padding: '12px 8px', textAlign: 'right' }}><ChevronRight size={14} style={{ color: 'var(--text-tertiary)' }} /></td>
                  </tr>
                )
              })}</tbody>
            </table>
          )}
        </div>
      </div>
    </AuthGuard>
  )
}
