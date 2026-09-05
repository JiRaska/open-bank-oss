// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Onboarding conversion analytics — the HISTORICAL companion to the operational cockpit at
// /onboarding. Where that page is a current-state snapshot of who is stuck where, this one reads the
// ClickHouse gold funnel marts (via /api/onboarding/funnel-analytics) to show conversion over a date
// range: step funnel + drop-off, median dwell per step, daily signature conversion, why signatures
// fail, and the KYC-method split.

import { useState, useEffect, useCallback, useRef } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { TrendingUp, RefreshCw, ArrowLeft } from 'lucide-react'
import Link from 'next/link'
import {
  ResponsiveContainer, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Cell, LabelList,
  LineChart, Line, PieChart, Pie, Legend,
} from 'recharts'
import type { FunnelAnalytics } from '@/app/api/onboarding/funnel-analytics/route'
import { PageHeader } from '@/components/ui/PageHeader'

// ── Step labels (contract order WELCOME→SIGN) ─────────────────────────────────

const STEP_LABEL_CS: Record<string, string> = {
  WELCOME: 'Úvod', IDENTITY: 'Totožnost', EMAIL: 'E-mail',
  AGREEMENT: 'Souhlasy', PASSKEY: 'Passkey', SIGN: 'Podpis',
}
const STEP_LABEL_EN: Record<string, string> = {
  WELCOME: 'Welcome', IDENTITY: 'Identity', EMAIL: 'Email',
  AGREEMENT: 'Agreements', PASSKEY: 'Passkey', SIGN: 'Signature',
}

// Theme-agnostic chart palette. recharts writes `fill`/`stroke` as SVG attributes, where CSS
// variables don't resolve — so charts use explicit hex; surrounding chrome uses CSS vars.
const C_VIEWED = '#6366f1'
const C_DONE = '#22c55e'
const C_FAIL = '#ef4444'
const C_RATE = '#22c55e'
const PIE_COLORS = ['#6366f1', '#22c55e', '#f59e0b', '#a855f7', '#06b6d4', '#ef4444', '#94a3b8']

