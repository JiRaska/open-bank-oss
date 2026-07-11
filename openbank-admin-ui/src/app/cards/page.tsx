// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { CreditCard, Plus, Search, RefreshCw, CheckCircle2, XCircle, Clock } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'

interface Card {
  id: string; partyId: string; accountId: string; maskedPan: string
  cardType: string; status: string; expiryDate: string; createdAt: string
}

const STATUS_COLORS: Record<string, { bg: string; text: string; border: string }> = {
  ACTIVE:    { bg: 'var(--success-bg)',  text: 'var(--success-text)',  border: 'var(--success-border)' },
  BLOCKED:   { bg: 'var(--danger-bg)',   text: 'var(--danger-text)',   border: 'var(--danger-border)' },
  EXPIRED:   { bg: 'var(--surface-3)',   text: 'var(--text-tertiary)', border: 'var(--border)' },
  PENDING:   { bg: 'var(--warning-bg)',  text: 'var(--warning-text)',  border: 'var(--warning-border)' },
}

export default function CardsPage() {
  const { t, language } = useLanguage()
  const [search, setSearch] = useState('')

  // Single graceful data path (admin-ui rule #1): the hook classifies a non-OK
  // BFF response and auto-wakes a scaled-to-zero pod (KEDA, ADR-0057) instead of
  // showing a cold 503 as "not responding".
  const { data, loading, unavailable, waking } = useServiceResource<Card[]>(
    svcUrl('card-issuance-service', '/api/v1/cards'),
    { select: (raw) => (Array.isArray(raw) ? (raw as Card[]) : ((raw as { cards?: Card[] }).cards ?? [])) },
  )
  const cards = data ?? []

  const filtered = cards.filter(c =>
    c.maskedPan?.includes(search) || c.cardType?.toLowerCase().includes(search.toLowerCase()) ||
    c.status?.toLowerCase().includes(search.toLowerCase())
  )

  return (
    <AuthGuard>
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '28px' }}>
          <div>
            <h1 style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '4px' }}>
              {t('Vydávání karet', 'Card Issuance')}
            </h1>
            <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              {t('Vydávání a správa platebních karet — PCI DSS Level 1', 'Card issuance and management — PCI DSS Level 1')}
            </p>
          </div>
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <ServiceStatusBadge
              label="card-issuance :8118"
              loading={loading}
              waking={waking}
              unavailable={unavailable}
              copy={{
                up: t('card-issuance běží', 'card-issuance is up'),
                idle: t('card-issuance spí (scale-to-zero), probouzí se…', 'card-issuance idle (scaled to zero), waking…'),
                down: t('card-issuance neodpovídá', 'card-issuance is not responding'),
                checking: t('Zjišťuji stav služby…', 'Checking service…'),
              }}
            />
            <button className="btn btn-primary btn-sm"><Plus size={13} /> {t('Vydat kartu', 'Issue Card')}</button>
          </div>
        </div>

        {/* KPIs */}
        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Celkem karet', 'Total cards'), value: cards.length, icon: <CreditCard size={16} />, color: 'var(--accent)' },
            { label: t('Aktivní', 'Active'), value: cards.filter(c => c.status === 'ACTIVE').length, icon: <CheckCircle2 size={16} />, color: 'var(--success)' },
            { label: t('Blokované', 'Blocked'), value: cards.filter(c => c.status === 'BLOCKED').length, icon: <XCircle size={16} />, color: 'var(--danger)' },
            { label: t('Čekající', 'Pending'), value: cards.filter(c => c.status === 'PENDING').length, icon: <Clock size={16} />, color: 'var(--warning)' },
          ].map(k => (
            <div key={k.label} className="stat-card">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
                <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: `${k.color}18`,
                  display: 'flex', alignItems: 'center', justifyContent: 'center', color: k.color }}>{k.icon}</div>
              </div>
              <div style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>{k.value}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{k.label}</div>
            </div>
          ))}
        </div>

        {/* Table */}
        <div className="card">
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '10px', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={13} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat karty…', 'Search cards…')}
                style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                  border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
            </div>
          </div>
          {loading ? (
            <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} />
              <div>{t('Načítám karty…', 'Loading cards…')}</div>
            </div>
          ) : unavailable ? (
            <DataUnavailable kind={unavailable.kind} service={t('Card-issuance-service', 'Card-issuance-service')} feature={t('Karty', 'Cards')} lang={language} />
          ) : filtered.length === 0 ? (
            <DataUnavailable kind="no_data" feature={t('Karty', 'Cards')} lang={language}
              detail={cards.length === 0
                ? t('Služba běží, zatím nebyly vydány žádné karty.', 'The service is running; no cards have been issued yet.')
                : t('Žádné výsledky pro zadaný filtr.', 'No results for the applied filter.')} />
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border)' }}>
                  {[t('PAN', 'PAN'), t('Typ', 'Type'), t('Status', 'Status'), t('Platnost', 'Expiry'), t('Party ID', 'Party ID'), t('Vytvořeno', 'Created')].map(h => (
                    <th key={h} style={{ padding: '10px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 700,
                      color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filtered.map(c => {
                  const sc = STATUS_COLORS[c.status] ?? STATUS_COLORS.PENDING
                  return (
                    <tr key={c.id} style={{ borderBottom: '1px solid var(--border)' }}
                      onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                      onMouseLeave={e => (e.currentTarget.style.background = '')}>
                      <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '13px', color: 'var(--text-primary)' }}>{c.maskedPan}</td>
                      <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{c.cardType}</td>
                      <td style={{ padding: '12px 16px' }}>
                        <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600,
                          background: sc.bg, color: sc.text, border: `1px solid ${sc.border}` }}>{c.status}</span>
                      </td>
                      <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{c.expiryDate}</td>
                      <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-tertiary)' }}>{c.partyId?.slice(0,8)}…</td>
                      <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{c.createdAt ? new Date(c.createdAt).toLocaleDateString('cs-CZ') : '—'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </AuthGuard>
  )
}
