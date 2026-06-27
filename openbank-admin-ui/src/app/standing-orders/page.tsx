// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect } from 'react'
import { Repeat, Plus, Search, CheckCircle2, XCircle, Clock, RefreshCw, Calendar } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'

interface StandingOrder {
  id: string; debtorAccountId: string; creditorAccountId: string; creditorName: string
  amount: number; currency: string; frequency: string; status: string
  nextExecutionDate: string; description: string
}

const FREQ_LABELS: Record<string, string> = {
  DAILY: 'Denně', WEEKLY: 'Týdně', MONTHLY: 'Měsíčně', QUARTERLY: 'Čtvrtletně', YEARLY: 'Ročně'
}

export default function StandingOrdersPage() {
  const { t } = useLanguage()
  const [orders, setOrders] = useState<StandingOrder[]>([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [serviceUp, setServiceUp] = useState<boolean | null>(null)

  useEffect(() => {
    fetch('/api/svc/standing-order-service/q/health/ready').then(r => setServiceUp(r.ok)).catch(() => setServiceUp(false))
    fetch('/api/svc/standing-order-service/api/v1/standing-orders').then(r => r.json())
      .then(d => setOrders(Array.isArray(d) ? d : d.standingOrders ?? []))
      .catch(() => setOrders([]))
      .finally(() => setLoading(false))
  }, [])

  const filtered = orders.filter(o =>
    o.creditorName?.toLowerCase().includes(search.toLowerCase()) ||
    o.description?.toLowerCase().includes(search.toLowerCase()) ||
    o.currency?.includes(search.toUpperCase())
  )

  return (
    <AuthGuard>
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '28px' }}>
          <div>
            <h1 style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '4px' }}>
              {t('Trvalé příkazy', 'Standing Orders')}
            </h1>
            <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              {t('Trvalé příkazy a opakované platby — SEPA SCT', 'Standing orders and recurring payments — SEPA SCT')}
            </p>
          </div>
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '11px', fontWeight: 600,
              padding: '4px 10px', borderRadius: '20px',
              background: serviceUp === true ? 'var(--success-bg)' : serviceUp === false ? 'var(--danger-bg)' : 'var(--surface-3)',
              color: serviceUp === true ? 'var(--success-text)' : serviceUp === false ? 'var(--danger-text)' : 'var(--text-tertiary)',
              border: `1px solid ${serviceUp === true ? 'var(--success-border)' : serviceUp === false ? 'var(--danger-border)' : 'var(--border)'}` }}>
              {serviceUp === true ? <CheckCircle2 size={10} /> : serviceUp === false ? <XCircle size={10} /> : <Clock size={10} />}
              standing-order :8121
            </span>
            <button className="btn btn-primary btn-sm"><Plus size={13} /> {t('Nový příkaz', 'New Order')}</button>
          </div>
        </div>

        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Celkem příkazů', 'Total orders'), value: orders.length, icon: <Repeat size={16} />, color: 'var(--accent)' },
            { label: t('Aktivní', 'Active'), value: orders.filter(o => o.status === 'ACTIVE').length, icon: <CheckCircle2 size={16} />, color: 'var(--success)' },
            { label: t('Pozastavené', 'Suspended'), value: orders.filter(o => o.status === 'SUSPENDED').length, icon: <Clock size={16} />, color: 'var(--warning)' },
            { label: t('Zrušené', 'Cancelled'), value: orders.filter(o => o.status === 'CANCELLED').length, icon: <XCircle size={16} />, color: 'var(--danger)' },
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
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat příkazy…', 'Search orders…')}
                style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                  border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
            </div>
          </div>
          {loading ? (
            <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám…', 'Loading…')}</div>
            </div>
          ) : filtered.length === 0 ? (
            <div style={{ padding: '48px', textAlign: 'center' }}>
              <Repeat size={32} style={{ color: 'var(--text-tertiary)', marginBottom: '12px' }} />
              <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '4px' }}>{t('Žádné trvalé příkazy', 'No standing orders')}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{t('Mikroservisa běží na portu 8121.', 'Microservice is running on port 8121.')}</div>
              <a href="/api/svc/standing-order-service/api/docs" target="_blank" rel="noreferrer"
                style={{ display: 'inline-block', marginTop: '12px', fontSize: '12px', color: 'var(--accent)', textDecoration: 'none' }}>→ Swagger UI</a>
            </div>
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                {[t('Příjemce', 'Recipient'), t('Částka', 'Amount'), t('Frekvence', 'Frequency'), t('Příští platba', 'Next run'), t('Status', 'Status'), t('Popis', 'Description')].map(h => (
                  <th key={h} style={{ padding: '10px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>
                ))}
              </tr></thead>
              <tbody>{filtered.map(o => (
                <tr key={o.id} style={{ borderBottom: '1px solid var(--border)' }}
                  onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                  onMouseLeave={e => (e.currentTarget.style.background = '')}>
                  <td style={{ padding: '12px 16px', fontSize: '13px', fontWeight: 500, color: 'var(--text-primary)' }}>{o.creditorName}</td>
                  <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '13px', color: 'var(--text-primary)' }}>
                    {o.amount?.toLocaleString('cs-CZ', { minimumFractionDigits: 2 })} {o.currency}
                  </td>
                  <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{FREQ_LABELS[o.frequency] ?? o.frequency}</td>
                  <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '5px' }}>
                    <Calendar size={11} style={{ color: 'var(--text-tertiary)' }} />
                    {o.nextExecutionDate ? new Date(o.nextExecutionDate).toLocaleDateString('cs-CZ') : '—'}
                  </td>
                  <td style={{ padding: '12px 16px' }}>
                    <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600,
                      background: o.status === 'ACTIVE' ? 'var(--success-bg)' : o.status === 'SUSPENDED' ? 'var(--warning-bg)' : 'var(--surface-3)',
                      color: o.status === 'ACTIVE' ? 'var(--success-text)' : o.status === 'SUSPENDED' ? 'var(--warning-text)' : 'var(--text-tertiary)',
                      border: `1px solid ${o.status === 'ACTIVE' ? 'var(--success-border)' : o.status === 'SUSPENDED' ? 'var(--warning-border)' : 'var(--border)'}` }}>{o.status}</span>
                  </td>
                  <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)', maxWidth: '200px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{o.description}</td>
                </tr>
              ))}</tbody>
            </table>
          )}
        </div>
      </div>
    </AuthGuard>
  )
}