function isoDay(d: Date) { return d.toISOString().slice(0, 10) }
function fmtSeconds(s: number | null): string {
  if (s == null) return '—'
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rem = s % 60
  return rem ? `${m}m ${rem}s` : `${m}m`
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function OnboardingAnalyticsPage() {
  const { t, language } = useLanguage()
  const stepLabel = useCallback(
    (s: string) => (language === 'cs' ? STEP_LABEL_CS[s] : STEP_LABEL_EN[s]) ?? s,
    [language],
  )

  const [to, setTo] = useState(() => isoDay(new Date()))
  const [from, setFrom] = useState(() => isoDay(new Date(Date.now() - 30 * 864e5)))

  const [data, setData] = useState<FunnelAnalytics | null>(null)
  const [loading, setLoading] = useState(true)
  const [failure, setFailure] = useState<'operational' | 'unauthorized' | 'forbidden' | null>(null)
  const [lastSuccessfulLoad, setLastSuccessfulLoad] = useState<Date | null>(null)
  const [renderedRange, setRenderedRange] = useState<string | null>(null)
  const generation = useRef(0)
  const loadedRange = useRef<string | null>(null)
  const activeRequest = useRef<AbortController | null>(null)
  const mounted = useRef(false)

  const load = useCallback(async () => {
    const requestGeneration = ++generation.current
    const requestedRange = `${from}:${to}`
    activeRequest.current?.abort()
    const controller = new AbortController()
    activeRequest.current = controller
    setLoading(true)
    setFailure(null)
    if (loadedRange.current !== requestedRange) setData(null)
    try {
      const res = await fetch(`/api/onboarding/funnel-analytics?from=${from}&to=${to}`, {
        signal: AbortSignal.any([controller.signal, AbortSignal.timeout(12000)]),
      })
      if (res.status === 401 || res.status === 403) {
        if (!mounted.current) return
        generation.current += 1
        activeRequest.current?.abort()
        setData(null)
        loadedRange.current = null
        setRenderedRange(null)
        setFailure(res.status === 401 ? 'unauthorized' : 'forbidden')
        setLoading(false)
        return
      }
      if (requestGeneration !== generation.current) return
      if (!res.ok) {
        setFailure('operational')
        return
      }
      const next = await res.json() as FunnelAnalytics
      if (requestGeneration !== generation.current) return
      if (next.error || next.from !== from || next.to !== to) {
        setFailure('operational')
        return
      }
      setData(next)
      loadedRange.current = requestedRange
      setRenderedRange(requestedRange)
      setLastSuccessfulLoad(new Date())
    } catch {
      if (requestGeneration === generation.current) setFailure('operational')
    } finally {
      if (requestGeneration === generation.current) setLoading(false)
    }
  }, [from, to])

  useEffect(() => {
    mounted.current = true
    void load()
    return () => {
      mounted.current = false
      generation.current += 1
      activeRequest.current?.abort()
    }
  }, [load])

  const currentRange = `${from}:${to}`
  const visibleData = renderedRange === currentRange ? data : null

  // Derived KPI: overall funnel conversion (SIGN completed / WELCOME viewed).
  const welcome = visibleData?.steps.find(s => s.step === 'WELCOME')?.viewed ?? 0
  const signed = visibleData?.steps.find(s => s.step === 'SIGN')?.completed ?? 0
  const overallPct = welcome > 0 ? (signed / welcome) * 100 : 0
  const totalSigns = (visibleData?.signOutcomes ?? []).reduce((a, o) => a + o.attempts, 0)
  const totalOk = (visibleData?.signOutcomes ?? []).reduce((a, o) => a + o.successes, 0)
  const signRate = totalSigns > 0 ? (totalOk / totalSigns) * 100 : 0

  const funnelChart = (visibleData?.steps ?? []).map(s => ({
    ...s, label: stepLabel(s.step),
  }))
  const rateChart = (visibleData?.signOutcomes ?? []).map(o => ({
    day: o.day.slice(5), // MM-DD
    rate: o.attempts > 0 ? Math.round((o.successes / o.attempts) * 1000) / 10 : 0,
  }))
  const kycChart = (visibleData?.kycMethods ?? []).map(k => ({ name: k.method, value: k.sessions }))
  const maxReasonFailures = Math.max(1, ...(visibleData?.failReasons ?? []).map(r => r.failures))

  const tooltipStyle = {
    background: 'var(--surface)',
    border: '1px solid var(--border)',
    borderRadius: '8px',
    fontSize: '12px',
    color: 'var(--text-secondary)',
  }
  const axisTick = { fill: 'var(--text-muted)', fontSize: 11 }

  return (
    <div>
      <PageHeader
        icon={<TrendingUp size={18} aria-hidden="true" />}
        title={t('Konverze onboardingu', 'Onboarding Conversion')}
        subtitle={t('Historická analýza funnelu — krok po kroku, kde a proč prospekti odpadají', 'Historical funnel analysis — where and why prospects drop off, step by step')}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/onboarding" style={{ color: 'inherit', textDecoration: 'none' }}>{t('Onboarding', 'Onboarding')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Konverze', 'Conversion')}</span></div>}
        actions={<div style={{ display: 'flex', alignItems: 'flex-end', gap: '8px' }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '2px', fontSize: '11px', color: 'var(--text-muted)' }}>
            {t('Od', 'From')}
            <input type="date" value={from} max={to} onChange={e => {
              setLoading(true)
              setFailure(null)
              setFrom(e.target.value)
            }}
              className="input" style={{ padding: '5px 8px', fontSize: '12px' }} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '2px', fontSize: '11px', color: 'var(--text-muted)' }}>
            {t('Do', 'To')}
            <input type="date" value={to} min={from} max={isoDay(new Date())} onChange={e => {
              setLoading(true)
              setFailure(null)
              setTo(e.target.value)
            }}
              className="input" style={{ padding: '5px 8px', fontSize: '12px' }} />
          </label>
          <button
            className="btn btn-secondary"
            type="button"
            onClick={load}
            disabled={loading}
            aria-busy={loading}
            aria-label={t('Obnovit analytiku onboardingu', 'Refresh onboarding analytics')}
          >
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      <div style={{ marginBottom: '16px' }}>
        <Link href="/onboarding" className="btn btn-secondary" style={{ textDecoration: 'none', fontSize: '12px' }}>
          <ArrowLeft size={13} /> {t('Zpět na cockpit', 'Back to cockpit')}
        </Link>
      </div>

      {failure === 'unauthorized' || failure === 'forbidden' ? (
        <div className="card" style={{ padding: 0 }}>
          <DataUnavailable
            kind="unauthorized"
            feature={t('Konverze onboardingu', 'Onboarding conversion')}
            lang={language}
            title={failure === 'forbidden' ? t('Přístup zamítnut', 'Access denied') : undefined}
            detail={failure === 'forbidden'
              ? t('Vaše aktuální role nemá oprávnění zobrazit tuto analytiku.', 'Your current role is not permitted to view this analytics data.')
              : t('Vaše přihlášení vypršelo. Přihlaste se prosím znovu.', 'Your session has expired. Please sign in again.')}
          />
        </div>
      ) : loading && !visibleData ? (
        <div role="status" aria-live="polite" className="card" style={{ padding: '48px', textAlign: 'center', color: 'var(--text-muted)' }}>
          <RefreshCw size={20} aria-hidden="true" style={{ animation: 'spin 1s linear infinite' }} />
          <span className="sr-only">{t('Načítám analytiku onboardingu', 'Loading onboarding analytics')}</span>
        </div>
      ) : !visibleData || visibleData.available === false ? (
        <div className="card" style={{ padding: 0 }}>
          <DataUnavailable kind={failure === 'operational' || visibleData?.error ? 'unreachable' : 'no_data'}
            service="Analytics-sink (ClickHouse)"
            feature={t('Konverze onboardingu', 'Onboarding conversion')}
            lang={language}
            detail={failure === 'operational' || visibleData?.error
              ? t('ClickHouse gold marty nejsou dostupné.', 'ClickHouse gold marts are unavailable.')
              : t('V tomto období nejsou žádná data funnelu.', 'No funnel data in this period.')}>
            {failure === 'operational' && (
              <button type="button" className="btn btn-secondary" onClick={load} disabled={loading}>
                <RefreshCw size={13} aria-hidden="true" /> {t('Zkusit znovu', 'Retry')}
              </button>
            )}
          </DataUnavailable>
        </div>
      ) : (
        <>
          {(failure === 'operational' || loading) && (
            <div
              role="status"
              aria-label={t('Aktuálnost analytiky onboardingu', 'Onboarding analytics freshness')}
              className="card"
              style={{ padding: '12px 16px', marginBottom: '16px', borderColor: 'var(--warning)' }}
            >
              <div style={{ fontSize: '13px', fontWeight: 600 }}>
                {loading
                  ? t('Obnovuji analytiku; poslední úspěšný snímek zůstává zobrazený.', 'Refreshing analytics; the last successful snapshot remains visible.')
                  : t('Živá analytika není dostupná — zobrazuji poslední úspěšný snímek.', 'Live analytics is unavailable — showing the last successful snapshot.')}
              </div>
              {lastSuccessfulLoad && (
                <div style={{ marginTop: '3px', fontSize: '11px', color: 'var(--text-muted)' }}>
                  {t('Naposledy úspěšně načteno', 'Last successful load')}: {lastSuccessfulLoad.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}
                </div>
              )}
              {failure === 'operational' && (
                <button type="button" className="btn btn-secondary" onClick={load} disabled={loading} aria-label={t('Zkusit znovu načíst analytiku onboardingu', 'Retry onboarding analytics')} style={{ marginTop: '8px' }}>
                  <RefreshCw size={13} aria-hidden="true" /> {t('Zkusit znovu', 'Retry')}
                </button>
              )}
            </div>
          )}
          {/* KPI stat row */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '10px', marginBottom: '20px' }}>
            <StatTile label={t('Celková konverze', 'Overall conversion')} value={`${overallPct.toFixed(1)} %`}
              hint={t('Podpis / Úvod', 'Signature / Welcome')} color={C_DONE} />
            <StatTile label={t('Zahájilo (Úvod)', 'Started (Welcome)')} value={String(welcome)}
              hint={t('Zobrazení', 'sessions viewed')} color={C_VIEWED} />
            <StatTile label={t('Dokončilo podpis', 'Completed signature')} value={String(signed)}
              hint={t('Podepsané smlouvy', 'signed agreements')} color={C_DONE} />
            <StatTile label={t('Úspěšnost podpisu', 'Signature success')} value={`${signRate.toFixed(1)} %`}
              hint={t('Úspěchy / pokusy', 'successes / attempts')} color={signRate >= 80 ? C_DONE : C_FAIL} />
          </div>

          {/* Funnel: viewed vs completed per step */}
          <div className="card" style={{ padding: '16px 20px', marginBottom: '20px' }}>
            <h3 style={{ margin: '0 0 4px', fontSize: '14px', fontWeight: 600 }}>
              {t('Funnel konverze — krok po kroku', 'Conversion funnel — step by step')}
            </h3>
            <p style={{ margin: '0 0 12px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('Zobrazeno vs. dokončeno; % je odchod na daném kroku',
                 'Viewed vs. completed; % is drop-off at that step')}
            </p>
            <div style={{ width: '100%', height: 300 }}>
              <ResponsiveContainer>
                <BarChart data={funnelChart} margin={{ top: 20, right: 16, left: 0, bottom: 4 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
                  <XAxis dataKey="label" tick={axisTick} axisLine={{ stroke: 'var(--border)' }} tickLine={false} />
                  <YAxis tick={axisTick} axisLine={false} tickLine={false} allowDecimals={false} />
                  <Tooltip contentStyle={tooltipStyle} cursor={{ fill: 'var(--border)', opacity: 0.3 }} />
                  <Bar dataKey="viewed" name={t('Zobrazeno', 'Viewed')} fill={C_VIEWED} radius={[3, 3, 0, 0]} />
                  <Bar dataKey="completed" name={t('Dokončeno', 'Completed')} fill={C_DONE} radius={[3, 3, 0, 0]}>
                    <LabelList dataKey="dropOffPct" position="top"
                      formatter={(v) => (Number(v) > 0 ? `−${Number(v).toFixed(0)}%` : '')}
                      style={{ fill: 'var(--text-muted)', fontSize: 10 }} />
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>

            {/* Median dwell per step */}
            <div style={{ display: 'grid', gridTemplateColumns: `repeat(${funnelChart.length}, 1fr)`, gap: '8px', marginTop: '12px', borderTop: '1px solid var(--border)', paddingTop: '12px' }}>
              {funnelChart.map(s => (
                <div key={s.step} style={{ textAlign: 'center' }}>
                  <div style={{ fontSize: '10px', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                    {t('Medián času', 'Median time')}
                  </div>
                  <div style={{ fontSize: '15px', fontWeight: 600, marginTop: '2px' }}>{fmtSeconds(s.medianSeconds)}</div>
                  <div style={{ fontSize: '10px', color: 'var(--text-muted)', marginTop: '1px' }}>{s.label}</div>
                </div>
              ))}
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: '20px', marginBottom: '20px' }}>
            {/* Daily signature conversion rate */}
            <div className="card" style={{ padding: '16px 20px' }}>
              <h3 style={{ margin: '0 0 4px', fontSize: '14px', fontWeight: 600 }}>
                {t('Podpis smlouvy — denní úspěšnost', 'Signature — daily success rate')}
              </h3>
              <p style={{ margin: '0 0 12px', fontSize: '12px', color: 'var(--text-muted)' }}>
                {t('Úspěchy / pokusy v %', 'Successes / attempts, %')}
              </p>
              <div style={{ width: '100%', height: 240 }}>
                {rateChart.length === 0 ? (
                  <DataUnavailable kind="no_data" feature={t('Podpis smlouvy', 'Signature')} lang={language} dense />
                ) : (
                  <ResponsiveContainer>
                    <LineChart data={rateChart} margin={{ top: 8, right: 16, left: 0, bottom: 4 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
                      <XAxis dataKey="day" tick={axisTick} axisLine={{ stroke: 'var(--border)' }} tickLine={false} />
                      <YAxis domain={[0, 100]} unit="%" tick={axisTick} axisLine={false} tickLine={false} />
                      <Tooltip contentStyle={tooltipStyle} formatter={(v) => [`${v} %`, t('Úspěšnost', 'Success rate')]} />
                      <Line type="monotone" dataKey="rate" stroke={C_RATE} strokeWidth={2} dot={{ r: 2 }} />
                    </LineChart>
                  </ResponsiveContainer>
                )}
              </div>
            </div>

            {/* KYC method split */}
            <div className="card" style={{ padding: '16px 20px' }}>
              <h3 style={{ margin: '0 0 4px', fontSize: '14px', fontWeight: 600 }}>
                {t('Metoda ověření', 'Verification method')}
              </h3>
              <p style={{ margin: '0 0 12px', fontSize: '12px', color: 'var(--text-muted)' }}>
                {t('Rozdělení podle zvolené KYC metody', 'Split by chosen KYC method')}
              </p>
              <div style={{ width: '100%', height: 240 }}>
                {kycChart.length === 0 ? (
                  <DataUnavailable kind="no_data" feature={t('Metoda ověření', 'Verification method')} lang={language} dense />
                ) : (
                  <ResponsiveContainer>
                    <PieChart>
                      <Pie data={kycChart} dataKey="value" nameKey="name" cx="50%" cy="50%"
                        innerRadius={45} outerRadius={80} paddingAngle={2}>
                        {kycChart.map((_, i) => <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />)}
                      </Pie>
                      <Tooltip contentStyle={tooltipStyle} />
                      <Legend wrapperStyle={{ fontSize: 11, color: 'var(--text-muted)' }} />
                    </PieChart>
                  </ResponsiveContainer>
                )}
              </div>
            </div>
          </div>

          {/* Sign-fail reasons, ranked */}
          <div className="card" style={{ padding: '16px 20px' }}>
            <h3 style={{ margin: '0 0 4px', fontSize: '14px', fontWeight: 600 }}>
              {t('Důvody selhání podpisu', 'Signature failure reasons')}
            </h3>
            <p style={{ margin: '0 0 12px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('Seřazeno podle počtu selhání', 'Ranked by failure count')}
            </p>
            {(visibleData.failReasons ?? []).length === 0 ? (
              <DataUnavailable kind="no_data" feature={t('Důvody selhání podpisu', 'Signature failure reasons')} lang={language} dense />
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                {visibleData.failReasons.map(r => (
                  <div key={r.reason} style={{ display: 'grid', gridTemplateColumns: '180px 1fr 48px', gap: '10px', alignItems: 'center' }}>
                    <span style={{ fontSize: '12px', fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.reason}>
                      {r.reason}
                    </span>
                    <div style={{ background: 'var(--border)', borderRadius: '4px', height: '18px', overflow: 'hidden' }}>
                      <div style={{ width: `${(r.failures / maxReasonFailures) * 100}%`, height: '100%', background: C_FAIL, borderRadius: '4px', transition: 'width 0.3s' }} />
                    </div>
                    <span style={{ fontSize: '12px', fontWeight: 600, textAlign: 'right' }}>{r.failures}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  )
}

function StatTile({ label, value, hint, color }: { label: string; value: string; hint: string; color: string }) {
  return (
    <div className="card" style={{ padding: '14px 16px' }}>
      <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>{label}</div>
      <div style={{ fontSize: '24px', fontWeight: 700, color, lineHeight: 1.1, marginTop: '4px' }}>{value}</div>
      <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '2px' }}>{hint}</div>
    </div>
  )
}
