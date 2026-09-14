// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import {
  Bar, BarChart, CartesianGrid, Cell, LabelList, Legend, Line, LineChart, Pie, PieChart,
  ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'

const C_VIEWED = '#6366f1'
const C_DONE = '#22c55e'
const PIE_COLORS = ['#6366f1', '#22c55e', '#f59e0b', '#a855f7', '#06b6d4', '#ef4444', '#94a3b8']
const tooltipStyle = {
  background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 8,
  fontSize: 12, color: 'var(--text-secondary)',
}
const axisTick = { fill: 'var(--text-muted)', fontSize: 11 }

type FunnelRow = { label: string; viewed: number; completed: number; dropOffPct: number }

export function FunnelChart({ data, viewedLabel, completedLabel }: {
  data: FunnelRow[]
  viewedLabel: string
  completedLabel: string
}) {
  return (
    <ResponsiveContainer>
      <BarChart data={data} margin={{ top: 20, right: 16, left: 0, bottom: 4 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
        <XAxis dataKey="label" tick={axisTick} axisLine={{ stroke: 'var(--border)' }} tickLine={false} />
        <YAxis tick={axisTick} axisLine={false} tickLine={false} allowDecimals={false} />
        <Tooltip contentStyle={tooltipStyle} cursor={{ fill: 'var(--border)', opacity: 0.3 }} />
        <Bar dataKey="viewed" name={viewedLabel} fill={C_VIEWED} radius={[3, 3, 0, 0]} />
        <Bar dataKey="completed" name={completedLabel} fill={C_DONE} radius={[3, 3, 0, 0]}>
          <LabelList dataKey="dropOffPct" position="top"
            formatter={(value) => (Number(value) > 0 ? `−${Number(value).toFixed(0)}%` : '')}
            style={{ fill: 'var(--text-muted)', fontSize: 10 }} />
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

export function SignatureRateChart({ data, rateLabel }: {
  data: { day: string; rate: number }[]
  rateLabel: string
}) {
  return (
    <ResponsiveContainer>
      <LineChart data={data} margin={{ top: 8, right: 16, left: 0, bottom: 4 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
        <XAxis dataKey="day" tick={axisTick} axisLine={{ stroke: 'var(--border)' }} tickLine={false} />
        <YAxis domain={[0, 100]} unit="%" tick={axisTick} axisLine={false} tickLine={false} />
        <Tooltip contentStyle={tooltipStyle} formatter={(value) => [`${value} %`, rateLabel]} />
        <Line type="monotone" dataKey="rate" stroke={C_DONE} strokeWidth={2} dot={{ r: 2 }} />
      </LineChart>
    </ResponsiveContainer>
  )
}

export function KycMethodChart({ data }: { data: { name: string; value: number }[] }) {
  return (
    <ResponsiveContainer>
      <PieChart>
        <Pie data={data} dataKey="value" nameKey="name" cx="50%" cy="50%"
          innerRadius={45} outerRadius={80} paddingAngle={2}>
          {data.map((row, index) => <Cell key={row.name} fill={PIE_COLORS[index % PIE_COLORS.length]} />)}
        </Pie>
        <Tooltip contentStyle={tooltipStyle} />
        <Legend wrapperStyle={{ fontSize: 11, color: 'var(--text-muted)' }} />
      </PieChart>
    </ResponsiveContainer>
  )
}
