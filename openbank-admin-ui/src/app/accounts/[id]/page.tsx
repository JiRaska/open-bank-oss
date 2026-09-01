// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useRef, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, RefreshCw, Lock, Unlock, XCircle, AlertCircle } from 'lucide-react'
import { accountApi } from '@/lib/api'
import { EntityChip } from '@/components/entities/EntityChip'
import { Can } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader } from '@/components/ui/PageHeader'
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
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [account, setAccount]   = useState<Account | null>(null)
  const [balance, setBalance]   = useState<AccountBalance | null>(null)
  const [loading, setLoading]   = useState(true)
  const [error, setError]       = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [acting, setActing]     = useState(false)
  const actionInFlight = useRef(false)
  const [actionIntent, setActionIntent] = useState<'freeze' | 'unfreeze' | 'close' | null>(null)
  const [actionReason, setActionReason] = useState('')

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

  function requestAction(action: 'freeze' | 'unfreeze' | 'close') {
    setActionIntent(action)
    setActionReason('')
    setActionError(null)
  }

  async function doAction(action: 'freeze' | 'unfreeze' | 'close', reason: string) {
    if (!account || actionInFlight.current) return
    const normalizedReason = reason.trim()
    if (!normalizedReason) {
      setActionError(t('Pro tuto změnu uveďte důvod do auditní stopy.', 'Provide a reason for this change in the audit trail.'))
      return
    }
    actionInFlight.current = true
    setActing(true); setActionError(null)
    try {
      if (action === 'freeze')   await accountApi.freeze(id, normalizedReason)
      if (action === 'unfreeze') await accountApi.unfreeze(id, normalizedReason)
      if (action === 'close')    await accountApi.close(id, normalizedReason)
      await load()
      setActionIntent(null)
      setActionReason('')
    } catch {
      // Never surface a raw backend message (could be a bare "HTTP 500") for a
      // user-initiated write — show a calm, localized human message instead.
      const human: Record<typeof action, string> = {
        freeze:   t('Účet se nepodařilo zablokovat. Zkuste to prosím znovu.', 'The account could not be frozen. Please try again.'),
        unfreeze: t('Účet se nepodařilo odblokovat. Zkuste to prosím znovu.', 'The account could not be unfrozen. Please try again.'),
        close:    t('Účet se nepodařilo zrušit. Zkuste to prosím znovu.', 'The account could not be closed. Please try again.'),
      }
      setActionError(human[action])
    } finally {
      actionInFlight.current = false
      setActing(false)
    }
  }

  if (loading) return (
    <div role="status" aria-live="polite" style={{ padding: '40px 0', color: 'var(--text-tertiary)', fontSize: '13px', display: 'flex', alignItems: 'center', gap: '8px' }}>
      <RefreshCw size={14} aria-hidden="true" className="animate-spin" /> {t('Načítám účet…', 'Loading account…')}
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
      <PageHeader
        title={account.accountNumber}
        subtitle={`${account.accountType} · ${account.currencyCode}`}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/accounts" style={{ color: 'var(--text-tertiary)', textDecoration: 'none' }}>{t('Účty', 'Accounts')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current mono" style={{ fontSize: '12px' }}>{account.accountNumber}</span></div>}
        actions={<div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          <span className={STATUS_PILL[account.status] ?? 'pill pill-neutral'}>{account.status}</span>
          <Link href="/accounts" className="btn btn-secondary"><ArrowLeft size={13} aria-hidden="true"/> {t('Zpět', 'Back')}</Link>
          {account.status === 'ACTIVE' && (
            <Can permission="accounts:freeze">
              <button type="button" className="btn btn-secondary" onClick={() => requestAction('freeze')} disabled={acting} aria-busy={acting} aria-label={t('Zmrazit účet', 'Freeze account')}>
                <Lock size={13} aria-hidden="true"/> {t('Zmrazit', 'Freeze')}
              </button>
            </Can>
          )}
          {account.status === 'FROZEN' && (
            <Can permission="accounts:freeze">
              <button type="button" className="btn btn-secondary" onClick={() => requestAction('unfreeze')} disabled={acting} aria-busy={acting} aria-label={t('Odmrazit účet', 'Unfreeze account')}>
                <Unlock size={13} aria-hidden="true"/> {t('Odzmrazit', 'Unfreeze')}
              </button>
            </Can>
          )}
          {account.status !== 'CLOSED' && (
            <Can permission="accounts:close">
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => requestAction('close')}
                disabled={acting}
                aria-busy={acting}
                aria-label={t('Zrušit účet', 'Close account')}
                style={{ color: 'var(--danger)', borderColor: 'var(--danger-border)' }}
              >
                <XCircle size={13} aria-hidden="true"/> {t('Zrušit účet', 'Close')}
              </button>
            </Can>
          )}
        </div>}
      />

      {actionIntent && (
        <section
          aria-labelledby="account-action-title"
          aria-describedby="account-action-description"
          style={{
            marginBottom: '16px', padding: '16px', background: 'var(--surface)',
            border: '1px solid var(--border)', borderRadius: 'var(--r-lg)',
            boxShadow: '0 12px 28px rgba(15,23,42,0.12)',
          }}
        >
          <h2 id="account-action-title" style={{ margin: 0, fontSize: '15px', color: 'var(--text-primary)' }}>
            {t(
              actionIntent === 'freeze' ? 'Zmrazit účet' : actionIntent === 'unfreeze' ? 'Odmrazit účet' : 'Zrušit účet',
              actionIntent === 'freeze' ? 'Freeze account' : actionIntent === 'unfreeze' ? 'Unfreeze account' : 'Close account',
            )}
          </h2>
          <p id="account-action-description" style={{ margin: '6px 0 12px', fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
            {t('Uveďte důvod. Důvod se uloží do auditní stopy této změny.', 'Provide a reason. It will be recorded in this change’s audit trail.')}
          </p>
          <label htmlFor="account-action-reason" style={{ display: 'block', fontSize: '12px', fontWeight: 650, color: 'var(--text-primary)', marginBottom: '6px' }}>
            {t('Důvod změny', 'Reason for change')}
          </label>
          <textarea
            id="account-action-reason"
            className="input"
            rows={3}
            autoFocus
            value={actionReason}
            onChange={event => setActionReason(event.target.value)}
            placeholder={t('Např. žádost klienta / podezření na zneužití…', 'E.g. customer request / suspected abuse…')}
          />
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginTop: '10px' }}>
            <button type="button" className="btn btn-secondary" onClick={() => { setActionIntent(null); setActionReason(''); setActionError(null) }} disabled={acting}>
              {t('Zrušit', 'Cancel')}
            </button>
            <button type="button" className="btn btn-primary" onClick={() => doAction(actionIntent, actionReason)} disabled={acting || !actionReason.trim()} aria-busy={acting}>
              {acting ? t('Ukládám…', 'Saving…') : t('Potvrdit změnu', 'Confirm change')}
            </button>
          </div>
        </section>
      )}

      {actionError && (
        <div
          role="alert"
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
              { label: t('Otevřen', 'Opened'),             value: new Date(account.openedAt).toLocaleString(numberLocale) },
              ...(account.closedAt ? [{ label: t('Uzavřen', 'Closed'), value: new Date(account.closedAt).toLocaleString(numberLocale) }] : []),
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
                    {Number(row.value).toLocaleString(numberLocale, { minimumFractionDigits: 2 })} {balance.currencyCode}
                  </span>
                </div>
              ))}
              <div style={{ padding: '10px 18px', borderTop: '1px solid var(--border)' }}>
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  {t('Naposledy aktualizováno:', 'Last updated:')} {new Date(balance.lastUpdatedAt).toLocaleString(numberLocale)}
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
