// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, RefreshCw, Lock, Unlock, XCircle, AlertCircle } from 'lucide-react'
import { accountApi } from '@/lib/api'
import { EntityChip } from '@/components/entities/EntityChip'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { Account, AccountBalance } from '@/types'

const STATUS_PILL: Record<string, string> = {
  ACTIVE:             'pill pill-success',
  FROZEN:             'pill pill-info',
  DORMANT:            'pill pill-warning',
  CLOSED:             'pill pill-neutral',
  PENDING_ACTIVATION: 'pill pill-warning',
}

export default function AccountDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { t } = useLanguage()
  const [account, setAccount]   = useState<Account | null>(null)
  const [balance, setBalance]   = useState<AccountBalance | null>(null)
  const [loading, setLoading]   = useState(true)
  const [error, setError]       = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [acting, setActing]     = useState(false)

  async function load() {
    setLoading(true); setError(null)
    try {
      const [acc, bal] = await Promise.allSettled([
        accountApi.get(id),
        accountApi.getBalance(id),
      ])
      if (acc.status === 'fulfilled') setAccount(acc.value)
      else throw new Error(acc.reason?.message ?? 'Failed to load account')
      if (bal.status === 'fulfilled') setBalance(bal.value)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load account')
    } finally { setLoading(false) }
  }

  useEffect(() => { load() }, [id])

  async function doAction(action: 'freeze' | 'unfreeze' | 'close') {
    if (!account) return
    const reason = window.prompt(`Reason for ${action}:`)
    if (reason === null) return
    setActing(true); setActionError(null)
    try {
      if (action === 'freeze')   await accountApi.freeze(id, reason)
      if (action === 'unfreeze') await accountApi.unfreeze(id, reason)
      if (action === 'close')    await accountApi.close(id, reason)
      await load()
    } catch {
      // Never surface a raw backend message (could be a bare "HTTP 500") for a
      // user-initiated write — show a calm, localized human message instead.
      const human: Record<typeof action, string> = {
        freeze:   t('Účet se nepodařilo zablokovat. Zkuste to prosím znovu.', 'The account could not be frozen. Please try again.'),
        unfreeze: t('Účet se nepodařilo odblokovat. Zkuste to prosím znovu.', 'The account could not be unfrozen. Please try again.'),
        close:    t('Účet se nepodařilo zrušit. Zkuste to prosím znovu.', 'The account could not be closed. Please try again.'),
      }
      setActionError(human[action])
    } finally { setActing(false) }
  }

  if (loading) return (
    <div style={{ padding: '40px 0', color: 'var(--text-tertiary)', fontSize: '13px', display: 'flex', alignItems: 'center', gap: '8px' }}>
      <RefreshCw size={14} className="animate-spin" /> {t('Načítám účet…', 'Loading account…')}
    </div>
  )

  if (error) return (
    <div style={{ padding: '20px', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', borderRadius: 'var(--r-lg)', display: 'flex', gap: '10px' }}>
      <AlertCircle size={15} style={{ color: 'var(--danger)', flexShrink: 0 }}/>
      <span style={{ fontSize: '13px', color: 'var(--danger)' }}>{error}</span>
    </div>
  )

  if (!account) return null

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span>
            <span className="breadcrumb-sep">/</span>
            <Link href="/accounts" style={{ color: 'var(--text-tertiary)', textDecoration: 'none' }}>{t('Účty', 'Accounts')}</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current mono" style={{ fontSize: '12px' }}>{account.accountNumber}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <span className="mono">{account.accountNumber}</span>
            <span className={STATUS_PILL[account.status] ?? 'pill pill-neutral'}>{account.status}</span>
          </h1>
          <p className="page-subtitle">{account.accountType} · {account.currencyCode}</p>
        </div>
        <div style={{ display: 'flex', gap: '8px' }}>
          <Link href="/accounts" className="btn btn-secondary"><ArrowLeft size={13}/> {t('Zpět', 'Back')}</Link>
          {account.status === 'ACTIVE' && (
            <button className="btn btn-secondary" onClick={() => doAction('freeze')} disabled={acting}>
              <Lock size={13}/> {t('Zmrazit', 'Freeze')}
            </button>
          )}
          {account.status === 'FROZEN' && (
            <button className="btn btn-secondary" onClick={() => doAction('unfreeze')} disabled={acting}>
              <Unlock size={13}/> {t('Odzmrazit', 'Unfreeze')}
            </button>
          )}
          {account.status !== 'CLOSED' && (
            <button
              className="btn btn-secondary"
              onClick={() => doAction('close')}
              disabled={acting}
              style={{ color: 'var(--danger)', borderColor: 'var(--danger-border)' }}
            >
              <XCircle size={13}/> {t('Zrušit účet', 'Close')}
            </button>
          )}
        </div>
      </div>

      {actionError && (
        <div
          className="form-error"
          style={{
            marginBottom: '16px', padding: '12px 16px',
            background: 'var(--danger-bg)', border: '1px solid var(--danger-border)',
            borderRadius: 'var(--r-lg)', display: 'flex', alignItems: 'center', gap: '10px',
          }}
        >
          <AlertCircle size={15} style={{ color: 'var(--danger)', flexShrink: 0 }} />
          <span style={{ fontSize: '13px', color: 'var(--danger)' }}>{actionError}</span>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
        {/* Account info */}
        <div className="card">
          <div className="card-header"><span className="card-header-title">{t('Informace o účtu', 'Account Information')}</span></div>
          <div style={{ padding: '4px 0' }}>
            {[
              { label: t('ID účtu', 'Account ID'),         value: account.id,            mono: true },
              { label: t('Číslo účtu (IBAN)', 'Account Number (IBAN)'), value: account.accountNumber, mono: true },
              { label: 'BBAN',                              value: account.accountNumber.length > 4 ? account.accountNumber.slice(4) : account.accountNumber, mono: true },
              { label: t('Vlastník (klient)', 'Owner (party)'), node: <EntityChip type="party" id={account.partyId} /> },
              { label: 'Product ID',                        value: account.productId,     mono: true },
              { label: t('Typ', 'Type'),                   value: account.accountType },
              { label: t('Měna', 'Currency'),              value: account.currencyCode },
              { label: t('Stav', 'Status'),                value: account.status },
              { label: t('Otevřen', 'Opened'),             value: new Date(account.openedAt).toLocaleString('en-GB') },
              ...(account.closedAt ? [{ label: t('Uzavřen', 'Closed'), value: new Date(account.closedAt).toLocaleString('en-GB') }] : []),
            ].map((row, i, arr) => (
              <div key={row.label} style={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                padding: '10px 18px',
                borderBottom: i < arr.length - 1 ? '1px solid var(--border)' : 'none',
              }}>
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{row.label}</span>
                {'node' in row ? row.node : (
                  <span style={{
                    fontSize: '12px', fontWeight: 500, color: 'var(--text-primary)',
                    fontFamily: row.mono ? 'JetBrains Mono, monospace' : 'inherit',
                    maxWidth: '260px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  }}>
                    {row.value}
                  </span>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* Balance */}
        <div className="card">
          <div className="card-header"><span className="card-header-title">{t('Zůstatek', 'Balance')}</span></div>
          {balance ? (
            <div style={{ padding: '4px 0' }}>
              {[
                { label: t('Disponibilní zůstatek', 'Available Balance'), value: balance.availableBalance, highlight: true },
                { label: t('Účetní zůstatek', 'Current Balance'),         value: balance.currentBalance },
                { label: t('Rezervovaný zůstatek', 'Reserved Balance'),   value: balance.reservedBalance },
                { label: t('Čekající zůstatek', 'Pending Balance'),       value: balance.pendingBalance },
              ].map((row, i, arr) => (
                <div key={row.label} style={{
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                  padding: '12px 18px',
                  borderBottom: i < arr.length - 1 ? '1px solid var(--border)' : 'none',
                  background: row.highlight ? 'var(--accent-light)' : 'transparent',
                }}>
                  <span style={{ fontSize: '12px', color: row.highlight ? 'var(--accent)' : 'var(--text-secondary)', fontWeight: row.highlight ? 600 : 400 }}>
                    {row.label}
                  </span>
                  <span style={{
                    fontSize: row.highlight ? '16px' : '13px',
                    fontWeight: row.highlight ? 700 : 500,
                    color: row.highlight ? 'var(--accent)' : 'var(--text-primary)',
                    fontFamily: 'JetBrains Mono, monospace',
                  }}>
                    {Number(row.value).toLocaleString('en-US', { minimumFractionDigits: 2 })} {balance.currencyCode}
                  </span>
                </div>
              ))}
              <div style={{ padding: '10px 18px', borderTop: '1px solid var(--border)' }}>
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  {t('Naposledy aktualizováno:', 'Last updated:')} {new Date(balance.lastUpdatedAt).toLocaleString('en-GB')}
                </span>
              </div>
            </div>
          ) : (
            <div className="empty-state">{t('Zůstatek není k dispozici', 'Balance not available')}</div>
          )}
        </div>
      </div>
    </div>
  )
}
