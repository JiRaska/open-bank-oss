// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

type FeedbackChartRow = {
  screen: string
  bug: number
  idea: number
  confusing: number
}

export function FeedbackChart({ data, labels }: {
  data: FeedbackChartRow[]
  labels: { bug: string; idea: string; confusing: string }
}) {
  return (
    <ResponsiveContainer width="100%" height={320}>
      <BarChart data={data} layout="vertical" margin={{ left: 120 }} accessibilityLayer>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis type="number" allowDecimals={false} />
        <YAxis type="category" dataKey="screen" width={120} />
        <Tooltip />
        <Legend />
        <Bar dataKey="bug" name={labels.bug} stackId="a" fill="#ef4444" />
        <Bar dataKey="idea" name={labels.idea} stackId="a" fill="#6366f1" />
        <Bar dataKey="confusing" name={labels.confusing} stackId="a" fill="#f59e0b" />
      </BarChart>
    </ResponsiveContainer>
  )
}
