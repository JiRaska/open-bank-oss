// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useCallback, useState } from 'react'
import { AlertTriangle, ShieldAlert, TrendingUp, Search, CheckCircle2, RefreshCw, Percent, Calendar } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'
import { PageHeader } from '@/components/ui/PageHeader'

interface AccrualRecord {
  id: string; accountId: string; accrualDate: string; accruedAmount: number
  currency: string; rate: number; dayCount: string; status: string
}

type AccessBlock = 'unauthorized' | 'forbidden'

function InterestAccessDenied({ language }: { language: 'cs' | 'en' }) {
  const cs = language === 'cs'
  return (
    <div
      role="alert"
      aria-live="assertive"
      style={{
        padding: '40px', textAlign: 'center', display: 'flex', flexDirection: 'column',
        alignItems: 'center', gap: '10px',
      }}
    >
      <ShieldAlert size={28} aria-hidden="true" style={{ color: 'var(--danger)' }} />
      <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>
        {cs ? 'Přístup odepřen' : 'Access denied'}
      </div>
      <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', maxWidth: '460px', lineHeight: 1.5 }}>
        {cs
          ? 'Vaše aktuální role nemá oprávnění zobrazit tato data. Žádná dříve načtená data se nezobrazují.'
          : 'Your current role does not have permission to view this data. No previously loaded data is shown.'}
      </div>
    </div>
  )
}

