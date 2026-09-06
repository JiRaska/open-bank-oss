// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import {
  Bar, BarChart, CartesianGrid, Cell, Legend, Pie, PieChart, ReferenceLine, ResponsiveContainer,
  Scatter, ScatterChart, Tooltip, XAxis, YAxis, ZAxis,
} from 'recharts'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { Decision, ReasonCount, StageRow, WeeklyOutcome } from './model'

// Theme-agnostic chart palette. recharts writes `fill`/`stroke` as SVG attributes, where CSS
// variables do not resolve — same constraint as the onboarding funnel page. One colour per
// outcome, reused everywhere so APPROVE is the same green on every chart on the page.
export const C_APPROVE = '#22c55e'
export const C_REFER = '#f59e0b'
export const C_DECLINE = '#ef4444'
export const C_STAGE: Record<string, string> = { STAGE_1: '#6366f1', STAGE_2: '#f59e0b', STAGE_3: '#ef4444' }
const C_BUCKET = ['#6366f1', '#a5b4fc', '#f59e0b', '#fb923c', '#ef4444']
const OUTCOME_COLOUR: Record<string, string> = { APPROVE: C_APPROVE, REFER: C_REFER, DECLINE: C_DECLINE }

const axisTick = { fontSize: 11, fill: 'var(--text-tertiary)' }

export function OutcomeTrend({ data }: { data: WeeklyOutcome[] }) {
  const { t } = useLanguage()
  return (
    <div style={{ height: 240 }}>
      <ResponsiveContainer>
        <BarChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 4 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
          <XAxis dataKey="week" tick={axisTick} />
          <YAxis allowDecimals={false} tick={axisTick} width={32} />
          <Tooltip />
          <Legend wrapperStyle={{ fontSize: 11 }} />
          <Bar dataKey="APPROVE" stackId="o" name={t('Schváleno', 'Approve')} fill={C_APPROVE} />
          <Bar dataKey="REFER" stackId="o" name={t('K posouzení', 'Refer')} fill={C_REFER} />
          <Bar dataKey="DECLINE" stackId="o" name={t('Zamítnuto', 'Decline')} fill={C_DECLINE} radius={[3, 3, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

export function ReasonPareto({ data }: { data: ReasonCount[] }) {
  const rows = data.slice(0, 10).map(r => ({ ...r, label: r.ruleId ? `${r.code} · ${r.ruleId}` : r.code }))
  return (
    <div style={{ height: Math.max(160, rows.length * 30 + 40) }}>
      <ResponsiveContainer>
        <BarChart data={rows} layout="vertical" margin={{ top: 4, right: 24, left: 8, bottom: 4 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" horizontal={false} />
          <XAxis type="number" allowDecimals={false} tick={axisTick} />
          <YAxis type="category" dataKey="label" width={260} tick={{ ...axisTick, fontSize: 10 }} />
          <Tooltip />
          <Bar dataKey="count" fill={C_REFER} radius={[0, 3, 3, 0]}>
            {rows.map(r => <Cell key={r.label} fill={r.code === 'EXCLUSION_MATCHED' ? C_DECLINE : C_REFER} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

type ScatterProps = {
  decisions: Decision[]
  dstiLimit: number | null
  dtiLimit: number | null
  /** Plot total-debt-service DSTI instead of the engine's new-installment DSTI. */
  includeExistingDebt: boolean
}

/**
 * DSTI × DTI, one point per evaluated application, coloured by engine outcome, with the policy's
 * own thresholds drawn as lines. The lines come from `/risk/policy`; when the policy carries no
 * numeric threshold for an attribute there is no line, rather than an invented one.
 */
export function AffordabilityScatter({ decisions, dstiLimit, dtiLimit, includeExistingDebt }: ScatterProps) {
  const { t } = useLanguage()
  const points = decisions
    .filter(d => d.affordability)
    .map(d => ({
      x: includeExistingDebt ? d.affordability!.dstiIncludingExistingDebt : d.affordability!.dsti,
      y: d.affordability!.dti,
      outcome: d.engineOutcome,
      id: d.applicationId,
    }))
  const series = (['APPROVE', 'REFER', 'DECLINE'] as const).map(o => ({ o, pts: points.filter(p => p.outcome === o) }))
  return (
    <div style={{ height: 280 }}>
      <ResponsiveContainer>
        <ScatterChart margin={{ top: 12, right: 24, left: 0, bottom: 8 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
          <XAxis type="number" dataKey="x" name="DSTI" tick={axisTick} domain={[0, (max: number) => Math.max(0.6, Math.ceil(max * 10) / 10)]}
            label={{ value: includeExistingDebt ? t('DSTI vč. stávajícího dluhu', 'DSTI incl. existing debt') : t('DSTI (engine)', 'DSTI (engine)'), position: 'insideBottom', offset: -4, fontSize: 11 }} />
          <YAxis type="number" dataKey="y" name="DTI" tick={axisTick} width={36} domain={[0, (max: number) => Math.max(10, Math.ceil(max))]} />
          <ZAxis range={[40, 40]} />
          <Tooltip cursor={{ strokeDasharray: '3 3' }} formatter={(v, name) => [typeof v === 'number' ? v.toFixed(3) : String(v), String(name)]} />
          {dstiLimit !== null && <ReferenceLine x={dstiLimit} stroke={C_DECLINE} strokeDasharray="4 4" label={{ value: `DSTI ${dstiLimit}`, fontSize: 10, position: 'top' }} />}
          {dtiLimit !== null && <ReferenceLine y={dtiLimit} stroke={C_DECLINE} strokeDasharray="4 4" label={{ value: `DTI ${dtiLimit}`, fontSize: 10, position: 'right' }} />}
          {series.map(s => <Scatter key={s.o} name={s.o} data={s.pts} fill={OUTCOME_COLOUR[s.o]} />)}
          <Legend wrapperStyle={{ fontSize: 11 }} />
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  )
}

export function StageMixPie({ stages }: { stages: StageRow[] }) {
  const data = stages.filter(s => s.outstanding > 0).map(s => ({ name: s.stage.replace('_', ' '), value: s.outstanding, stage: s.stage }))
  return (
    <div style={{ height: 220 }}>
      <ResponsiveContainer>
        <PieChart>
          <Pie data={data} dataKey="value" nameKey="name" innerRadius={50} outerRadius={80} paddingAngle={2}>
            {data.map(d => <Cell key={d.stage} fill={C_STAGE[d.stage]} />)}
          </Pie>
          <Tooltip formatter={v => (typeof v === 'number' ? v.toLocaleString() : String(v))} />
          <Legend wrapperStyle={{ fontSize: 11 }} />
        </PieChart>
      </ResponsiveContainer>
    </div>
  )
}

export function BucketBars({ buckets }: { buckets: { bucket: string; count: number; outstanding: number }[] }) {
  const { t } = useLanguage()
  const rows = buckets.map(b => ({ ...b, label: b.bucket.replace('DPD_', '').replace('_PLUS', '+').replace('_', '–') }))
  return (
    <div style={{ height: 220 }}>
      <ResponsiveContainer>
        <BarChart data={rows} margin={{ top: 8, right: 8, left: 0, bottom: 4 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
          <XAxis dataKey="label" tick={axisTick} />
          <YAxis allowDecimals={false} tick={axisTick} width={32} />
          <Tooltip />
          <Bar dataKey="count" name={t('Úvěry', 'Loans')} radius={[3, 3, 0, 0]}>
            {rows.map((r, i) => <Cell key={r.bucket} fill={C_BUCKET[i % C_BUCKET.length]} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
