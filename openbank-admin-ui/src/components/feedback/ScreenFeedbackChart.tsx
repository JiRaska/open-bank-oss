// SPDX-License-Identifier: Apache-2.0
'use client'

import {
  Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { ScreenFeedbackBoard } from '@/app/api/feedback/screen-feedback/route'

type Props = {
  screens: ScreenFeedbackBoard['screens']
  cs: boolean
}

export function ScreenFeedbackChart({ screens, cs }: Props) {
  const bug = cs ? 'Chyba' : 'Bug'
  const idea = cs ? 'Nápad' : 'Idea'
  const confusing = cs ? 'Nesrozumitelné' : 'Confusing'
  const data = screens.slice(0, 12).map(screen => ({
    screen: screen.screenId,
    [bug]: screen.bugs,
    [idea]: screen.ideas,
    [confusing]: screen.confusing,
  }))

  return (
    <div role="group" aria-label={cs ? 'Hlášení podle obrazovky a kategorie' : 'Reports by screen and category'}>
      <ResponsiveContainer width="100%" height={320}>
        <BarChart accessibilityLayer data={data} layout="vertical" margin={{ left: 120 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
          <XAxis type="number" allowDecimals={false} />
          <YAxis type="category" dataKey="screen" width={120} />
          <Tooltip contentStyle={{ background: 'var(--surface-1)', borderColor: 'var(--border)', borderRadius: 12 }} />
          <Legend />
          <Bar dataKey={bug} stackId="a" fill="var(--danger)" />
          <Bar dataKey={idea} stackId="a" fill="var(--accent)" />
          <Bar dataKey={confusing} stackId="a" fill="var(--warning)" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