export default function InterestPage() {
  const { t, language } = useLanguage()
  const [search, setSearch] = useState('')
  const [lastSuccessfulAt, setLastSuccessfulAt] = useState<Date | null>(null)
  const [retainedAccessBlock, setRetainedAccessBlock] = useState<{
    kind: AccessBlock
    snapshot: AccrualRecord[] | null
  } | null>(null)
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const { data, loading, unavailable, waking, reload } = useServiceResource<AccrualRecord[]>(
    svcUrl('interest-service', '/api/v1/interest/accruals'),
    { select: (raw) => {
      setLastSuccessfulAt(new Date())
      return Array.isArray(raw) ? (raw as AccrualRecord[]) : ((raw as { accruals?: AccrualRecord[] }).accruals ?? [])
    } },
  )
  const accruals = data ?? []
  const hasSnapshot = data !== null
  const currentAccessBlock: AccessBlock | null = unavailable?.kind === 'unauthorized'
    ? 'unauthorized'
    : unavailable?.status === 403 ? 'forbidden' : null
  const persistedAccessBlock = retainedAccessBlock?.snapshot === data ? retainedAccessBlock.kind : null
  const accessBlock = currentAccessBlock ?? persistedAccessBlock
  const visibleSnapshot = hasSnapshot && accessBlock === null
  const settledFailure = unavailable !== null && !loading && !waking
  const showingRetainedSnapshot = settledFailure && visibleSnapshot

  const requestReload = useCallback(() => {
    if (loading) return
    setRetainedAccessBlock(currentAccessBlock
      ? { kind: currentAccessBlock, snapshot: data }
      : null)
    reload()
  }, [currentAccessBlock, data, loading, reload])

  const filtered = accruals.filter(a =>
    a.accountId?.toLowerCase().includes(search.toLowerCase()) ||
    a.status?.toLowerCase().includes(search.toLowerCase()) ||
    a.dayCount?.toLowerCase().includes(search.toLowerCase())
  )

  const accruing = accruals.filter(a => a.status === 'ACCRUING')
  const capitalized = accruals.filter(a => a.status === 'CAPITALIZED')
  const totalAccrued = accruals.reduce((s, a) => s + (a.accruedAmount ?? 0), 0)

  return (
    <AuthGuard permission="payments:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <PageHeader
          icon={<Percent size={20} aria-hidden="true" />}
          title={t('Úrokové výpočty', 'Interest Calculations')}
          subtitle={t('Akruální účetnictví — ACT/365 · ACT/360 · kapitalizace', 'Accrual accounting — ACT/365 · ACT/360 · capitalisation')}
          actions={<div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {accessBlock === null && (
              <ServiceStatusBadge
                label="interest-service :8125"
                loading={loading}
                waking={waking}
                unavailable={unavailable}
                copy={{
                  up: t('interest-service běží', 'interest-service is up'),
                  idle: t('interest-service spí (scale-to-zero), probouzí se…', 'interest-service idle (scaled to zero), waking…'),
                  down: t('interest-service neodpovídá', 'interest-service is not responding'),
                  checking: t('Zjišťuji stav služby…', 'Checking service…'),
                }}
              />
            )}
            <button
              type="button"
              onClick={requestReload}
              disabled={loading}
              aria-busy={loading}
              aria-label={settledFailure
                ? t('Zkusit znovu načíst úrokové záznamy', 'Retry loading interest records')
                : t('Obnovit úrokové záznamy', 'Refresh interest records')}
              className="btn btn-secondary btn-sm"
            >
              <RefreshCw size={14} aria-hidden="true" className={loading ? 'animate-spin' : ''} />
              {settledFailure ? t('Zkusit znovu', 'Try again') : t('Obnovit', 'Refresh')}
            </button>
          </div>}
        />

        {showingRetainedSnapshot && (
          <div
            role="status"
            aria-live="polite"
            aria-label={t('Aktuálnost úrokových dat', 'Interest data freshness')}
            style={{
              marginBottom: 20, padding: '14px 16px', borderRadius: 10,
              border: '1px solid var(--warning-border)', background: 'var(--warning-bg)',
              color: 'var(--text-primary)', display: 'flex', alignItems: 'flex-start', gap: 10,
            }}
          >
            <AlertTriangle size={18} aria-hidden="true" style={{ color: 'var(--warning)', flexShrink: 0, marginTop: 1 }} />
            <div>
              <div style={{ fontSize: 13, fontWeight: 700 }}>
                {t(
                  'Obnovení selhalo — zobrazuji poslední úspěšný snapshot.',
                  'Refresh failed — showing the last successful snapshot.',
                )}
              </div>
              <div style={{ marginTop: 3, fontSize: 12, color: 'var(--text-secondary)' }}>
                {lastSuccessfulAt && <>
                  {t('Poslední úspěšné načtení', 'Last successful load')}: {lastSuccessfulAt.toLocaleString(numberLocale)}.{' '}
                </>}
                {t(
                  'Naakruované částky i stavy se od té doby mohly změnit.',
                  'Accrued amounts and statuses may have changed since then.',
                )}
              </div>
            </div>
          </div>
        )}

        {loading && visibleSnapshot && (
          <p role="status" aria-live="polite" style={{ margin: '0 0 12px', color: 'var(--text-tertiary)', fontSize: 11 }}>
            {t(
              'Aktualizuji úrokové záznamy; poslední snapshot zůstává dostupný.',
              'Refreshing interest records; the last snapshot remains available.',
            )}
          </p>
        )}

        {visibleSnapshot && <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Záznamy celkem', 'Total records'), value: accruals.length, icon: <TrendingUp size={16} aria-hidden="true" />, color: 'var(--accent)' },
            { label: t('Akruuje', 'Accruing'), value: accruing.length, icon: <Percent size={16} aria-hidden="true" />, color: 'var(--warning)' },
            { label: t('Kapitalizováno', 'Capitalised'), value: capitalized.length, icon: <CheckCircle2 size={16} aria-hidden="true" />, color: 'var(--success)' },
            { label: t('Celkem naakruováno', 'Total accrued'), value: totalAccrued.toLocaleString(numberLocale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }), icon: <Calendar size={16} aria-hidden="true" />, color: 'var(--accent-2)' },
          ].map(k => (
            <div key={k.label} className="stat-card">
              <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: `${k.color}18`,
                display: 'flex', alignItems: 'center', justifyContent: 'center', color: k.color, marginBottom: '10px' }}>{k.icon}</div>
              <div style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>{k.value}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{k.label}</div>
            </div>
          ))}
        </div>}

        <div className="card">
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '10px', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={13} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat účet, status, day count…', 'Search account, status, day count…')}
                aria-label={t('Hledat v úrokových výpočtech', 'Search interest calculations')}
                style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                  border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
            </div>
          </div>
          {accessBlock === 'forbidden' ? (
            <InterestAccessDenied language={language} />
          ) : accessBlock === 'unauthorized' ? (
            <DataUnavailable kind="unauthorized" service={t('Interest-service', 'Interest-service')} feature={t('Úrokové záznamy', 'Interest records')} lang={language} />
          ) : loading && !visibleSnapshot ? (
            <div role="status" aria-live="polite" style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám…', 'Loading…')}</div>
            </div>
          ) : unavailable && !visibleSnapshot ? (
            <DataUnavailable kind={unavailable.kind} service={t('Interest-service', 'Interest-service')} feature={t('Úrokové záznamy', 'Interest records')} lang={language} />
          ) : filtered.length === 0 ? (
            <DataUnavailable kind="no_data" feature={t('Úrokové záznamy', 'Interest records')} lang={language}
              detail={accruals.length === 0
                ? showingRetainedSnapshot
                  ? t('Poslední úspěšný snapshot neobsahoval žádné úrokové záznamy.', 'The last successful snapshot contained no interest records.')
                  : t('Služba běží, zatím žádné úrokové záznamy.', 'The service is running; no interest records yet.')
                : t('Žádné výsledky pro zadaný filtr.', 'No results for the applied filter.')} />
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                {[t('Účet', 'Account'), t('Datum', 'Date'), t('Naakruováno', 'Accrued'), t('Měna', 'Currency'), t('Sazba', 'Rate'), t('Day Count', 'Day Count'), t('Status', 'Status')].map(h => (
                  <th key={h} style={{ padding: '10px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>
                ))}
              </tr></thead>
              <tbody>{filtered.map(a => (
                <tr key={a.id} style={{ borderBottom: '1px solid var(--border)' }}
                  onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                  onMouseLeave={e => (e.currentTarget.style.background = '')}>
                  <td style={{ padding: '12px 16px', fontSize: '12px', fontFamily: 'var(--font-mono)', color: 'var(--text-primary)' }}>{a.accountId}</td>
                  <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{a.accrualDate}</td>
                  <td style={{ padding: '12px 16px', fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{(a.accruedAmount ?? 0).toLocaleString(numberLocale, { minimumFractionDigits: 4 })}</td>
                  <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{a.currency}</td>
                  <td style={{ padding: '12px 16px', fontSize: '12px', fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>{((a.rate ?? 0) * 100).toFixed(4)}%</td>
                  <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{a.dayCount}</td>
                  <td style={{ padding: '12px 16px' }}>
                    <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600,
                      background: a.status === 'CAPITALIZED' ? 'var(--success-bg)' : 'var(--warning-bg)',
                      color: a.status === 'CAPITALIZED' ? 'var(--success-text)' : 'var(--warning-text)',
                      border: `1px solid ${a.status === 'CAPITALIZED' ? 'var(--success-border)' : 'var(--warning-border)'}` }}>{a.status}</span>
                  </td>
                </tr>
              ))}</tbody>
            </table>
          )}
        </div>
      </div>
    </AuthGuard>
  )
}
