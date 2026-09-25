// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Bar, CartesianGrid, ComposedChart, Legend, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { cumulativeGap, type GapRow, type LadderRow } from './model'
import { C_GAP, C_INFLOW, C_OUTFLOW } from './palette'

const axisTick = { fontSize: 11, fill: 'var(--text-tertiary)' }

/**
 * Cash-flow maturity ladder for ONE currency: the engine's bank-signed net flow per bucket as an
 * inflow (up) or outflow (down) bar, and the cumulative gap as a line. Loaded only through
 * next/dynamic from the snapshot page, which reserves this height.
 */
export function MaturityLadder({ rows, locale }: { rows: LadderRow[]; locale: string }) {
  const { t } = useLanguage()
  const gaps = cumulativeGap(rows)
  const data = rows.map((r, i) => ({ ...r, gap: gaps[i] }))
  const fmt = (v: number) => v.toLocaleString(locale, { maximumFractionDigits: 0 })
  return (
    <div style={{ height: 280 }}>
      <ResponsiveContainer>
        <ComposedChart data={data} margin={{ top: 8, right: 8, left: 8, bottom: 4 }} stackOffset="sign">
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
          <XAxis dataKey="bucket" tick={axisTick} />
          <YAxis tick={axisTick} width={72} tickFormatter={fmt} />
          <Tooltip formatter={v => fmt(Number(v))} />
          <Legend wrapperStyle={{ fontSize: 11 }} />
          <ReferenceLine y={0} stroke="var(--border)" />
          <Bar dataKey="inflow" stackId="flow" name={t('Přítok', 'Inflow')} fill={C_INFLOW} />
          <Bar dataKey="outflow" stackId="flow" name={t('Odtok', 'Outflow')} fill={C_OUTFLOW} />
          <Line dataKey="gap" name={t('Kumulativní mezera', 'Cumulative gap')} stroke={C_GAP} dot={false} strokeWidth={2} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}

/** Repricing gap for ONE currency: assets up, liabilities down, cumulative gap as a line. */
export function RepricingGapChart({ rows, locale }: { rows: GapRow[]; locale: string }) {
  const { t } = useLanguage()
  const fmt = (v: number) => v.toLocaleString(locale, { maximumFractionDigits: 0 })
  return (
    <div style={{ height: 280 }}>
      <ResponsiveContainer>
        <ComposedChart data={rows} margin={{ top: 8, right: 8, left: 8, bottom: 4 }} stackOffset="sign">
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
          <XAxis dataKey="bucket" tick={axisTick} />
          <YAxis tick={axisTick} width={72} tickFormatter={fmt} />
          <Tooltip formatter={v => fmt(Number(v))} />
          <Legend wrapperStyle={{ fontSize: 11 }} />
          <ReferenceLine y={0} stroke="var(--border)" />
          <Bar dataKey="assets" stackId="gap" name={t('Aktiva', 'Assets')} fill={C_INFLOW} />
          <Bar dataKey="liabilities" stackId="gap" name={t('Pasiva', 'Liabilities')} fill={C_OUTFLOW} />
          <Line dataKey="cumulativeGap" name={t('Kumulativní mezera', 'Cumulative gap')} stroke={C_GAP} dot={false} strokeWidth={2} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}
