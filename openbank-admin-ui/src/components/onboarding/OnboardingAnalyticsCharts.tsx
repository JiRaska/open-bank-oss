// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import {
  ResponsiveContainer, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Cell, LabelList,
  LineChart, Line, PieChart, Pie, Legend,
} from 'recharts'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { FunnelStep } from '@/lib/onboarding/funnelAnalyticsContract'

const C_VIEWED = '#6366f1'
const C_DONE = '#22c55e'
const C_RATE = '#22c55e'
const PIE_COLORS = ['#6366f1', '#22c55e', '#f59e0b', '#a855f7', '#06b6d4', '#ef4444', '#94a3b8']

type FunnelDatum = FunnelStep & { label: string }
type RateDatum = { day: string; rate: number }
type KycDatum = { name: string; value: number }

function fmtSeconds(seconds: number | null): string {
  if (seconds == null) return '—'
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const remainder = seconds % 60
  return remainder ? `${minutes}m ${remainder}s` : `${minutes}m`
}

export function OnboardingAnalyticsCharts({ funnel, rate, kyc }: {
  funnel: FunnelDatum[]
  rate: RateDatum[]
  kyc: KycDatum[]
}) {
  const { t, language } = useLanguage()
  const tooltipStyle = {
    background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '8px',
    fontSize: '12px', color: 'var(--text-secondary)',
  }
  const axisTick = { fill: 'var(--text-muted)', fontSize: 11 }
  const funnelSummary = funnel
    .map(row => `${row.label}: ${row.viewed} ${t('zobrazeno', 'viewed')}, ${row.completed} ${t('dokončeno', 'completed')}, ${row.dropOffPct.toFixed(1)} % ${t('odchod', 'drop-off')}`)
    .join('; ')
  const rateSummary = rate.map(row => `${row.day}: ${row.rate.toFixed(1)} %`).join('; ')
  const kycSummary = kyc.map(row => `${row.name}: ${row.value}`).join('; ')

  return (
    <>
      <div className="card" style={{ padding: '16px 20px', marginBottom: '20px' }}>
        <h3 style={{ margin: '0 0 4px', fontSize: '14px', fontWeight: 600 }}>
          {t('Funnel konverze — krok po kroku', 'Conversion funnel — step by step')}
        </h3>
        <p style={{ margin: '0 0 12px', fontSize: '12px', color: 'var(--text-muted)' }}>
          {t('Zobrazeno vs. dokončeno; % je odchod na daném kroku',
            'Viewed vs. completed; % is drop-off at that step')}
        </p>
        <div
          role="img"
          aria-label={`${t('Konverzní funnel podle kroku', 'Conversion funnel by step')}. ${funnelSummary}`}
          style={{ width: '100%', height: 300 }}
        >
          <ResponsiveContainer>
            <BarChart data={funnel} margin={{ top: 20, right: 16, left: 0, bottom: 4 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
              <XAxis dataKey="label" tick={axisTick} axisLine={{ stroke: 'var(--border)' }} tickLine={false} />
              <YAxis tick={axisTick} axisLine={false} tickLine={false} allowDecimals={false} />
              <Tooltip contentStyle={tooltipStyle} cursor={{ fill: 'var(--border)', opacity: 0.3 }} />
              <Bar dataKey="viewed" name={t('Zobrazeno', 'Viewed')} fill={C_VIEWED} radius={[3, 3, 0, 0]} />
              <Bar dataKey="completed" name={t('Dokončeno', 'Completed')} fill={C_DONE} radius={[3, 3, 0, 0]}>
                <LabelList dataKey="dropOffPct" position="top"
                  formatter={(value) => (Number(value) > 0 ? `−${Number(value).toFixed(0)}%` : '')}
                  style={{ fill: 'var(--text-muted)', fontSize: 10 }} />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: `repeat(${funnel.length}, 1fr)`, gap: '8px', marginTop: '12px', borderTop: '1px solid var(--border)', paddingTop: '12px' }}>
          {funnel.map(step => (
            <div key={step.step} style={{ textAlign: 'center' }}>
              <div style={{ fontSize: '10px', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                {t('Medián času', 'Median time')}
              </div>
              <div style={{ fontSize: '15px', fontWeight: 600, marginTop: '2px' }}>{fmtSeconds(step.medianSeconds)}</div>
              <div style={{ fontSize: '10px', color: 'var(--text-muted)', marginTop: '1px' }}>{step.label}</div>
            </div>
          ))}
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: '20px', marginBottom: '20px' }}>
        <div className="card" style={{ padding: '16px 20px' }}>
          <h3 style={{ margin: '0 0 4px', fontSize: '14px', fontWeight: 600 }}>
            {t('Podpis smlouvy — denní úspěšnost', 'Signature — daily success rate')}
          </h3>
          <p style={{ margin: '0 0 12px', fontSize: '12px', color: 'var(--text-muted)' }}>
            {t('Úspěchy / pokusy v %', 'Successes / attempts, %')}
          </p>
          <div style={{ width: '100%', height: 240 }}>
            {rate.length === 0 ? (
              <DataUnavailable kind="no_data" feature={t('Podpis smlouvy', 'Signature')} lang={language} dense />
            ) : (
              <div role="img" aria-label={`${t('Denní úspěšnost podpisu', 'Daily signature success rate')}. ${rateSummary}`} style={{ height: '100%' }}>
                <ResponsiveContainer>
                  <LineChart data={rate} margin={{ top: 8, right: 16, left: 0, bottom: 4 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
                    <XAxis dataKey="day" tick={axisTick} axisLine={{ stroke: 'var(--border)' }} tickLine={false} />
                    <YAxis domain={[0, 100]} unit="%" tick={axisTick} axisLine={false} tickLine={false} />
                    <Tooltip contentStyle={tooltipStyle} formatter={(value) => [`${value} %`, t('Úspěšnost', 'Success rate')]} />
                    <Line type="monotone" dataKey="rate" stroke={C_RATE} strokeWidth={2} dot={{ r: 2 }} />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            )}
          </div>
        </div>

        <div className="card" style={{ padding: '16px 20px' }}>
          <h3 style={{ margin: '0 0 4px', fontSize: '14px', fontWeight: 600 }}>
            {t('Metoda ověření', 'Verification method')}
          </h3>
          <p style={{ margin: '0 0 12px', fontSize: '12px', color: 'var(--text-muted)' }}>
            {t('Rozdělení podle zvolené KYC metody', 'Split by chosen KYC method')}
          </p>
          <div style={{ width: '100%', height: 240 }}>
            {kyc.length === 0 ? (
              <DataUnavailable kind="no_data" feature={t('Metoda ověření', 'Verification method')} lang={language} dense />
            ) : (
              <div role="img" aria-label={`${t('Rozdělení podle KYC metody', 'KYC method split')}. ${kycSummary}`} style={{ height: '100%' }}>
                <ResponsiveContainer>
                  <PieChart>
                    <Pie data={kyc} dataKey="value" nameKey="name" cx="50%" cy="50%"
                      innerRadius={45} outerRadius={80} paddingAngle={2}>
                      {kyc.map((row, index) => <Cell key={row.name} fill={PIE_COLORS[index % PIE_COLORS.length]} />)}
                    </Pie>
                    <Tooltip contentStyle={tooltipStyle} />
                    <Legend wrapperStyle={{ fontSize: 11, color: 'var(--text-muted)' }} />
                  </PieChart>
                </ResponsiveContainer>
              </div>
            )}
          </div>
        </div>
      </div>
    </>
  )
}
