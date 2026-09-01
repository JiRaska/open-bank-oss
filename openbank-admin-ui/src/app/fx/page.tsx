// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect, useCallback } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  Globe, TrendingUp, RefreshCw, CheckCircle2, XCircle, Clock,
  ArrowLeftRight, Save, History, Edit3, Banknote, Download,
  Calendar, Play, AlertCircle, Settings, Eye, EyeOff, Percent,
  ChevronDown, ChevronUp, Lock, Unlock, Moon,
} from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { CURRENCY_META } from '@/lib/currency-meta'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { FxTrendChart } from '@/components/fx/FxTrendChart'

interface FxRate { baseCurrency: string; quoteCurrency: string; rate: number; timestamp: string }
interface FxConversion { id: string; fromCurrency: string; toCurrency: string; fromAmount: number; toAmount: number; rate: number; status: string; createdAt: string }
interface CnbRate { currencyCode: string; amount: number; rate: number; validFor: string; country: string; currency: string }
interface FxTrendPoint { date: string; rate: string; timestamp: string }
interface FxTrend { indicative: true; base: string; quote: string; points: FxTrendPoint[]; unavailable?: boolean }
interface EcbRate { currency: string; rate: number; date: string }
interface CurrencyMetaType { flag: string; symbol: string; name: string }

interface MarginConfig {
  buyPct: number
  sellPct: number
}

interface CurrencyOverride {
  currency: string
  buyOverride: number | null
  sellOverride: number | null
  published: boolean
}

interface ScheduleEntry {
  id: string
  source: 'CNB' | 'ECB'
  label: string
  hour: number
  minute: number
  days: string[]
  nextRun: string
  lastRun: string | null
  lastStatus: 'ok' | 'error' | null
  lastCount: number | null
}

const ALL_DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
const DAY_LABELS_CS: Record<string, string> = { MON: 'Po', TUE: 'Út', WED: 'St', THU: 'Čt', FRI: 'Pá', SAT: 'So', SUN: 'Ne' }
const DAY_LABELS_EN: Record<string, string> = { MON: 'Mon', TUE: 'Tue', WED: 'Wed', THU: 'Thu', FRI: 'Fri', SAT: 'Sat', SUN: 'Sun' }

function nextRunTime(hour: number, minute: number, days: string[]): string {
  const now = new Date()
  for (let i = 0; i < 8; i++) {
    const d = new Date(now)
    d.setDate(d.getDate() + i)
    d.setHours(hour, minute, 0, 0)
    const dayName = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'][d.getDay()]
    if (days.includes(dayName) && d > now) return d.toISOString()
  }
  const fallback = new Date(now)
  fallback.setDate(fallback.getDate() + 1)
  fallback.setHours(hour, minute, 0, 0)
  return fallback.toISOString()
}

function formatNextRun(iso: string, t: (cs: string, en: string) => string): string {
  // eslint-disable-next-line react-hooks/purity -- time-relative display; timestamps are stable server data.
  const diffMs = new Date(iso).getTime() - Date.now()
  if (diffMs < 0) return t('brzy', 'soon')
  const h = Math.floor(diffMs / 3600000)
  const m = Math.floor((diffMs % 3600000) / 60000)
  if (h >= 24) return t(`za ${Math.floor(h / 24)}d ${h % 24}h`, `in ${Math.floor(h / 24)}d ${h % 24}h`)
  if (h > 0) return t(`za ${h}h ${m}m`, `in ${h}h ${m}m`)
  if (m > 0) return t(`za ${m}m`, `in ${m}m`)
  return t('brzy', 'soon')
}

const SCHEDULE_LABELS_EN: Record<string, string> = { 'cnb-daily': 'CNB Daily rates', 'ecb-daily': 'ECB Reference rates' }

const INITIAL_SCHEDULES: ScheduleEntry[] = [
  { id: 'cnb-daily', source: 'CNB', label: 'ČNB Denní kurzy', hour: 14, minute: 30, days: ['MON', 'TUE', 'WED', 'THU', 'FRI'], nextRun: nextRunTime(14, 30, ['MON', 'TUE', 'WED', 'THU', 'FRI']), lastRun: null, lastStatus: null, lastCount: null },
  { id: 'ecb-daily', source: 'ECB', label: 'ECB Referenční kurzy', hour: 16, minute: 0, days: ['MON', 'TUE', 'WED', 'THU', 'FRI'], nextRun: nextRunTime(16, 0, ['MON', 'TUE', 'WED', 'THU', 'FRI']), lastRun: null, lastStatus: null, lastCount: null },
]

const ECB_CURRENCIES = ['USD', 'GBP', 'JPY', 'CHF', 'PLN', 'HUF', 'RON', 'SEK', 'NOK', 'DKK', 'AUD', 'CAD', 'CNY']
const DEFAULT_PUBLISHED = ['USD', 'EUR', 'GBP', 'CHF', 'JPY', 'PLN', 'HUF', 'SEK', 'NOK', 'DKK']
// FX persistence endpoints for bank-sheet configuration are not part of the current
// service contract. Keep the browser preview honest until a durable backend exists.
const FX_CONFIGURATION_WRITABLE = false

// fx-service is on the FinOps scaledown allowlist, so "not reachable" usually means
// "intentionally idle", not "broken". Map the /api/fx/rates status to a calm badge:
// scale-to-zero is amber "asleep", never a red "down". null = still checking.
type FxStatus = 'up' | 'scaled_to_zero' | 'down' | 'not_deployed'
function fxBadge(status: FxStatus | null, t: (cs: string, en: string) => string) {
  switch (status) {
    case 'up':
      return { icon: CheckCircle2, bg: 'var(--success-bg)', fg: 'var(--success-text)', border: 'var(--success-border)', label: t('fx-service :8119', 'fx-service :8119') }
    case 'scaled_to_zero':
      return { icon: Moon, bg: 'var(--warning-bg)', fg: 'var(--warning-text)', border: 'var(--warning-border)', label: t('fx-service spí (scale-to-zero)', 'fx-service idle (scaled to zero)') }
    case 'down':
      return { icon: XCircle, bg: 'var(--danger-bg)', fg: 'var(--danger-text)', border: 'var(--danger-border)', label: t('fx-service neodpovídá', 'fx-service is not responding') }
    case 'not_deployed':
      return { icon: XCircle, bg: 'var(--surface-3)', fg: 'var(--text-tertiary)', border: 'var(--border)', label: t('fx-service není nasazen', 'fx-service not deployed') }
    default:
      return { icon: Clock, bg: 'var(--surface-3)', fg: 'var(--text-tertiary)', border: 'var(--border)', label: t('fx-service — zjišťuji…', 'fx-service — checking…') }
  }
}

