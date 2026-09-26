// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import {
  ResponsiveContainer, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend,
} from 'recharts'

const C_BUG = '#ef4444'
const C_IDEA = '#6366f1'
const C_CONFUSING = '#f59e0b'

export type ScreenPainDatum = {
  screen: string
  bug: number
  idea: number
  confusing: number
}

/** Heavy chart renderer kept outside the feedback board's critical route bundle. */
export function ScreenPainChart({ data, cs }: { data: ScreenPainDatum[]; cs: boolean }) {
  const bug = cs ? 'Chyba' : 'Bug'
  const idea = cs ? 'Nápad' : 'Idea'
  const confusing = cs ? 'Nesrozumitelné' : 'Confusing'
  const summary = data
    .map(row => `${row.screen}: ${bug} ${row.bug}, ${idea} ${row.idea}, ${confusing} ${row.confusing}`)
    .join('; ')

  return (
    <div
      role="img"
      aria-label={cs
        ? `Počet chyb, nápadů a nejasností podle obrazovky. ${summary}`
        : `Bug, idea and confusion reports by screen. ${summary}`}
    >
      <ResponsiveContainer width="100%" height={320}>
        <BarChart data={data} layout="vertical" margin={{ left: 120 }}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis type="number" allowDecimals={false} />
          <YAxis type="category" dataKey="screen" width={120} />
          <Tooltip />
          <Legend />
          <Bar dataKey="bug" name={bug} stackId="a" fill={C_BUG} />
          <Bar dataKey="idea" name={idea} stackId="a" fill={C_IDEA} />
          <Bar dataKey="confusing" name={confusing} stackId="a" fill={C_CONFUSING} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
