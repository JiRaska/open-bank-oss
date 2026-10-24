// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury reports (ADR-0344 catalogue): the ten reports of the desk, each read from the service
// that owns it. Risk reports run over a chosen risk snapshot (newest by default) and, where the
// report supports it, a portfolio node. Every response is shown with its provenance, and a data
// gap the service reports is rendered as a gap — never as a zero.

'use client'

import { useEffect, useState } from 'react'
import { FileText } from 'lucide-react'
import { z } from 'zod'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { riskUrl, wbGet, wbUrl, type WbRead } from '@/components/treasury/wb/client'
import { REPORTS, reportName, type ReportId } from '@/components/treasury/wb/reports'
import { ReportView } from '@/components/treasury/wb/ReportView'
import { Field, gridStyle, ReadFailure, Section, useFmt, useRefOptions } from '@/components/treasury/wb/ui'

export default function TreasuryReportsPage() {
  return (
    <AuthGuard permission="treasury:workbench:view">
      <Reports />
    </AuthGuard>
  )
}

const snapshotsSchema = z.object({
  runs: z.array(z.object({ id: z.string(), asOf: z.string(), provenance: z.string().nullish(), status: z.string().nullish() })).max(500),
})
const anyJson = z.unknown()

function Reports() {
  const { t } = useFmt()
  const [reportId, setReportId] = useState<ReportId>('valuation')
  const [snapshots, setSnapshots] = useState<z.infer<typeof snapshotsSchema>['runs'] | null>(null)
  const [snapshotId, setSnapshotId] = useState('')
  const [bookId, setBookId] = useState('')
  const [confidence, setConfidence] = useState('0.99')
  const [horizon, setHorizon] = useState('1')
  const [bucketSet, setBucketSet] = useState('')
  const [result, setResult] = useState<WbRead<unknown> | null>(null)
  const books = useRefOptions('book')
  const spec = REPORTS.find(r => r.id === reportId)!

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const res = await wbGet(riskUrl('/snapshots'), snapshotsSchema)
      if (cancelled) return
      const runs = res.ok ? [...res.data.runs].sort((a, b) => b.asOf.localeCompare(a.asOf)) : []
      setSnapshots(runs)
      if (runs.length > 0) setSnapshotId(runs[0].id)
    })()
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    if (spec.snapshot && !snapshotId) {
      setResult(snapshots === null ? null : { ok: false, kind: 'not_available', status: 404 })
      return
    }
    let cancelled = false
    setResult(null)
    const query: Record<string, string | undefined> = {}
    if (spec.book && bookId) query.bookId = bookId
    if (spec.extra?.includes('confidence')) query.confidence = confidence
    if (spec.extra?.includes('horizon')) query.horizonDays = horizon
    if (spec.extra?.includes('bucketSet') && bucketSet) query.bucketSetId = bucketSet
    const path = spec.path(encodeURIComponent(snapshotId))
    const url = spec.source === 'risk' ? riskUrl(path, query) : wbUrl(path, query)
    void (async () => {
      const res = await wbGet(url, anyJson)
      if (!cancelled) setResult(res)
    })()
    return () => { cancelled = true }
  }, [spec, snapshotId, snapshots, bookId, confidence, horizon, bucketSet])

  return (
    <div>
      <PageHeader title={t('Reporty a analýzy', 'Reports and analyses')} subtitle={t('Reporty treasury z poskytujících služeb, s původem dat.', 'Treasury reports from the owning services, with data provenance.')} icon={<FileText size={20} aria-hidden="true" />} />
      <Section title={t('Nabídka reportů', 'Reports menu')} id="reports-menu">
        <div role="group" aria-label={t('Nabídka reportů', 'Reports menu')} style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {REPORTS.map(r => (
            <button key={r.id} type="button" className={r.id === reportId ? 'btn btn-primary' : 'btn'} aria-pressed={r.id === reportId} onClick={() => setReportId(r.id)}>
              {reportName(r, t)}
            </button>
          ))}
        </div>
      </Section>
      <Section title={t('Parametry', 'Parameters')} id="reports-params">
        <div style={gridStyle}>
          {spec.snapshot && (
            <Field label={t('Snímek rizik', 'Risk snapshot')}>
              <select className="input" value={snapshotId} onChange={e => setSnapshotId(e.target.value)}>
                {(snapshots ?? []).map(s => <option key={s.id} value={s.id}>{`${s.asOf} · ${s.provenance ?? '—'} · ${s.status ?? '—'}`}</option>)}
              </select>
            </Field>
          )}
          {spec.book && (
            <Field label={t('Uzel portfolia', 'Portfolio node')}>
              <select className="input" value={bookId} onChange={e => setBookId(e.target.value)}>
                <option value="">{t('Celá banka', 'Whole bank')}</option>
                {(books.options ?? []).map(b => <option key={b.key} value={b.key}>{b.label}</option>)}
              </select>
            </Field>
          )}
          {spec.extra?.includes('confidence') && (
            <Field label={t('Hladina spolehlivosti', 'Confidence')}>
              <select className="input" value={confidence} onChange={e => setConfidence(e.target.value)}>
                {['0.95', '0.975', '0.99'].map(c => <option key={c} value={c}>{c}</option>)}
              </select>
            </Field>
          )}
          {spec.extra?.includes('horizon') && (
            <Field label={t('Horizont (dny)', 'Horizon (days)')}>
              <input className="input" type="number" min={1} max={250} value={horizon} onChange={e => setHorizon(e.target.value)} />
            </Field>
          )}
          {spec.extra?.includes('bucketSet') && (
            <Field label={t('Sada pásem', 'Bucket set')}>
              <input className="input" maxLength={64} value={bucketSet} onChange={e => setBucketSet(e.target.value)} />
            </Field>
          )}
        </div>
      </Section>
      <Section title={reportName(spec, t)} id="reports-view">
        {result === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
        {result && !result.ok && <ReadFailure result={result} what={reportName(spec, t)} service={spec.source === 'risk' ? 'risk-engine' : 'treasury-service'} />}
        {result?.ok && <ReportView data={result.data} fallbackProvenance={spec.provenance} />}
      </Section>
    </div>
  )
}
