// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { loadStatutoryReturns, type ReturnBreach, type ReturnCapability, type StatutoryReturn } from '@/components/regulatory/statutoryReturns'

type Snapshot = { capability: ReturnCapability; returns: StatutoryReturn[]; breaches: ReturnBreach[] }
type State = { status: 'loading' } | { status: 'error'; kind: UnavailableKind } | { status: 'ready'; data: Snapshot }

export default function StatutoryReturnsPage() {
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
        title="Statutární výkazy"
        subtitle="Stav sestavení a zákonných termínů podle tax-reporting-service"
        breadcrumb={<Link href="/regulatory">Regulatorní výkaznictví</Link>}
      />
      {state.status === 'loading' && <p role="status">Načítání výkazů…</p>}
      {state.status === 'error' && <DataUnavailable kind={state.kind} service="Tax-reporting-service" lang="cs" />}
      {state.status === 'ready' && <>
        <section aria-labelledby="capability-heading" className="card" style={{ padding: 20, marginBottom: 16 }}>
          <h2 id="capability-heading">Možnosti systému</h2>
          <p>Datový zdroj: {state.data.capability.dataSourceAvailable ? 'dostupný' : 'nedostupný'}</p>
          <p>Regulatorní soubor: {state.data.capability.wireFormatAvailable ? 'dostupný' : 'nedostupný'}</p>
          <p>{state.data.capability.note}</p>
          <p>Schválení a odeslání se v této obrazovce neprovádí.</p>
        </section>
        <section aria-labelledby="breaches-heading" className="card" style={{ padding: 20, marginBottom: 16 }}>
          <h2 id="breaches-heading">Překročené termíny</h2>
          {state.data.breaches.length === 0 ? <p>API nehlásí žádný překročený termín.</p> :
            <ul>{state.data.breaches.map(item =>
              <li key={`${item.catalogueId}/${item.returnCode}/${item.entityId}/${item.period}`}>
                {item.returnCode} · {item.entityId} · {item.period} · {item.kind} · termín {item.dueDate}
              </li>)}</ul>}
        </section>
        <section aria-labelledby="returns-heading" className="card" style={{ padding: 20 }}>
          <h2 id="returns-heading">Revize výkazů</h2>
          {state.data.returns.length === 0 ? <p>API zatím nevrátilo žádnou sestavenou revizi.</p> :
            <ul>{state.data.returns.map(item =>
              <li key={item.id}>
                {item.returnCode} · {item.entityId} · {item.period} · revize {item.revision} · {item.status}
                {item.submissionReference ? ` · reference ${item.submissionReference}` : ''}
              </li>)}</ul>}
        </section>
      </>}
    </div>
  )
}
