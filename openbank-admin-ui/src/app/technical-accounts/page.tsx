// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

'use client'

import { useEffect, useState } from 'react'
import { Building2, RefreshCw, TrendingUp, TrendingDown } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

interface TechnicalAccount {
  id: string
  accountNumber: string
  accountType: string
  glCode: string
  name: string
  description?: string
  currency: string
  status: string
  balance: number
  normalBalance: string
  parentGlCode?: string
  isSuspense: boolean
  isNostro: boolean
  isVostro: boolean
  correspondentBankBic?: string
  correspondentBankName?: string
  openedAt: string
  lastMovementAt?: string
}

const TYPE_LABELS: Record<string, { label: string; color: string }> = {
  NOSTRO:           { label: 'Nostro',          color: '#2563eb' },
  VOSTRO:           { label: 'Vostro',          color: '#7c3aed' },
  SUSPENSE:         { label: 'Suspense',         color: '#d97706' },
  FEE_INCOME:       { label: 'Fee Income',       color: '#16a34a' },
  FEE_EXPENSE:      { label: 'Fee Expense',      color: '#dc2626' },
  INTEREST_INCOME:  { label: 'Interest Income',  color: '#059669' },
  INTEREST_EXPENSE: { label: 'Interest Expense', color: '#b91c1c' },
  PROVISION:        { label: 'Provision',        color: '#9333ea' },
  CLEARING:         { label: 'Clearing',         color: '#0891b2' },
  SETTLEMENT:       { label: 'Settlement',       color: '#1d4ed8' },
  CAPITAL:          { label: 'Capital',          color: '#065f46' },
  RESERVE:          { label: 'Reserve',          color: '#1e3a5f' },
  TAX:              { label: 'Tax',              color: '#92400e' },
  OTHER:            { label: 'Other',            color: '#6b7280' },
}

// Seed data — in production this would come from account-service /api/v1/technical-accounts
const SEED_ACCOUNTS: TechnicalAccount[] = [
  { id: '1', accountNumber: 'TECH-SUSPENSE-CZK', accountType: 'SUSPENSE', glCode: '1001', name: 'Suspense Account CZK', currency: 'CZK', status: 'ACTIVE', balance: 0, normalBalance: 'DEBIT', isSuspense: true, isNostro: false, isVostro: false, openedAt: '2025-01-01' },
  { id: '2', accountNumber: 'TECH-SUSPENSE-EUR', accountType: 'SUSPENSE', glCode: '1002', name: 'Suspense Account EUR', currency: 'EUR', status: 'ACTIVE', balance: 0, normalBalance: 'DEBIT', isSuspense: true, isNostro: false, isVostro: false, openedAt: '2025-01-01' },
  { id: '3', accountNumber: 'TECH-FEE-INCOME', accountType: 'FEE_INCOME', glCode: '4001', name: 'Fee Income Account', currency: 'CZK', status: 'ACTIVE', balance: 125430.50, normalBalance: 'CREDIT', isSuspense: false, isNostro: false, isVostro: false, openedAt: '2025-01-01' },
  { id: '4', accountNumber: 'TECH-INT-INCOME', accountType: 'INTEREST_INCOME', glCode: '4101', name: 'Interest Income Account', currency: 'CZK', status: 'ACTIVE', balance: 89200.00, normalBalance: 'CREDIT', isSuspense: false, isNostro: false, isVostro: false, openedAt: '2025-01-01' },
  { id: '5', accountNumber: 'TECH-INT-EXPENSE', accountType: 'INTEREST_EXPENSE', glCode: '5101', name: 'Interest Expense Account', currency: 'CZK', status: 'ACTIVE', balance: 34100.00, normalBalance: 'DEBIT', isSuspense: false, isNostro: false, isVostro: false, openedAt: '2025-01-01' },
  { id: '6', accountNumber: 'TECH-CLEARING-CZK', accountType: 'CLEARING', glCode: '1101', name: 'Clearing Account CZK', currency: 'CZK', status: 'ACTIVE', balance: 0, normalBalance: 'DEBIT', isSuspense: false, isNostro: false, isVostro: false, openedAt: '2025-01-01' },
  { id: '7', accountNumber: 'TECH-CLEARING-EUR', accountType: 'CLEARING', glCode: '1102', name: 'Clearing Account EUR', currency: 'EUR', status: 'ACTIVE', balance: 0, normalBalance: 'DEBIT', isSuspense: false, isNostro: false, isVostro: false, openedAt: '2025-01-01' },
  { id: '8', accountNumber: 'TECH-SETTLEMENT', accountType: 'SETTLEMENT', glCode: '1201', name: 'CNB Settlement Account', currency: 'CZK', status: 'ACTIVE', balance: 5000000.00, normalBalance: 'DEBIT', isSuspense: false, isNostro: true, isVostro: false, correspondentBankBic: 'CNBACZPP', correspondentBankName: 'Česká národní banka', openedAt: '2025-01-01' },
  { id: '9', accountNumber: 'TECH-PROVISION', accountType: 'PROVISION', glCode: '5201', name: 'Loan Loss Provision', currency: 'CZK', status: 'ACTIVE', balance: 250000.00, normalBalance: 'CREDIT', isSuspense: false, isNostro: false, isVostro: false, openedAt: '2025-01-01' },
  { id: '10', accountNumber: 'TECH-TAX-VAT', accountType: 'TAX', glCode: '3401', name: 'VAT Payable Account', currency: 'CZK', status: 'ACTIVE', balance: 26340.00, normalBalance: 'CREDIT', isSuspense: false, isNostro: false, isVostro: false, openedAt: '2025-01-01' },
]

