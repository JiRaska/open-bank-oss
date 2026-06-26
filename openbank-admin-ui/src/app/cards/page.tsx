// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

'use client'
import { useState, useEffect } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { CreditCard, Plus, Search, Filter, RefreshCw, CheckCircle2, XCircle, Clock, Shield, Zap } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'

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
  const [cards, setCards] = useState<Card[]>([])
  const { t } = useLanguage()
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [serviceUp, setServiceUp] = useState<boolean | null>(null)

  useEffect(() => {
    fetch('/api/svc/card-issuance-service/q/health/ready').then(r => setServiceUp(r.ok)).catch(() => setServiceUp(false))
    fetch('/api/svc/card-issuance-service/api/v1/cards').then(r => r.json()).then(d => {
      setCards(Array.isArray(d) ? d : d.cards ?? [])
    }).catch(() => setCards([])).finally(() => setLoading(false))
  }, [])

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
            <span style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '11px', fontWeight: 600,
              padding: '4px 10px', borderRadius: '20px',
              background: serviceUp === true ? 'var(--success-bg)' : serviceUp === false ? 'var(--danger-bg)' : 'var(--surface-3)',
              color: serviceUp === true ? 'var(--success-text)' : serviceUp === false ? 'var(--danger-text)' : 'var(--text-tertiary)',
              border: `1px solid ${serviceUp === true ? 'var(--success-border)' : serviceUp === false ? 'var(--danger-border)' : 'var(--border)'}` }}>
              {serviceUp === true ? <CheckCircle2 size={10} /> : serviceUp === false ? <XCircle size={10} /> : <Clock size={10} />}
              card-issuance :8118
            </span>
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
          ) : filtered.length === 0 ? (
            <div style={{ padding: '48px', textAlign: 'center' }}>
              <CreditCard size={32} style={{ color: 'var(--text-tertiary)', marginBottom: '12px' }} />
              <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '4px' }}>{t('Žádné karty', 'No cards')}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                {cards.length === 0 ? t('Mikroservisa běží. Zatím nebyly vydány žádné karty.', 'Microservice is running. No cards have been issued yet.') : t('Žádné výsledky pro zadaný filtr.', 'No results for the applied filter.')}
              </div>
              <a href="/api/svc/card-issuance-service/api/docs" target="_blank" rel="noreferrer"
                style={{ display: 'inline-block', marginTop: '12px', fontSize: '12px', color: 'var(--accent)', textDecoration: 'none' }}>
                → Swagger UI (port 8118)
              </a>
            </div>
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
