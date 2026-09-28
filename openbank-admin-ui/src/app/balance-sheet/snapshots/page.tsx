// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Balance-sheet snapshot runs (risk-engine, ADR-0314; admin console #10618).
//
// A run pulls the ledger's trial balance and sub-ledgers for an as-of date and ties the
// contract-level positions out to it with zero tolerance. The list shows the most recent runs; the
// create form (ROLE_RISK / ROLE_ADMIN) asks the engine to build or replay one. risk-engine answers
// 201 for a new run and 200 for a replay of the same ledger inputs — the page says which.

'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { RefreshCw, Scale } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { getJson, riskUrl, sendJson } from '@/components/balance-sheet/api'
import { snapshotListSchema, snapshotRunSchema, type SnapshotSummary } from '@/components/balance-sheet/contracts'
import { isIsoDate } from '@/components/balance-sheet/model'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const PAGE_SIZE = 25

export default function SnapshotsPage() {
  return (
    <AuthGuard permission="balance-sheet:view">
      <Snapshots />
    </AuthGuard>
  )
}

function Snapshots() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const canCreate = hasPermission(session?.user?.roles ?? [], 'balance-sheet:snapshot:create')
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [runs, setRuns] = useState<SnapshotSummary[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [asOf, setAsOf] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string; runId?: string } | null>(null)

  const load = useCallback(async () => {
    const res = await getJson(riskUrl('/api/v1/risk/snapshots', { limit: String(PAGE_SIZE) }), snapshotListSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setRuns(null); return }
    setUnavailable(null)
    setRuns(res.data.runs)
  }, [])

  useEffect(() => { void load() }, [load])

  const create = async () => {
    if (!isIsoDate(asOf)) {
      setNotice({ tone: 'danger', text: t('Zadejte platné datum (RRRR-MM-DD).', 'Enter a valid date (YYYY-MM-DD).') })
      return
    }
    setBusy(true)
    setNotice(null)
    const res = await sendJson(riskUrl('/api/v1/risk/snapshots'), { asOf }, snapshotRunSchema)
    setBusy(false)
    if (res.ok) {
      setNotice({
        tone: 'success',
        runId: res.data.id,
        text: res.data.status === 'TIED_OUT'
          ? t('Snímek je odsouhlasen s hlavní knihou.', 'Snapshot tied out to the ledger.')
          : t('Snímek NENÍ odsouhlasen — pozice se nezobrazují, viz rozdíly.', 'Snapshot did NOT tie out — positions are withheld, see the mismatches.'),
      })
      void load()
      return
    }
    setNotice({
      tone: 'danger',
      text: res.kind === 'forbidden'
        ? t('Snímek může vytvořit jen útvar rizik (ROLE_RISK) nebo administrátor.', 'Only the risk department (ROLE_RISK) or an administrator may create a snapshot.')
        : res.kind === 'refused'
          ? t(`risk-engine žádost odmítl: ${res.message ?? ''}`, `risk-engine refused the request: ${res.message ?? ''}`)
          : t('risk-engine je nedostupný.', 'risk-engine is unavailable.'),
    })
  }

  return (
    <div>
      <PageHeader
        title={t('Snímky rozvahy', 'Balance-sheet snapshots')}
        subtitle={t('Pozice na úrovni smluv odsouhlasené s hlavní knihou k datu (ADR-0314).', 'Contract-level positions tied out to the ledger as of a date (ADR-0314).')}
        icon={<Scale size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />

      {canCreate && (
        <div className="card" style={{ marginBottom: 16 }}>
          <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('Nový snímek', 'New snapshot')}</h2>
          <div style={{ display: 'flex', gap: 8, alignItems: 'end', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
              {t('K datu', 'As of')}
              <input type="date" className="input" value={asOf} onChange={e => setAsOf(e.target.value)} aria-label={t('Datum snímku', 'Snapshot as-of date')} />
            </label>
            <button type="button" className="btn btn-primary btn-sm" disabled={busy || !asOf} onClick={() => void create()}>
              {busy ? t('Sestavuji…', 'Building…') : t('Sestavit snímek', 'Build snapshot')}
            </button>
          </div>
          <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>
            {t('Stejná data hlavní knihy ke stejnému datu vrátí existující běh (replay), nic nového nevznikne.', 'The same ledger data at the same date returns the existing run (a replay); nothing new is stored.')}
          </p>
        </div>
      )}

      {notice && (
        <div role="status" className="card" style={{ marginBottom: 16, borderColor: notice.tone === 'danger' ? 'var(--danger)' : 'var(--success)' }}>
          {notice.text}{' '}
          {notice.runId && <Link href={`/balance-sheet/snapshots/${notice.runId}`}>{t('Otevřít běh', 'Open run')}</Link>}
        </div>
      )}

      {unavailable ? (
        <DataUnavailable kind={unavailable.kind} service="risk-engine" feature={t('snímky rozvahy', 'balance-sheet snapshots')} lang={language} />
      ) : runs === null ? null : runs.length === 0 ? (
        <DataUnavailable kind="no_data" service="risk-engine" feature={t('snímky rozvahy', 'balance-sheet snapshots')} lang={language} dense />
      ) : (
        <div className="card" style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('K datu', 'As of')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Původ dat', 'Provenance')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Pozice', 'Positions')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Rozdíly', 'Mismatches')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Zaznamenáno', 'Recorded')}</th>
              </tr>
            </thead>
            <tbody>
              {runs.map(run => (
                <tr key={run.id}>
                  <td><Link href={`/balance-sheet/snapshots/${run.id}`}>{run.asOf}</Link></td>
                  <td>
                    <StatusBadge
                      status={run.status}
                      tone={run.status === 'TIED_OUT' ? 'success' : 'danger'}
                      label={run.status === 'TIED_OUT' ? t('Odsouhlaseno', 'Tied out') : t('Neodsouhlaseno', 'Untied')}
                    />
                  </td>
                  <td><ProvenanceBadge provenance={run.provenance} /></td>
                  <td style={{ textAlign: 'right' }}>{run.positionCount.toLocaleString(locale)}</td>
                  <td style={{ textAlign: 'right' }}>{run.mismatchCount.toLocaleString(locale)}</td>
                  <td>{new Date(run.recordedAt).toLocaleString(locale)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>
            {t(`Zobrazeno nejnovějších ${runs.length} (limit ${PAGE_SIZE}).`, `Showing the latest ${runs.length} (limit ${PAGE_SIZE}).`)}
          </p>
        </div>
      )}
    </div>
  )
}