export default function TechnicalAccountsPage() {
  const { t } = useLanguage()
  const [accounts] = useState<TechnicalAccount[]>(SEED_ACCOUNTS)
  const [filter, setFilter] = useState('all')

  const groups = ['all', ...Array.from(new Set(accounts.map(a => a.accountType)))]
  const filtered = filter === 'all' ? accounts : accounts.filter(a => a.accountType === filter)

  const totalBalance = accounts.reduce((sum, a) => sum + (a.normalBalance === 'CREDIT' ? a.balance : -a.balance), 0)

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Technické účty', 'Technical Accounts')}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Building2 size={18} style={{ color: 'var(--accent)' }} />
            {t('Technické účty & GL', 'Technical Accounts & GL')}
          </h1>
          <p className="page-subtitle">{t('Nostro/Vostro · Suspense · Výnosy z poplatků · Zúčtování · Vypořádání · Opravné položky · Daně', 'Nostro/Vostro · Suspense · Fee Income · Clearing · Settlement · Provision · Tax')}</p>
        </div>
      </div>

      {/* Summary */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: '12px', marginBottom: '20px' }}>
        {[
          { label: t('Celkem účtů', 'Total accounts'), value: accounts.length, color: 'var(--accent)' },
          { label: t('Aktivní', 'Active'), value: accounts.filter(a => a.status === 'ACTIVE').length, color: 'var(--success)' },
          { label: t('Suspense', 'Suspense'), value: accounts.filter(a => a.isSuspense).length, color: '#d97706' },
          { label: t('Nostro/Vostro', 'Nostro/Vostro'), value: accounts.filter(a => a.isNostro || a.isVostro).length, color: '#2563eb' },
        ].map(s => (
          <div key={s.label} className="card" style={{ padding: '14px 16px' }}>
            <div style={{ fontSize: '22px', fontWeight: 700, color: s.color }}>{s.value}</div>
            <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>{s.label}</div>
          </div>
        ))}
      </div>

      {/* Type filter */}
      <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', marginBottom: '16px' }}>
        {groups.map(g => {
          const cfg = TYPE_LABELS[g]
          return (
            <button key={g} onClick={() => setFilter(g)} style={{
              padding: '5px 12px', fontSize: '11px', fontWeight: 600, borderRadius: '20px',
              border: `1px solid ${filter === g ? (cfg?.color || 'var(--accent)') : 'var(--border)'}`,
              background: filter === g ? (cfg?.color || 'var(--accent)') : 'var(--surface)',
              color: filter === g ? '#fff' : 'var(--text-secondary)',
              cursor: 'pointer', fontFamily: 'inherit',
            }}>{g === 'all' ? t('Vše', 'All') : (cfg?.label || g)}</button>
          )
        })}
      </div>

      {/* Table */}
      <div className="card" style={{ overflow: 'hidden' }}>
        <div style={{ overflowX: 'auto' }}>
          <table className="table">
            <thead>
              <tr>
                <th>{t('GL kód', 'GL Code')}</th>
                <th>{t('Název', 'Name')}</th>
                <th>{t('Typ', 'Type')}</th>
                <th>{t('Měna', 'Currency')}</th>
                <th>{t('Zůstatek', 'Balance')}</th>
                <th>{t('Norm. strana', 'Normal side')}</th>
                <th>{t('Příznaky', 'Flags')}</th>
                <th>{t('Korespondent', 'Correspondent')}</th>
                <th>{t('Status', 'Status')}</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(acc => {
                const typeCfg = TYPE_LABELS[acc.accountType] || { label: acc.accountType, color: '#6b7280' }
                return (
                  <tr key={acc.id}>
                    <td style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', fontWeight: 700, color: 'var(--accent)' }}>{acc.glCode}</td>
                    <td>
                      <div style={{ fontSize: '13px', fontWeight: 600 }}>{acc.name}</div>
                      <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace' }}>{acc.accountNumber}</div>
                    </td>
                    <td>
                      <span style={{ fontSize: '11px', fontWeight: 600, padding: '2px 8px', borderRadius: '4px', background: `${typeCfg.color}15`, color: typeCfg.color, border: `1px solid ${typeCfg.color}30` }}>
                        {typeCfg.label}
                      </span>
                    </td>
                    <td style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '12px' }}>{acc.currency}</td>
                    <td style={{ fontWeight: 700, color: acc.balance > 0 ? 'var(--text-primary)' : 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace', fontSize: '13px' }}>
                      {acc.balance > 0 ? (acc.normalBalance === 'CREDIT' ? <TrendingUp size={12} style={{ color: 'var(--success)', marginRight: '4px' }} /> : <TrendingDown size={12} style={{ color: 'var(--danger)', marginRight: '4px' }} />) : null}
                      {acc.balance.toLocaleString('cs-CZ', { minimumFractionDigits: 2 })}
                    </td>
                    <td>
                      <span style={{ fontSize: '11px', padding: '2px 6px', borderRadius: '4px', background: acc.normalBalance === 'CREDIT' ? '#f0fdf4' : '#fef2f2', color: acc.normalBalance === 'CREDIT' ? '#16a34a' : '#dc2626', fontWeight: 600 }}>
                        {acc.normalBalance}
                      </span>
                    </td>
                    <td style={{ fontSize: '11px' }}>
                      {acc.isSuspense && <span style={{ marginRight: '4px', padding: '1px 5px', background: '#fffbeb', color: '#d97706', borderRadius: '3px', border: '1px solid #fde68a', fontSize: '10px' }}>SUSPENSE</span>}
                      {acc.isNostro && <span style={{ marginRight: '4px', padding: '1px 5px', background: '#eff6ff', color: '#2563eb', borderRadius: '3px', border: '1px solid #bfdbfe', fontSize: '10px' }}>NOSTRO</span>}
                      {acc.isVostro && <span style={{ padding: '1px 5px', background: '#faf5ff', color: '#7c3aed', borderRadius: '3px', border: '1px solid #e9d5ff', fontSize: '10px' }}>VOSTRO</span>}
                    </td>
                    <td style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                      {acc.correspondentBankBic ? (
                        <div>
                          <div style={{ fontFamily: 'JetBrains Mono, monospace', fontWeight: 600 }}>{acc.correspondentBankBic}</div>
                          <div style={{ color: 'var(--text-tertiary)', fontSize: '10px' }}>{acc.correspondentBankName}</div>
                        </div>
                      ) : '—'}
                    </td>
                    <td><span className={acc.status === 'ACTIVE' ? 'pill pill-success' : 'pill pill-neutral'}>{acc.status}</span></td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
