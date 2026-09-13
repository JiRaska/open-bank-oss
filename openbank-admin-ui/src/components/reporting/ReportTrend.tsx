// SPDX-License-Identifier: Apache-2.0
'use client'

import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useLanguage } from '@/lib/i18n/LanguageContext'

// Only additive counts are charted. Amounts remain attached to their currency in the table;
// daily distinct counts must never be presented as period-wide distinct customers.
const COUNTS: Record<string, { key: string; cs: string; en: string }> = {
  'risk-settlement-daily': { key: 'settled_count', cs: 'Zúčtované transakce', en: 'Settled transactions' },
  'risk-failures-daily': { key: 'failed_count', cs: 'Události selhání', en: 'Failure events' },
  'risk-credit-distress-daily': { key: 'suppressed', cs: 'Potlačené nabídky', en: 'Suppressed quotes' },
  'warehouse-event-volume': { key: 'events', cs: 'Přijaté události', en: 'Received events' },
}

export function dailyCounts(rows: Record<string, unknown>[], key: string) {
  const days = new Map<string, number>()
  for (const row of rows) {
    const count = Number(row[key])
    if (typeof row.day !== 'string' || row[key] == null || !Number.isFinite(count) || count < 0) continue
    days.set(row.day, (days.get(row.day) ?? 0) + count)
  }
  return [...days].sort(([a], [b]) => a.localeCompare(b)).map(([day, count]) => ({ day, count }))
}

export function ReportTrend({ reportId, rows, truncated }: { reportId: string; rows: Record<string, unknown>[]; truncated: boolean }) {
  const { t, language } = useLanguage()
  const metric = COUNTS[reportId]
  if (!metric || truncated) return null
  const data = dailyCounts(rows, metric.key)
  if (data.length === 0) return null
  const nf = new Intl.NumberFormat(language === 'cs' ? 'cs-CZ' : 'en-GB')
  return <section className="mb-6 rounded-xl border border-[var(--border)]" style={{ padding: 16 }} aria-label={t('Denní trend reportu', 'Daily report trend')}>
    <div className="flex flex-wrap justify-between gap-4 mb-4">
      <div><p className="text-sm text-[var(--text-secondary)]">{t(metric.cs, metric.en)}</p><p className="text-3xl font-semibold tabular-nums">{nf.format(data.reduce((sum, row) => sum + row.count, 0))}</p><p className="text-xs text-[var(--text-tertiary)]">{t('Součet za zobrazené dny', 'Sum across the displayed days')}</p></div>
      <div className="text-sm text-[var(--text-secondary)]"><p>{t('Dny s daty', 'Days with data')}: <strong>{data.length}</strong></p><p>{t('Poslední den s daty', 'Latest day with data')}: <strong>{data.at(-1)?.day}</strong></p></div>
    </div>
    <div style={{ width: '100%', height: 220 }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} accessibilityLayer margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis dataKey="day" tick={{ fontSize: 11, fill: 'var(--text-secondary)' }} tickFormatter={(day: string) => day.slice(5)} />
          <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: 'var(--text-secondary)' }} width={55} />
          <Tooltip contentStyle={{ background: 'var(--surface-1)', borderColor: 'var(--border)', borderRadius: 12 }} />
          <Bar dataKey="count" name={t(metric.cs, metric.en)} fill="var(--accent)" radius={[4, 4, 0, 0]} maxBarSize={36} />
        </BarChart>
      </ResponsiveContainer>
    </div>
    <p className="mt-3 text-xs text-[var(--text-tertiary)]">{t('Chybějící den neznamená nulovou aktivitu. Data mohou dorazit se zpožděním; dnešní den je neúplný.', 'A missing day does not mean zero activity. Data may arrive late; today is incomplete.')}</p>
  </section>
}
