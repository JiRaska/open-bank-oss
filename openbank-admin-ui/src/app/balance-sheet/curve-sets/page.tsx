// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Yield-curve sets (risk-engine, ADR-0313 D4; admin console #10618).
//
// The risk department uploads money-market quotes per index and tenor; risk-engine bootstraps them
// into zero curves. A curve set is an INPUT to every PV and projection, so who supplied it must be
// a person: OPA refuses every service account, and only ROLE_RISK / ROLE_ADMIN see the form.
// Provenance is chosen explicitly on every upload — there is no default that could quietly label
// synthetic quotes as production (ADR-0313 D13).

'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { RefreshCw, TrendingUp as CurveIcon } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { getJson, riskUrl, sendJson } from '@/components/balance-sheet/api'
import { curveSetListSchema, curveSetSchema, type CurveSetSummary } from '@/components/balance-sheet/contracts'
import { CurveUploadForm } from '@/components/balance-sheet/CurveUploadForm'
import { explainServerError, indexInfo, isSandboxEnvironment, toPayload, type FormState } from '@/components/balance-sheet/curveForm'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const PAGE_SIZE = 25
// Same environment tag the regulatory page trusts; only an explicit production build hides the
// sandbox sample-fill helper, and that helper always forces provenance = synthetic.
const SANDBOX = isSandboxEnvironment(process.env.NEXT_PUBLIC_GLITCHTIP_ENVIRONMENT)

export default function CurveSetsPage() {
  return (
    <AuthGuard permission="balance-sheet:view">
      <CurveSets />
    </AuthGuard>
  )
}

function CurveSets() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const canUpload = hasPermission(session?.user?.roles ?? [], 'balance-sheet:curves:upload')
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [sets, setSets] = useState<CurveSetSummary[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string; id?: string } | null>(null)

  const load = useCallback(async () => {
    const res = await getJson(riskUrl('/api/v1/risk/curve-sets', { limit: String(PAGE_SIZE) }), curveSetListSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setSets(null); return }
    setUnavailable(null)
    setSets(res.data.curveSets)
  }, [])

  useEffect(() => { void load() }, [load])

  const upload = async (state: FormState): Promise<boolean> => {
    setBusy(true)
    setNotice(null)
    const res = await sendJson(riskUrl('/api/v1/risk/curve-sets'), toPayload(state), curveSetSchema)
    setBusy(false)
    if (res.ok) {
      setNotice({ tone: 'success', id: res.data.id, text: t('Sada křivek uložena.', 'Curve set stored.') })
      void load()
      return true
    }
    setNotice({
      tone: 'danger',
      text: res.kind === 'forbidden'
        ? t('Sadu křivek může nahrát jen útvar rizik (ROLE_RISK) nebo administrátor.', 'Only the risk department (ROLE_RISK) or an administrator may upload a curve set.')
        : res.kind === 'refused'
          ? explainServerError(res.message, language === 'cs' ? 'cs' : 'en')
          : t('risk-engine je nedostupný.', 'risk-engine is unavailable.'),
    })
    return false
  }

  return (
    <div>
      <PageHeader
        title={t('Výnosové křivky', 'Curve sets')}
        subtitle={t('Kotace peněžního trhu převedené na nulové křivky (ADR-0313 D4).', 'Money-market quotes bootstrapped into zero curves (ADR-0313 D4).')}
        icon={<CurveIcon size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />

      {canUpload && <CurveUploadForm sandbox={SANDBOX} busy={busy} onSubmit={upload} />}

      {notice && (
        <div role="status" className="card" style={{ marginBottom: 16, borderColor: notice.tone === 'danger' ? 'var(--danger)' : 'var(--success)' }}>
          {notice.text}{' '}
          {notice.id && <Link href={`/balance-sheet/curve-sets/${notice.id}`}>{t('Otevřít sadu', 'Open set')}</Link>}
        </div>
      )}

      {unavailable ? (
        <DataUnavailable kind={unavailable.kind} service="risk-engine" feature={t('sady výnosových křivek', 'curve sets')} lang={language} />
      ) : sets === null ? null : sets.length === 0 ? (
        <DataUnavailable kind="no_data" service="risk-engine" feature={t('sady výnosových křivek', 'curve sets')} lang={language} dense />
      ) : (
        <div className="card" style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('K datu', 'As of')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Původ dat', 'Provenance')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Zdroj', 'Source')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Křivky', 'Curves')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Počet křivek', 'Curve count')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Zaznamenáno', 'Recorded')}</th>
              </tr>
            </thead>
            <tbody>
              {sets.map(s => (
                <tr key={s.id}>
                  <td><Link href={`/balance-sheet/curve-sets/${s.id}`}>{s.asOf}</Link></td>
                  <td><ProvenanceBadge provenance={s.provenance} /></td>
                  <td>{s.source}</td>
                  <td>{s.indices.map(i => indexInfo(i)?.label ?? i).join(', ')}</td>
                  <td style={{ textAlign: 'right' }}>{s.indices.length}</td>
                  <td>{new Date(s.recordedAt).toLocaleString(locale)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
