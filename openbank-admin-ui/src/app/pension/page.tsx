// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pension contracts (ADR-0334): open a contract by id, or list contracts by status. The id is
// validated as a UUID before any call (search rule #2). The list is pension-service's staff route
// GET /contracts?status= (API 1.1.0), newest first.

'use client'

import { useCallback, useEffect, useState, type FormEvent } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { PiggyBank } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { getJson, PENSION, pensionUrl } from '@/components/pension/api'
import { CONTRACT_STATUSES, pensionContractListSchema, type PensionContract } from '@/components/pension/contracts'
import { isUuid, statusLabel } from '@/components/pension/model'
import { PAGE_SIZE } from '@/components/pension/PensionQueue'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionContractsPage() {
  return (
    <AuthGuard permission="pension:view">
      <ContractSearch />
    </AuthGuard>
  )
}

function ContractSearch() {
  const { t, language } = useLanguage()
  const router = useRouter()
  const [query, setQuery] = useState('')
  const [hint, setHint] = useState<string | null>(null)
  const [status, setStatus] = useState<string>('ACTIVE')
  const [rows, setRows] = useState<PensionContract[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [shown, setShown] = useState(PAGE_SIZE)

  const load = useCallback(async () => {
    const res = await getJson(pensionUrl('/contracts', { status, limit: String(PAGE_SIZE * 4) }), pensionContractListSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setRows(null); return }
    setUnavailable(null)
    setRows(res.data)
    setShown(PAGE_SIZE)
  }, [status])

  useEffect(() => { void load() }, [load])

  const open = (e: FormEvent) => {
    e.preventDefault()
    if (!isUuid(query)) { setHint(t('Zadejte ID smlouvy ve formátu UUID.', 'Enter the contract id as a UUID.')); return }
    setHint(null)
    router.push(`/pension/contracts/${encodeURIComponent(query.trim())}`)
  }

  return (
    <div>
      <PageHeader
        title={t('Penzijní smlouvy', 'Pension contracts')}
        subtitle={t('Doplňkové penzijní spoření (DPS) a dlouhodobý investiční produkt (DIP).', 'Supplementary pension savings (DPS) and long-term investment product (DIP).')}
        icon={<PiggyBank size={20} aria-hidden="true" />}
      />

      <form className="card" onSubmit={open} style={{ marginBottom: 16, display: 'flex', gap: 8, alignItems: 'flex-start', flexWrap: 'wrap' }}>
        <div style={{ flex: '1 1 320px' }}>
          <input
            className="input"
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder={t('ID smlouvy (UUID)', 'Contract id (UUID)')}
            aria-label={t('ID smlouvy', 'Contract id')}
            style={{ width: '100%' }}
          />
          {hint && <div role="alert" style={{ fontSize: 12, color: 'var(--danger-text)', marginTop: 4 }}>{hint}</div>}
        </div>
        <button type="submit" className="btn btn-primary btn-sm">{t('Otevřít', 'Open')}</button>
      </form>

      <div className="card" style={{ overflowX: 'auto' }}>
        <label style={{ fontSize: 13, display: 'inline-flex', gap: 8, alignItems: 'center', marginBottom: 12 }}>
          {t('Stav', 'Status')}
          <select className="input" value={status} onChange={e => setStatus(e.target.value)} aria-label={t('Filtr stavu', 'Status filter')}>
            {CONTRACT_STATUSES.map(s => <option key={s} value={s}>{statusLabel(s, t)}</option>)}
          </select>
        </label>
        {unavailable ? (
          <DataUnavailable kind={unavailable.kind} service={PENSION} feature={t('seznam penzijních smluv', 'pension contract list')} lang={language} dense />
        ) : rows === null ? null : rows.length === 0 ? (
          <DataUnavailable kind="no_data" service={PENSION} feature={t('seznam penzijních smluv', 'pension contract list')} lang={language} dense />
        ) : (
          <>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Smlouva', 'Contract')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Produkt', 'Product')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Strategie', 'Strategy')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Počátek', 'Start')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.slice(0, shown).map(c => (
                  <tr key={c.contractId}>
                    <td><Link href={`/pension/contracts/${encodeURIComponent(c.contractId)}`}>{c.contractId}</Link></td>
                    <td>{`${c.productLine} · ${c.jurisdiction}`}</td>
                    <td>{statusLabel(c.status, t)}</td>
                    <td>{c.currentStrategy?.strategyCode ?? '—'}</td>
                    <td>{c.startDate ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {rows.length > shown && (
              <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 12 }} onClick={() => setShown(s => s + PAGE_SIZE)}>
                {t('Načíst další', 'Load more')}
              </button>
            )}
          </>
        )}
      </div>
    </div>
  )
}
