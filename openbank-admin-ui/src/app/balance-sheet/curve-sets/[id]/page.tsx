// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One stored curve set: pillars, zero rates and discount factors per index (ADR-0313 D4, #10618).

'use client'

import { use, useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, TrendingUp } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { getJson, riskUrl } from '@/components/balance-sheet/api'
import { curveSetSchema, type CurveSet } from '@/components/balance-sheet/contracts'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function CurveSetDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  return (
    <AuthGuard permission="balance-sheet:view">
      <CurveSetDetail id={id} />
    </AuthGuard>
  )
}

function CurveSetDetail({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [set, setSet] = useState<CurveSet | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const load = useCallback(async () => {
    const res = await getJson(riskUrl(`/api/v1/risk/curve-sets/${encodeURIComponent(id)}`), curveSetSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); return }
    setUnavailable(null)
    setSet(res.data)
  }, [id])

  useEffect(() => { void load() }, [load])

  const back = (
    <Link href="/balance-sheet/curve-sets" className="btn btn-secondary btn-sm" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <ArrowLeft size={14} aria-hidden="true" /> {t('Zpět na křivky', 'Back to curve sets')}
    </Link>
  )

  return (
    <div>
      <PageHeader
        title={set ? t(`Sada křivek k ${set.asOf}`, `Curve set as of ${set.asOf}`) : t('Sada křivek', 'Curve set')}
        subtitle={set ? t(`Zdroj: ${set.source}`, `Source: ${set.source}`) : undefined}
        icon={<TrendingUp size={20} aria-hidden="true" />}
        actions={back}
      />
      {unavailable && (
        <DataUnavailable kind={unavailable.kind} service="risk-engine" feature={t('sada výnosových křivek', 'curve set')} lang={language} />
      )}
      {set && (
        <>
          <div style={{ marginBottom: 16 }}><ProvenanceBadge provenance={set.provenance} /></div>
          {set.curves.map(curve => (
            <div key={curve.index} className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
              <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{`${curve.index} · ${curve.currency}`}</h2>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead>
                  <tr>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Pilíř', 'Pillar')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Nulová sazba', 'Zero rate')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Diskontní faktor', 'Discount factor')}</th>
                  </tr>
                </thead>
                <tbody>
                  {curve.pillars.map(p => (
                    <tr key={p.date}>
                      <td>{p.date}</td>
                      <td style={{ textAlign: 'right' }}>{`${(p.zeroRate * 100).toLocaleString(locale, { minimumFractionDigits: 4, maximumFractionDigits: 4 })} %`}</td>
                      <td style={{ textAlign: 'right' }}>{p.discountFactor.toLocaleString(locale, { minimumFractionDigits: 6, maximumFractionDigits: 6 })}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
        </>
      )}
    </div>
  )
}