function applyMargin(mid: number, pct: number, direction: 'buy' | 'sell'): number {
  return direction === 'buy' ? mid * (1 - pct / 100) : mid * (1 + pct / 100)
}

function CurrencyCell({ code, meta }: { code: string; meta?: CurrencyMetaType }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
      <span style={{ fontSize: '18px', lineHeight: 1, flexShrink: 0 }}>{meta?.flag ?? '🏳️'}</span>
      <div>
        <div style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)' }}>{code}</div>
        {meta?.name && <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginTop: '1px', whiteSpace: 'nowrap' }}>{meta.name}</div>}
      </div>
    </div>
  )
}

function MidCell({ mid, symbol }: { mid: number; symbol?: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'baseline', gap: '3px' }}>
      {symbol && <span style={{ fontSize: '9px', color: 'var(--text-tertiary)' }}>{symbol}</span>}
      <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)' }}>{mid.toFixed(4)}</span>
    </div>
  )
}

export default function FxPage() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState<string | null>(null)
  // Typed unavailable reason for a failed aggregate fetch → renders the calm
  // <DataUnavailable> panel instead of leaking a raw "HTTP 500" (graceful-state rule).
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const [cnbRates, setCnbRates] = useState<CnbRate[]>([])
  const [cnbSyncedAt, setCnbSyncedAt] = useState<string | null>(null)
  const [cnbError, setCnbError] = useState<string | null>(null)

  const [ecbRates, setEcbRates] = useState<EcbRate[]>([])
  const [ecbSyncedAt, setEcbSyncedAt] = useState<string | null>(null)
  const [ecbError, setEcbError] = useState<string | null>(null)

  const [fxStatus, setFxStatus] = useState<FxStatus | null>(null)
  const [conversions, setConversions] = useState<FxConversion[]>([])

  const [margin, setMargin] = useState<MarginConfig>({ buyPct: 1.5, sellPct: 1.5 })
  const [editingMargin, setEditingMargin] = useState(false)
  const [marginDraft, setMarginDraft] = useState<MarginConfig>({ buyPct: 1.5, sellPct: 1.5 })

  const [overrides, setOverrides] = useState<Record<string, CurrencyOverride>>(() =>
    ECB_CURRENCIES.reduce((acc, c) => ({
      ...acc,
      [c]: { currency: c, buyOverride: null, sellOverride: null, published: DEFAULT_PUBLISHED.includes(c) },
    }), {} as Record<string, CurrencyOverride>)
  )
  const [editingOverride, setEditingOverride] = useState<string | null>(null)
  const [overrideDraft, setOverrideDraft] = useState<Partial<CurrencyOverride>>({})

  const [schedules, setSchedules] = useState<ScheduleEntry[]>(INITIAL_SCHEDULES)
  const [editingSchedule, setEditingSchedule] = useState<string | null>(null)
  const [scheduleDraft, setScheduleDraft] = useState<Partial<ScheduleEntry>>({})

  const [history, setHistory] = useState<Array<{ timestamp: string; source: string; pair: string; rate: number }>>([])
  const [activeTab, setActiveTab] = useState<'cnb' | 'ecb' | 'bank'>('bank')

  // The three-calendar-month ČNB reference-mid trend (issue #7735) — real chronological data
  // from fx-service via /api/fx/history, the SAME normalization the customer app renders
  // (src/lib/fx/trend.ts mirrors customer-edge's mapCnbTrend). Never a client-memory snapshot.
  const [trend, setTrend] = useState<FxTrend | null>(null)
  const [trendPair] = useState<{ base: string; quote: string }>({ base: 'EUR', quote: 'CZK' })

  useEffect(() => {
    let cancelled = false
    fetch(`/api/fx/history?base=${trendPair.base}&quote=${trendPair.quote}`, { cache: 'no-store' })
      .then(res => (res.ok ? res.json() : null))
      .then(data => { if (!cancelled) setTrend(data) })
      .catch(() => { if (!cancelled) setTrend(null) })
    return () => { cancelled = true }
  }, [trendPair])

  const loadData = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const res = await fetch('/api/fx/rates', { cache: 'no-store', signal: AbortSignal.timeout(20000) })
      if (!res.ok) {
        setUnavailable({ kind: await classifyBffFailure(res) })
        return
      }
      const data = await res.json()

      setCnbRates(data.cnb?.rates ?? [])
      setCnbSyncedAt(data.cnb?.syncedAt ?? null)
      setCnbError(data.cnb?.error ?? null)

      setEcbRates(data.ecb?.rates ?? [])
      setEcbSyncedAt(data.ecb?.syncedAt ?? null)
      setEcbError(data.ecb?.error ?? null)

      setFxStatus((data.fxService?.status as FxStatus | undefined) ?? (data.fxService?.up ? 'up' : 'down'))
      setConversions(data.fxService?.conversions ?? [])

      // NOTE (issue #7735): this used to fabricate fake "history" rows here by re-labelling
      // the CURRENT rate-sheet snapshot with `now` as its timestamp on every refresh — a
      // client-memory illusion of a time series, never persisted, never a real observation.
      // The real three-calendar-month CNB trend is fetched separately, from a real endpoint,
      // in the `trend` effect below. `history` here is now only ever a genuine admin-action
      // log (margin edits, overrides), appended at the moment those actions actually happen.
    } catch {
      // Timeout / abort / network — the FX aggregate endpoint didn't answer.
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadData() }, [loadData])

  const manualRefresh = async (source: 'cnb' | 'ecb' | 'all') => {
    setRefreshing(source)
    try {
      const res = await fetch('/api/fx/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ source }),
        signal: AbortSignal.timeout(20000),
      })
      const data = await res.json()
      const now = new Date().toISOString()
      setSchedules(prev => prev.map(s => {
        const key = s.source.toLowerCase() as 'cnb' | 'ecb'
        if (source !== 'all' && key !== source) return s
        const r = data?.results?.[key]
        if (!r) return s
        return { ...s, lastRun: now, lastStatus: r.ok ? 'ok' : 'error', lastCount: r.count ?? null, nextRun: nextRunTime(s.hour, s.minute, s.days) }
      }))
      await loadData()
    } finally {
      setRefreshing(null)
    }
  }

  const saveSchedule = (id: string) => {
    setSchedules(prev => prev.map(s => {
      if (s.id !== id) return s
      const updated = { ...s, ...scheduleDraft } as ScheduleEntry
      updated.nextRun = nextRunTime(updated.hour, updated.minute, updated.days)
      return updated
    }))
    setEditingSchedule(null)
  }

  const saveMargin = () => {
    setMargin(marginDraft)
    setEditingMargin(false)
    setHistory(prev => [
      { timestamp: new Date().toISOString(), source: 'Margin Edit', pair: 'GLOBAL', rate: marginDraft.buyPct },
      ...prev,
    ].slice(0, 50))
  }

  const saveOverride = (currency: string) => {
    setOverrides(prev => ({ ...prev, [currency]: { ...prev[currency], ...overrideDraft } as CurrencyOverride }))
    if (overrideDraft.buyOverride != null || overrideDraft.sellOverride != null) {
      setHistory(prev => [
        { timestamp: new Date().toISOString(), source: 'Override', pair: `EUR/${currency}`, rate: overrideDraft.buyOverride ?? overrideDraft.sellOverride ?? 0 },
        ...prev,
      ].slice(0, 50))
    }
    setEditingOverride(null)
  }

  const togglePublished = (currency: string) => {
    setOverrides(prev => ({ ...prev, [currency]: { ...prev[currency], published: !prev[currency].published } }))
  }

  const isRefreshing = (src: string) => refreshing === src || refreshing === 'all'
  const totalVolume = conversions.reduce((s, c) => s + (c.fromAmount ?? 0), 0)

  const cnbSpread = 0.5
  const bankRateRows = ECB_CURRENCIES.map(code => {
    const ecb = ecbRates.find(r => r.currency === code)
    const ov = overrides[code]
    if (!ecb) return null
    const mid = ecb.rate
    const buyCalc = applyMargin(mid, margin.buyPct, 'buy')
    const sellCalc = applyMargin(mid, margin.sellPct, 'sell')
    const buy = ov?.buyOverride != null ? ov.buyOverride : buyCalc
    const sell = ov?.sellOverride != null ? ov.sellOverride : sellCalc
    return { code, mid, buy, sell, buyCalc, sellCalc, hasOverride: ov?.buyOverride != null || ov?.sellOverride != null, published: ov?.published ?? false, date: ecb.date }
  }).filter(Boolean) as Array<{ code: string; mid: number; buy: number; sell: number; buyCalc: number; sellCalc: number; hasOverride: boolean; published: boolean; date: string }>

  const tabStyle = (tab: string) => ({
    padding: '8px 18px',
    fontSize: '12px',
    fontWeight: 600,
    cursor: 'pointer',
    border: 'none',
    borderRadius: '6px',
    background: activeTab === tab ? 'var(--accent)' : 'transparent',
    color: activeTab === tab ? '#fff' : 'var(--text-secondary)',
    transition: 'all 0.15s',
  } as React.CSSProperties)

  return (
    <AuthGuard>
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>

        <PageHeader
          icon={<Globe size={20} aria-hidden="true" />}
          title={t('Devizové operace', 'Foreign Exchange')}
          subtitle={t('CNB, ECB & Bankovní kurzovní lístek', 'CNB, ECB & bank rate sheet')}
          actions={<div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <button type="button" onClick={loadData} disabled={loading} aria-busy={loading} aria-label={t('Obnovit kurzy FX', 'Refresh FX rates')} className="btn btn-secondary btn-sm"><RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />{t('Obnovit', 'Refresh')}</button>
            <button type="button" onClick={() => manualRefresh('all')} disabled={!!refreshing || loading} aria-busy={refreshing === 'all'} aria-label={t('Stáhnout všechny kurzy FX', 'Fetch all FX rates')} className="btn btn-primary" style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px' }}>
              <Download size={13} aria-hidden="true" style={{ animation: refreshing === 'all' ? 'spin 1s linear infinite' : 'none' }} />
              {t('Stáhnout vše', 'Fetch All')}
            </button>
            {(() => {
              const b = fxBadge(fxStatus, t)
              const Icon = b.icon
              return (
                <span style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '11px', fontWeight: 600, padding: '4px 10px', borderRadius: '20px',
                  background: b.bg, color: b.fg, border: `1px solid ${b.border}` }}>
                  <Icon size={10} />
                  {b.label}
                </span>
              )
            })()}
          </div>}
        />

        {unavailable && (
          <div className="card" style={{ padding: 0, marginBottom: '20px' }}>
            <DataUnavailable
              kind={unavailable.kind}
              service={t('FX-service', 'FX-service')}
              feature={t('Kurzovní lístek', 'FX rates')}
              lang={language}
              dense
            />
          </div>
        )}

        <div className="card" style={{ marginBottom: '20px', padding: '12px 16px', display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '18px', fontSize: '11px' }}>
          <span style={{ fontWeight: 700, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
            <Globe size={13} style={{ color: 'var(--accent)' }} />
            {t('Zdroje dat', 'Data sources')}
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: '5px', color: 'var(--text-tertiary)' }}>
            {cnbError ? <XCircle size={11} style={{ color: 'var(--danger-text)' }} /> : <CheckCircle2 size={11} style={{ color: 'var(--success-text)' }} />}
            <strong style={{ color: 'var(--text-secondary)' }}>ČNB</strong>
            {t('externí referenční kurzy (api.cnb.cz)', 'external reference rates (api.cnb.cz)')}
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: '5px', color: 'var(--text-tertiary)' }}>
            {ecbError ? <XCircle size={11} style={{ color: 'var(--danger-text)' }} /> : <CheckCircle2 size={11} style={{ color: 'var(--success-text)' }} />}
            <strong style={{ color: 'var(--text-secondary)' }}>ECB</strong>
            {t('externí referenční kurzy (data-api.ecb.europa.eu)', 'external reference rates (data-api.ecb.europa.eu)')}
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: '5px', color: 'var(--text-tertiary)' }}>
            {(() => { const b = fxBadge(fxStatus, t); const Icon = b.icon; return <Icon size={11} style={{ color: b.fg }} /> })()}
            <strong style={{ color: 'var(--text-secondary)' }}>fx-service</strong>
            {fxStatus === 'scaled_to_zero'
              ? t('interní FX engine + konverze (DB) — spí (scale-to-zero)', 'internal FX engine + conversions (DB) — idle (scaled to zero)')
              : t('interní FX engine + konverze (DB)', 'internal FX engine + conversions (DB)')}
          </span>
          <span style={{ marginLeft: 'auto', color: 'var(--text-tertiary)', fontStyle: 'italic' }}>
            <AlertCircle size={11} style={{ verticalAlign: '-1px', marginRight: '4px' }} />
            {t(
              'Referenční kurzy ČNB/ECB jsou dostupné i bez interních služeb. Bankovní lístek (marže, override, plán) je pouze náhled — uložení zatím není nakonfigurované.',
              'ČNB/ECB reference rates are available without internal services. The bank sheet (margins, overrides, schedule) is preview-only — persistence is not configured.'
            )}
          </span>
        </div>

        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('CNB Páry', 'CNB Pairs'), value: cnbRates.length, icon: <Banknote size={16} />, color: 'var(--accent)' },
            { label: t('ECB Páry', 'ECB Pairs'), value: ecbRates.length, icon: <Globe size={16} />, color: 'var(--info)' },
            { label: t('Publikované měny', 'Published Currencies'), value: Object.values(overrides).filter(o => o.published).length, icon: <Eye size={16} />, color: 'var(--success)' },
            { label: t('Objem (EUR)', 'Volume (EUR)'), value: totalVolume > 0 ? totalVolume.toLocaleString(numberLocale, { maximumFractionDigits: 0 }) : '—', icon: <TrendingUp size={16} />, color: 'var(--warning)' },
          ].map(k => (
            <div key={k.label} className="stat-card">
              <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: `${k.color}18`, display: 'flex', alignItems: 'center', justifyContent: 'center', color: k.color, marginBottom: '10px' }}>{k.icon}</div>
              <div style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>{loading ? '—' : k.value}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{k.label}</div>
            </div>
          ))}
        </div>

        <div className="card" style={{ marginBottom: '24px' }}>
          <div style={{ padding: '12px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: '8px' }}>
            {(['bank', 'cnb', 'ecb'] as const).map(tab => (
              <button
                key={tab}
                type="button"
                style={tabStyle(tab)}
                aria-pressed={activeTab === tab}
                aria-label={tab === 'bank' ? t('Bankovní lístek', 'Bank rate sheet') : tab === 'cnb' ? t('Kurzy ČNB', 'CNB rates') : t('Kurzy ECB', 'ECB rates')}
                onClick={() => setActiveTab(tab)}
              >
                {tab === 'bank' ? `🏦 ${t('Bankovní lístek', 'Bank Rate Sheet')}` : tab === 'cnb' ? `🇨🇿 ${t('ČNB Kurzy', 'CNB Rates')}` : `🇪🇺 ${t('ECB Kurzy', 'ECB Rates')}`}
              </button>
            ))}
          </div>

          {activeTab === 'cnb' && (
            <div>
              <div style={{ padding: '10px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  {t('Střed kurzu z ČNB · Nákup/Prodej = střed ±', 'CNB mid rate · Buy/Sell = mid ±')} {cnbSpread}% {t('(orientační)', '(indicative)')}
                  {cnbSyncedAt && ` · ${t('Synchronizace', 'Sync')}: ${new Date(cnbSyncedAt).toLocaleTimeString(numberLocale)}`}
                  {cnbError && <span style={{ color: 'var(--red)', marginLeft: '8px' }}>⚠ {cnbError}</span>}
                </span>
                <button onClick={() => manualRefresh('cnb')} disabled={!!refreshing || loading} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--accent)', display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px' }}>
                  <Download size={12} style={{ animation: isRefreshing('cnb') ? 'spin 1s linear infinite' : 'none' }} /> {t('Stáhnout', 'Download')}
                </button>
              </div>
              <div style={{ maxHeight: '420px', overflowY: 'auto' }}>
                {loading ? (
                  <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '12px' }}>
                    <RefreshCw size={14} style={{ animation: 'spin 1s linear infinite', marginBottom: '6px' }} /><div>{t('Načítám…', 'Loading…')}</div>
                  </div>
                ) : (
                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                      {[t('Měna', 'Currency'), t('Střed / CZK', 'Mid / CZK'), t('Nákup / CZK', 'Buy / CZK'), t('Prodej / CZK', 'Sell / CZK'), t('Platnost', 'Valid For')].map(h => (
                        <th key={h} style={{ padding: '8px 16px', position: 'sticky', top: 0, background: 'var(--surface-1)', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase' }}>{h}</th>
                      ))}
                    </tr></thead>
                    <tbody>{cnbRates.map((r, i) => {
                      const mid = r.rate / r.amount
                      const buy = applyMargin(mid, cnbSpread, 'buy')
                      const sell = applyMargin(mid, cnbSpread, 'sell')
                      return (
                        <tr key={i} style={{ borderBottom: '1px solid var(--border)' }}
                          onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                          onMouseLeave={e => (e.currentTarget.style.background = '')}>
                          <td style={{ padding: '8px 16px' }}><CurrencyCell code={r.currencyCode} meta={CURRENCY_META[r.currencyCode]} /></td>
                          <td style={{ padding: '8px 16px' }}><MidCell mid={mid} symbol="Kč" /></td>
                          <td style={{ padding: '8px 16px' }}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700, color: 'var(--success-text)' }}>{buy.toFixed(4)}</span></td>
                          <td style={{ padding: '8px 16px' }}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700, color: 'var(--danger-text)' }}>{sell.toFixed(4)}</span></td>
                          <td style={{ padding: '8px 16px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                            {r.validFor}
                            {r.amount > 1 && <div style={{ fontSize: '10px', marginTop: '1px' }}>{t('za', 'per')} {r.amount} {r.currencyCode}</div>}
                          </td>
                        </tr>
                      )
                    })}</tbody>
                  </table>
                )}
              </div>
            </div>
          )}

          {activeTab === 'ecb' && (
            <div>
              <div style={{ padding: '10px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  {t('ECB referenční kurzy (EUR base) · Nákup/Prodej = střed ±', 'ECB reference rates (EUR base) · Buy/Sell = mid ±')} {margin.buyPct}%/{margin.sellPct}%
                  {ecbSyncedAt && ` · ${t('Synchronizace', 'Sync')}: ${new Date(ecbSyncedAt).toLocaleTimeString(numberLocale)}`}
                  {ecbError && <span style={{ color: 'var(--red)', marginLeft: '8px' }}>⚠ {ecbError}</span>}
                </span>
                <button onClick={() => manualRefresh('ecb')} disabled={!!refreshing || loading} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--info)', display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px' }}>
                  <Download size={12} style={{ animation: isRefreshing('ecb') ? 'spin 1s linear infinite' : 'none' }} /> {t('Stáhnout', 'Download')}
                </button>
              </div>
              <div style={{ maxHeight: '420px', overflowY: 'auto' }}>
                {loading ? (
                  <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '12px' }}>
                    <RefreshCw size={14} style={{ animation: 'spin 1s linear infinite', marginBottom: '6px' }} /><div>{t('Načítám…', 'Loading…')}</div>
                  </div>
                ) : (
                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                      {[t('Měna', 'Currency'), t('Střed (EUR)', 'Mid (EUR)'), t('Nákup', 'Buy'), t('Prodej', 'Sell'), t('Datum ECB', 'ECB Date')].map(h => (
                        <th key={h} style={{ padding: '8px 16px', position: 'sticky', top: 0, background: 'var(--surface-1)', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase' }}>{h}</th>
                      ))}
                    </tr></thead>
                    <tbody>{ecbRates.map((r, i) => {
                      const buy = applyMargin(r.rate, margin.buyPct, 'buy')
                      const sell = applyMargin(r.rate, margin.sellPct, 'sell')
                      return (
                        <tr key={i} style={{ borderBottom: '1px solid var(--border)' }}
                          onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                          onMouseLeave={e => (e.currentTarget.style.background = '')}>
                          <td style={{ padding: '8px 16px' }}><CurrencyCell code={r.currency} meta={CURRENCY_META[r.currency]} /></td>
                          <td style={{ padding: '8px 16px' }}><MidCell mid={r.rate} /></td>
                          <td style={{ padding: '8px 16px' }}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700, color: 'var(--success-text)' }}>{buy.toFixed(4)}</span></td>
                          <td style={{ padding: '8px 16px' }}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700, color: 'var(--danger-text)' }}>{sell.toFixed(4)}</span></td>
                          <td style={{ padding: '8px 16px', fontSize: '11px', color: 'var(--text-tertiary)' }}>{r.date}</td>
                        </tr>
                      )
                    })}</tbody>
                  </table>
                )}
              </div>
            </div>
          )}

          {activeTab === 'bank' && (
            <div>
              <div style={{ padding: '12px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '10px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
                  <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Odvozeno od ECB · Globální marže:', 'Derived from ECB · Global margin:')}</span>
                  {editingMargin ? (
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      <label style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '12px', color: 'var(--success-text)' }}>
                        <span style={{ fontSize: '9px', fontWeight: 700, background: 'var(--success-bg)', border: '1px solid var(--success-border)', borderRadius: '3px', padding: '0 4px' }}>BUY</span>
                        <input type="number" aria-label={t('Nákupní marže v procentech', 'Buy margin percent')} step="0.1" min="0" max="20" value={marginDraft.buyPct}
                          onChange={e => setMarginDraft(p => ({ ...p, buyPct: parseFloat(e.target.value) || 0 }))}
                          style={{ width: '56px', padding: '3px 6px', fontSize: '12px', background: 'var(--surface-1)', border: '1px solid var(--border)', borderRadius: '4px', color: 'var(--text-primary)', textAlign: 'right' }} />
                        <Percent size={11} style={{ color: 'var(--text-tertiary)' }} />
                      </label>
                      <label style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '12px', color: 'var(--danger-text)' }}>
                        <span style={{ fontSize: '9px', fontWeight: 700, background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', borderRadius: '3px', padding: '0 4px' }}>SELL</span>
                        <input type="number" aria-label={t('Prodejní marže v procentech', 'Sell margin percent')} step="0.1" min="0" max="20" value={marginDraft.sellPct}
                          onChange={e => setMarginDraft(p => ({ ...p, sellPct: parseFloat(e.target.value) || 0 }))}
                          style={{ width: '56px', padding: '3px 6px', fontSize: '12px', background: 'var(--surface-1)', border: '1px solid var(--border)', borderRadius: '4px', color: 'var(--text-primary)', textAlign: 'right' }} />
                        <Percent size={11} style={{ color: 'var(--text-tertiary)' }} />
                      </label>
                      <button type="button" disabled={!FX_CONFIGURATION_WRITABLE} onClick={saveMargin} style={{ background: 'var(--success-bg)', color: 'var(--success-text)', border: '1px solid var(--success-border)', padding: '4px 10px', borderRadius: '5px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', fontWeight: 600 }}>
                        <Save size={11} /> {t('Uložit', 'Save')}
                      </button>
                      <button onClick={() => setEditingMargin(false)} style={{ background: 'var(--surface-3)', color: 'var(--text-secondary)', border: '1px solid var(--border)', padding: '4px 10px', borderRadius: '5px', cursor: 'pointer', fontSize: '11px' }}>
                        {t('Zrušit', 'Cancel')}
                      </button>
                    </div>
                  ) : (
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      <span style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '12px', fontWeight: 700, color: 'var(--success-text)' }}>
                        <span style={{ fontSize: '9px', fontWeight: 700, background: 'var(--success-bg)', border: '1px solid var(--success-border)', borderRadius: '3px', padding: '0 4px' }}>BUY</span>
                        −{margin.buyPct}%
                      </span>
                      <span style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '12px', fontWeight: 700, color: 'var(--danger-text)' }}>
                        <span style={{ fontSize: '9px', fontWeight: 700, background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', borderRadius: '3px', padding: '0 4px' }}>SELL</span>
                        +{margin.sellPct}%
                      </span>
                      <button type="button" disabled={!FX_CONFIGURATION_WRITABLE} onClick={() => { setMarginDraft(margin); setEditingMargin(true) }} style={{ background: 'var(--surface-3)', color: 'var(--text-secondary)', border: '1px solid var(--border)', padding: '4px 10px', borderRadius: '5px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px' }}>
                        <Settings size={11} /> {t('Upravit marži', 'Edit margin')}
                      </button>
                    </div>
                  )}
                </div>
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  {Object.values(overrides).filter(o => o.published).length} {t('publikovaných měn', 'published currencies')}
                </span>
              </div>

              <div style={{ maxHeight: '480px', overflowY: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                    {[t('Publikovat', 'Publish'), t('Měna', 'Currency'), t('ECB Střed', 'ECB Mid'), t('Nákup (banka)', 'Buy (bank)'), t('Prodej (banka)', 'Sell (bank)'), t('Override', 'Override'), t('Datum', 'Date')].map(h => (
                      <th key={h} style={{ padding: '8px 16px', position: 'sticky', top: 0, background: 'var(--surface-1)', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase' }}>{h}</th>
                    ))}
                  </tr></thead>
                  <tbody>
                    {loading ? (
                      <tr><td colSpan={7} style={{ padding: '32px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '12px' }}>
                        <RefreshCw size={14} style={{ animation: 'spin 1s linear infinite' }} />
                      </td></tr>
                    ) : bankRateRows.map(r => (
                      <tr key={r.code} style={{ borderBottom: '1px solid var(--border)', opacity: r.published ? 1 : 0.45 }}
                        onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                        onMouseLeave={e => (e.currentTarget.style.background = '')}>
                        <td style={{ padding: '8px 16px' }}>
                          <button type="button" disabled={!FX_CONFIGURATION_WRITABLE} onClick={() => togglePublished(r.code)} title={r.published ? t('Skrýt z lístku', 'Hide from rate sheet') : t('Publikovat na lístek', 'Publish to rate sheet')}
                            style={{ background: r.published ? 'var(--success-bg)' : 'var(--surface-3)', border: `1px solid ${r.published ? 'var(--success-border)' : 'var(--border)'}`, borderRadius: '5px', padding: '3px 7px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px', color: r.published ? 'var(--success-text)' : 'var(--text-tertiary)', fontSize: '10px', fontWeight: 600 }}>
                            {r.published ? <Eye size={11} /> : <EyeOff size={11} />}
                            {r.published ? t('Ano', 'Yes') : t('Ne', 'No')}
                          </button>
                        </td>
                        <td style={{ padding: '8px 16px' }}><CurrencyCell code={r.code} meta={CURRENCY_META[r.code]} /></td>
                        <td style={{ padding: '8px 16px' }}><MidCell mid={r.mid} /></td>
                        <td style={{ padding: '8px 16px' }}>
                          {editingOverride === r.code ? (
                            <input type="number" aria-label={t(`Override nákupního kurzu ${r.code}`, `Buy rate override ${r.code}`)} step="0.0001" placeholder={r.buyCalc.toFixed(4)} value={overrideDraft.buyOverride ?? ''}
                              onChange={e => setOverrideDraft(p => ({ ...p, buyOverride: e.target.value ? parseFloat(e.target.value) : null }))}
                              style={{ width: '80px', padding: '3px 6px', fontSize: '12px', background: 'var(--surface-1)', border: '1px solid var(--success-border)', borderRadius: '4px', color: 'var(--success-text)', fontFamily: 'var(--font-mono)' }} />
                          ) : (
                            <div style={{ display: 'flex', alignItems: 'center', gap: '5px' }}>
                              <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700, color: 'var(--success-text)' }}>{r.buy.toFixed(4)}</span>
                              {overrides[r.code]?.buyOverride != null && <span title={t('Override aktivní', 'Override active')}><Lock size={10} style={{ color: 'var(--warning)' }} /></span>}
                            </div>
                          )}
                        </td>
                        <td style={{ padding: '8px 16px' }}>
                          {editingOverride === r.code ? (
                            <input type="number" aria-label={t(`Override prodejního kurzu ${r.code}`, `Sell rate override ${r.code}`)} step="0.0001" placeholder={r.sellCalc.toFixed(4)} value={overrideDraft.sellOverride ?? ''}
                              onChange={e => setOverrideDraft(p => ({ ...p, sellOverride: e.target.value ? parseFloat(e.target.value) : null }))}
                              style={{ width: '80px', padding: '3px 6px', fontSize: '12px', background: 'var(--surface-1)', border: '1px solid var(--danger-border)', borderRadius: '4px', color: 'var(--danger-text)', fontFamily: 'var(--font-mono)' }} />
                          ) : (
                            <div style={{ display: 'flex', alignItems: 'center', gap: '5px' }}>
                              <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 700, color: 'var(--danger-text)' }}>{r.sell.toFixed(4)}</span>
                              {overrides[r.code]?.sellOverride != null && <span title={t('Override aktivní', 'Override active')}><Lock size={10} style={{ color: 'var(--warning)' }} /></span>}
                            </div>
                          )}
                        </td>
                        <td style={{ padding: '8px 16px' }}>
                          {editingOverride === r.code ? (
                            <div style={{ display: 'flex', gap: '5px' }}>
                              <button type="button" disabled={!FX_CONFIGURATION_WRITABLE} onClick={() => saveOverride(r.code)} style={{ background: 'var(--success-bg)', color: 'var(--success-text)', border: '1px solid var(--success-border)', padding: '3px 8px', borderRadius: '4px', cursor: 'pointer', fontSize: '11px', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '3px' }}>
                                <Save size={10} /> OK
                              </button>
                              <button onClick={() => setEditingOverride(null)} style={{ background: 'var(--surface-3)', color: 'var(--text-secondary)', border: '1px solid var(--border)', padding: '3px 8px', borderRadius: '4px', cursor: 'pointer', fontSize: '11px' }}>✕</button>
                            </div>
                          ) : (
                            <div style={{ display: 'flex', gap: '5px' }}>
                              <button type="button" disabled={!FX_CONFIGURATION_WRITABLE} onClick={() => { setEditingOverride(r.code); setOverrideDraft({ buyOverride: overrides[r.code]?.buyOverride ?? null, sellOverride: overrides[r.code]?.sellOverride ?? null }) }}
                                style={{ background: 'var(--surface-3)', color: 'var(--text-secondary)', border: '1px solid var(--border)', padding: '3px 8px', borderRadius: '4px', cursor: 'pointer', fontSize: '11px', display: 'flex', alignItems: 'center', gap: '3px' }}>
                                <Edit3 size={10} /> {t('Upravit', 'Fix')}
                              </button>
                              {r.hasOverride && (
                                <button type="button" disabled={!FX_CONFIGURATION_WRITABLE} onClick={() => setOverrides(prev => ({ ...prev, [r.code]: { ...prev[r.code], buyOverride: null, sellOverride: null } }))}
                                  title={t('Zrušit override', 'Clear override')}
                                  style={{ background: 'var(--warning-bg)', color: 'var(--warning-text)', border: '1px solid var(--warning-border)', padding: '3px 8px', borderRadius: '4px', cursor: 'pointer', fontSize: '11px', display: 'flex', alignItems: 'center', gap: '3px' }}>
                                  <Unlock size={10} /> {t('Reset', 'Reset')}
                                </button>
                              )}
                            </div>
                          )}
                        </td>
                        <td style={{ padding: '8px 16px', fontSize: '11px', color: 'var(--text-tertiary)' }}>{r.date}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>

        <div className="card" style={{ marginBottom: '24px' }}>
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Calendar size={14} style={{ color: 'var(--accent)' }} />
              <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{t('Plánované aktualizace', 'Scheduled Updates')}</span>
            </div>
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Automatické stahování kurzů', 'Auto-fetch schedule')}</span>
          </div>
          <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
            {schedules.map(s => (
              <div key={s.id} style={{ borderRadius: '8px', background: 'var(--surface-2)', border: '1px solid var(--border)', overflow: 'hidden' }}>
                <div style={{ display: 'grid', gridTemplateColumns: '28px 1fr auto auto auto auto', alignItems: 'center', gap: '16px', padding: '14px 16px' }}>
                  <div style={{ width: '28px', height: '28px', borderRadius: '6px', background: s.source === 'CNB' ? 'var(--accent)18' : 'var(--info)18', display: 'flex', alignItems: 'center', justifyContent: 'center', color: s.source === 'CNB' ? 'var(--accent)' : 'var(--info)' }}>
                    {s.source === 'CNB' ? <Banknote size={14} /> : <Globe size={14} />}
                  </div>
                  <div>
                    <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{t(s.label, SCHEDULE_LABELS_EN[s.id] ?? s.label)}</div>
                    <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>
                      {String(s.hour).padStart(2, '0')}:{String(s.minute).padStart(2, '0')} · {s.days.map(d => t(DAY_LABELS_CS[d], DAY_LABELS_EN[d])).join(', ')}
                    </div>
                  </div>
                  <div style={{ textAlign: 'right', minWidth: '110px' }}>
                    <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Příští spuštění', 'Next run')}</div>
                    <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: '4px', justifyContent: 'flex-end' }}>
                      <Clock size={11} style={{ color: 'var(--text-tertiary)' }} />{formatNextRun(s.nextRun, t)}
                    </div>
                  </div>
                  <div style={{ textAlign: 'right', minWidth: '110px' }}>
                    <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Poslední spuštění', 'Last run')}</div>
                    <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{s.lastRun ? new Date(s.lastRun).toLocaleTimeString(numberLocale) : '—'}</div>
                  </div>
                  <div style={{ minWidth: '80px', textAlign: 'center' }}>
                    {s.lastStatus === 'ok' && (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', padding: '3px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600, background: 'var(--success-bg)', color: 'var(--success-text)', border: '1px solid var(--success-border)' }}>
                        <CheckCircle2 size={10} /> OK {s.lastCount != null ? `(${s.lastCount})` : ''}
                      </span>
                    )}
                    {s.lastStatus === 'error' && (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', padding: '3px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600, background: 'var(--danger-bg)', color: 'var(--danger-text)', border: '1px solid var(--danger-border)' }}>
                        <XCircle size={10} /> {t('Chyba', 'Error')}
                      </span>
                    )}
                    {!s.lastStatus && <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>—</span>}
                  </div>
                  <div style={{ display: 'flex', gap: '6px' }}>
                    <button onClick={() => manualRefresh(s.source.toLowerCase() as 'cnb' | 'ecb')} disabled={!!refreshing || loading} className="btn btn-secondary btn-sm" style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '11px', whiteSpace: 'nowrap' }}>
                      <Play size={11} style={{ animation: isRefreshing(s.source.toLowerCase()) ? 'spin 1s linear infinite' : 'none' }} />
                      {t('Spustit', 'Run')}
                    </button>
                    <button type="button" disabled={!FX_CONFIGURATION_WRITABLE} onClick={() => { setEditingSchedule(editingSchedule === s.id ? null : s.id); setScheduleDraft({ hour: s.hour, minute: s.minute, days: [...s.days] }) }}
                      style={{ background: editingSchedule === s.id ? 'var(--accent)' : 'var(--surface-3)', color: editingSchedule === s.id ? '#fff' : 'var(--text-secondary)', border: `1px solid ${editingSchedule === s.id ? 'var(--accent)' : 'var(--border)'}`, padding: '4px 8px', borderRadius: '5px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px' }}>
                      <Edit3 size={11} />
                      {editingSchedule === s.id ? <ChevronUp size={11} /> : <ChevronDown size={11} />}
                    </button>
                  </div>
                </div>

                {editingSchedule === s.id && (
                  <div style={{ padding: '14px 16px', borderTop: '1px solid var(--border)', background: 'var(--surface-1)', display: 'flex', alignItems: 'center', gap: '24px', flexWrap: 'wrap' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      <span style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)' }}>{t('Čas:', 'Time:')}</span>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                        <select aria-label={t(`Hodina plánu ${s.id}`, `Schedule hour ${s.id}`)} value={scheduleDraft.hour ?? s.hour}
                          onChange={e => setScheduleDraft(p => ({ ...p, hour: parseInt(e.target.value) }))}
                          style={{ padding: '4px 8px', fontSize: '13px', fontFamily: 'var(--font-mono)', fontWeight: 700, background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: '5px', color: 'var(--text-primary)', cursor: 'pointer' }}>
                          {Array.from({ length: 24 }, (_, i) => (
                            <option key={i} value={i}>{String(i).padStart(2, '0')}</option>
                          ))}
                        </select>
                        <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-secondary)' }}>:</span>
                        <select aria-label={t(`Minuta plánu ${s.id}`, `Schedule minute ${s.id}`)} value={scheduleDraft.minute ?? s.minute}
                          onChange={e => setScheduleDraft(p => ({ ...p, minute: parseInt(e.target.value) }))}
                          style={{ padding: '4px 8px', fontSize: '13px', fontFamily: 'var(--font-mono)', fontWeight: 700, background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: '5px', color: 'var(--text-primary)', cursor: 'pointer' }}>
                          {[0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55].map(m => (
                            <option key={m} value={m}>{String(m).padStart(2, '0')}</option>
                          ))}
                        </select>
                      </div>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <span style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)' }}>{t('Dny:', 'Days:')}</span>
                      <div style={{ display: 'flex', gap: '4px' }}>
                        {ALL_DAYS.map(day => {
                          const active = (scheduleDraft.days ?? s.days).includes(day)
                          return (
                            <button type="button" disabled={!FX_CONFIGURATION_WRITABLE} key={day}
                              onClick={() => setScheduleDraft(p => {
                                const days = p.days ?? [...s.days]
                                return { ...p, days: active ? days.filter(d => d !== day) : [...days, day] }
                              })}
                              style={{ padding: '4px 8px', fontSize: '11px', fontWeight: 700, borderRadius: '5px', cursor: 'pointer', border: `1px solid ${active ? 'var(--accent)' : 'var(--border)'}`, background: active ? 'var(--accent)' : 'var(--surface-2)', color: active ? '#fff' : 'var(--text-tertiary)', transition: 'all 0.1s' }}>
                              {t(DAY_LABELS_CS[day], DAY_LABELS_EN[day])}
                            </button>
                          )
                        })}
                      </div>
                    </div>
                    <div style={{ display: 'flex', gap: '6px', marginLeft: 'auto' }}>
                      <button type="button" disabled={!FX_CONFIGURATION_WRITABLE} onClick={() => saveSchedule(s.id)} style={{ background: 'var(--success-bg)', color: 'var(--success-text)', border: '1px solid var(--success-border)', padding: '5px 14px', borderRadius: '5px', cursor: 'pointer', fontSize: '12px', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '5px' }}>
                        <Save size={12} /> {t('Uložit', 'Save')}
                      </button>
                      <button onClick={() => setEditingSchedule(null)} style={{ background: 'var(--surface-3)', color: 'var(--text-secondary)', border: '1px solid var(--border)', padding: '5px 14px', borderRadius: '5px', cursor: 'pointer', fontSize: '12px' }}>
                        {t('Zrušit', 'Cancel')}
                      </button>
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>

        <div style={{ marginBottom: '20px' }}>
          <FxTrendChart bases={cnbRates.map(rate => rate.currencyCode)} quote="CZK" lang={language} />
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
          <div className="card">
            <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <TrendingUp size={14} style={{ color: 'var(--text-primary)' }} />
              <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                {t(`3měsíční trend ČNB ${trendPair.base}/${trendPair.quote} (orientační)`, `3-Month CNB Trend ${trendPair.base}/${trendPair.quote} (indicative)`)}
              </span>
            </div>
            <div style={{ maxHeight: '220px', overflowY: 'auto' }}>
              {trend === null && (
                <div style={{ padding: '16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{t('Načítání…', 'Loading…')}</div>
              )}
              {trend?.unavailable && (
                <div style={{ padding: '16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{t('FX služba nedostupná', 'FX service unavailable')}</div>
              )}
              {trend && !trend.unavailable && trend.points.length === 0 && (
                <div style={{ padding: '16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{t('Žádná data za posledních 3 měsíce', 'No data for the last 3 months')}</div>
              )}
              {trend && !trend.unavailable && trend.points.length > 0 && (
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                    {[t('Datum', 'Date'), t('Kurz (střed ČNB)', 'Rate (CNB mid)')].map(h => (
                      <th key={h} style={{ padding: '8px 16px', position: 'sticky', top: 0, background: 'var(--surface-1)', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase' }}>{h}</th>
                    ))}
                  </tr></thead>
                  <tbody>
                    {trend.points.map(p => (
                      <tr key={p.date} style={{ borderBottom: '1px solid var(--border)' }}>
                        <td style={{ padding: '6px 16px', fontSize: '11px', color: 'var(--text-secondary)' }}>{p.date}</td>
                        <td style={{ padding: '6px 16px', fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-primary)' }}>{p.rate}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>

          <div className="card">
            <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <History size={14} style={{ color: 'var(--text-primary)' }} />
              <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{t('Historie akcí operátora', 'Operator Action Log')}</span>
            </div>
            <div style={{ maxHeight: '220px', overflowY: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                  {[t('Čas', 'Time'), t('Zdroj', 'Source'), t('Pár', 'Pair'), t('Kurz', 'Rate')].map(h => (
                    <th key={h} style={{ padding: '8px 16px', position: 'sticky', top: 0, background: 'var(--surface-1)', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase' }}>{h}</th>
                  ))}
                </tr></thead>
                <tbody>
                  {history.map((h, i) => (
                    <tr key={i} style={{ borderBottom: '1px solid var(--border)' }}>
                      <td style={{ padding: '6px 16px', fontSize: '11px', color: 'var(--text-secondary)' }}>{new Date(h.timestamp).toLocaleTimeString(numberLocale)}</td>
                      <td style={{ padding: '6px 16px', fontSize: '11px', fontWeight: 600 }}>
                        <span style={{ padding: '2px 6px', borderRadius: '4px', background: h.source === 'CNB' ? 'var(--accent)' : h.source.includes('Override') ? 'var(--warning)' : h.source.includes('Margin') ? 'var(--info)' : 'var(--info)', color: '#fff', opacity: 0.85 }}>{h.source}</span>
                      </td>
                      <td style={{ padding: '6px 16px', fontFamily: 'var(--font-mono)', fontSize: '11px' }}>{h.pair}</td>
                      <td style={{ padding: '6px 16px', fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-primary)' }}>{h.rate.toFixed(4)}</td>
                    </tr>
                  ))}
                  {history.length === 0 && <tr><td colSpan={4} style={{ padding: '16px', textAlign: 'center', fontSize: '12px', color: 'var(--text-tertiary)' }}>{t('Zatím žádná historie', 'No history yet')}</td></tr>}
                </tbody>
              </table>
            </div>
          </div>

          <div className="card">
            <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <ArrowLeftRight size={14} style={{ color: 'var(--success)' }} />
              <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{t('Poslední konverze (fx-service)', 'Recent Conversions (fx-service)')}</span>
            </div>
            {loading && conversions.length === 0 ? (
              <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
                <RefreshCw size={16} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám…', 'Loading...')}</div>
              </div>
            ) : conversions.length === 0 ? (
              <div style={{ padding: '32px', textAlign: 'center', fontSize: '13px', color: 'var(--text-secondary)' }}>
                {t('Žádné konverze v interním systému.', 'No conversions in internal system.')}
              </div>
            ) : (
              <div style={{ overflowX: 'auto', maxHeight: '220px', overflowY: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                    {[t('Datum', 'Date'), t('Z → Na', 'From → To'), t('Částka Z', 'From'), t('Částka Na', 'To'), t('Status', 'Status')].map(h => (
                      <th key={h} style={{ padding: '8px 16px', position: 'sticky', top: 0, background: 'var(--surface-1)', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase' }}>{h}</th>
                    ))}
                  </tr></thead>
                  <tbody>{conversions.slice(0, 15).map(c => (
                    <tr key={c.id} style={{ borderBottom: '1px solid var(--border)' }}
                      onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                      onMouseLeave={e => (e.currentTarget.style.background = '')}>
                      <td style={{ padding: '10px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{c.createdAt ? new Date(c.createdAt).toLocaleString(numberLocale) : '—'}</td>
                      <td style={{ padding: '10px 16px' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                          <span style={{ fontSize: '14px' }}>{CURRENCY_META[c.fromCurrency]?.flag ?? '🏳️'}</span>
                          <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 600 }}>{c.fromCurrency}</span>
                          <ArrowLeftRight size={11} style={{ color: 'var(--text-tertiary)' }} />
                          <span style={{ fontSize: '14px' }}>{CURRENCY_META[c.toCurrency]?.flag ?? '🏳️'}</span>
                          <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', fontWeight: 600 }}>{c.toCurrency}</span>
                        </div>
                      </td>
                      <td style={{ padding: '10px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{c.fromAmount?.toLocaleString(numberLocale)}</td>
                      <td style={{ padding: '10px 16px', fontSize: '12px', color: 'var(--text-primary)', fontWeight: 600 }}>{c.toAmount?.toLocaleString(numberLocale)}</td>
                      <td style={{ padding: '10px 16px' }}>
                        <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600,
                          background: c.status === 'COMPLETED' ? 'var(--success-bg)' : 'var(--warning-bg)',
                          color: c.status === 'COMPLETED' ? 'var(--success-text)' : 'var(--warning-text)',
                          border: `1px solid ${c.status === 'COMPLETED' ? 'var(--success-border)' : 'var(--warning-border)'}` }}>{c.status}</span>
                      </td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </div>
    </AuthGuard>
  )
}
