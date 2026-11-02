// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { loadStatutoryReturns, type ReturnBreach, type ReturnCapability, type StatutoryReturn } from '@/components/regulatory/statutoryReturns'

type Snapshot = { capability: ReturnCapability; returns: StatutoryReturn[]; breaches: ReturnBreach[] }
type State = { status: 'loading' } | { status: 'error'; kind: UnavailableKind } | { status: 'ready'; data: Snapshot }

export default function StatutoryReturnsPage() {
  const { t, language } = useLanguage()
  const [state, setState] = useState<State>({ status: 'loading' })

  useEffect(() => {
    let active = true
    loadStatutoryReturns().then(result => {
      if (active) setState(result.ok ? { status: 'ready', data: result.data } : { status: 'error', kind: result.kind })
    })
    return () => { active = false }
  }, [])

  return (
    <div>
      <PageHeader
        title={t('Statutární výkazy', 'Statutory returns')}
        subtitle={t('Stav sestavení a zákonných termínů podle tax-reporting-service', 'Build status and statutory deadlines from tax-reporting-service')}
        breadcrumb={<Link href="/regulatory">{t('Regulatorní výkaznictví', 'Regulatory reporting')}</Link>}
      />
      {state.status === 'loading' && <p role="status">{t('Načítání výkazů…', 'Loading returns…')}</p>}
      {state.status === 'error' && <DataUnavailable kind={state.kind} service="Tax-reporting-service" lang={language} />}
      {state.status === 'ready' && <>
        <section aria-labelledby="capability-heading" className="card" style={{ padding: 20, marginBottom: 16 }}>
          <h2 id="capability-heading">{t('Možnosti systému', 'System capabilities')}</h2>
          <p>{t('Datový zdroj', 'Data source')}: {state.data.capability.dataSourceAvailable ? t('dostupný', 'available') : t('nedostupný', 'unavailable')}</p>
          <p>{t('Regulatorní soubor', 'Regulatory file')}: {state.data.capability.wireFormatAvailable ? t('dostupný', 'available') : t('nedostupný', 'unavailable')}</p>
          <p>{state.data.capability.note}</p>
          <p>{t('Schválení a odeslání se v této obrazovce neprovádí.', 'Approval and submission are not performed on this screen.')}</p>
        </section>
        <section aria-labelledby="breaches-heading" className="card" style={{ padding: 20, marginBottom: 16 }}>
          <h2 id="breaches-heading">{t('Překročené termíny', 'Missed deadlines')}</h2>
          {state.data.breaches.length === 0 ? <p>{t('API nehlásí žádný překročený termín.', 'The API reports no missed deadline.')}</p> :
            <ul>{state.data.breaches.map(item =>
              <li key={`${item.catalogueId}/${item.returnCode}/${item.entityId}/${item.period}`}>
                {item.returnCode} · {item.entityId} · {item.period} · {item.kind} · {t('termín', 'due')} {item.dueDate}
              </li>)}</ul>}
        </section>
        <section aria-labelledby="returns-heading" className="card" style={{ padding: 20 }}>
          <h2 id="returns-heading">{t('Revize výkazů', 'Return revisions')}</h2>
          {state.data.returns.length === 0 ? <p>{t('API zatím nevrátilo žádnou sestavenou revizi.', 'The API has not returned any built revision yet.')}</p> :
            <ul>{state.data.returns.map(item =>
              <li key={item.id}>
                {item.returnCode} · {item.entityId} · {item.period} · {t('revize', 'revision')} {item.revision} · {item.status}
                {item.submissionReference ? ` · ${t('reference', 'reference')} ${item.submissionReference}` : ''}
              </li>)}</ul>}
        </section>
      </>}
    </div>
  )
}
