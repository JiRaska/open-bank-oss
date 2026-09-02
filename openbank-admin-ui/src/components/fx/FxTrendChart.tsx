'use client'
import { useEffect, useId, useMemo, useState } from 'react'
import { Minus, TrendingDown, TrendingUp } from 'lucide-react'
import { fxTrendDirection, fxTrendSummary, fxTrendTimelinePositions, normaliseFxTrend, type FxTrendPoint } from '@/lib/fx/trend'
import styles from './FxTrendChart.module.css'

const WIDTH = 320
const HEIGHT = 104
const PADDING_X = 4
const PADDING_TOP = 12
const PADDING_BOTTOM = 12

function chartGeometry(points: FxTrendPoint[]) {
  if (points.length < 2) return null
  const values = points.map(point => point.rate)
  const minimum = Math.min(...values)
  const maximum = Math.max(...values)
  const padding = Math.max((maximum - minimum) * .12, maximum * .002, .000001)
  const low = minimum - padding
  const high = maximum + padding
  const span = high - low
  const plotWidth = WIDTH - PADDING_X * 2
  const plotHeight = HEIGHT - PADDING_TOP - PADDING_BOTTOM
  const positions = fxTrendTimelinePositions(points)
  const coordinates = points.map((point, index) => ({
    x: PADDING_X + positions[index] * plotWidth,
    y: PADDING_TOP + ((high - point.rate) / span) * plotHeight,
  }))
  const line = coordinates.map(({ x, y }, index) => `${index ? 'L' : 'M'} ${x.toFixed(2)} ${y.toFixed(2)}`).join(' ')
  return {
    line,
    area: `${line} L ${coordinates.at(-1)!.x.toFixed(2)} ${HEIGHT - PADDING_BOTTOM} L ${coordinates[0].x.toFixed(2)} ${HEIGHT - PADDING_BOTTOM} Z`,
    first: coordinates[0],
    last: coordinates.at(-1)!,
  }
}

export function FxTrendChart({ bases, quote, lang }: { bases: string[]; quote: string; lang: 'cs' | 'en' }) {
  const availableBases = useMemo(() => [...new Set(['EUR', ...bases])].sort(), [bases])
  const [base, setBase] = useState('EUR')
  const [points, setPoints] = useState<FxTrendPoint[]>([])
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const gradientId = `fx-trend-${useId().replace(/:/g, '')}`

  useEffect(() => {
    const controller = new AbortController()
    fetch(`/api/fx/history/${base}/${quote}`, { cache: 'no-store', signal: controller.signal })
      .then(response => {
        if (!response.ok) throw new Error(`FX history HTTP ${response.status}`)
        return response.json()
      })
      .then(rows => {
        if (!controller.signal.aborted) setPoints(normaliseFxTrend(Array.isArray(rows) ? rows : []))
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setPoints([])
          setFailed(true)
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [attempt, base, quote])

  // Do not label the newly selected pair with the previous pair's summary while its request is in flight.
  const summary = loading ? null : fxTrendSummary(points)
  const geometry = useMemo(() => loading ? null : chartGeometry(points), [loading, points])
  const direction = fxTrendDirection(summary?.changePercent ?? 0)
  const tone = direction === 'up' ? 'var(--success)' : direction === 'down' ? 'var(--danger)' : 'var(--accent)'
  const locale = lang === 'cs' ? 'cs-CZ' : 'en-GB'
  const formatRate = (rate: number) => rate.toLocaleString(locale, { maximumFractionDigits: 6 })
  const formatDate = (timestamp: string) => new Date(timestamp).toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric' })
  const chartLabel = summary && (lang === 'cs'
    ? `${base}/${quote}: z ${formatRate(summary.first.rate)} na ${formatRate(summary.last.rate)}, změna ${summary.changePercent.toFixed(2)} procenta; minimum ${formatRate(summary.minimum.rate)}, maximum ${formatRate(summary.maximum.rate)}`
    : `${base}/${quote}: from ${formatRate(summary.first.rate)} to ${formatRate(summary.last.rate)}, a ${summary.changePercent.toFixed(2)} percent change; minimum ${formatRate(summary.minimum.rate)}, maximum ${formatRate(summary.maximum.rate)}`)

  return <section className={`card ${styles.card}`} data-testid="fx-trend-chart" aria-label={lang === 'cs' ? `Tříměsíční trend ${base}/${quote}` : `Three-month ${base}/${quote} trend`}>
    <div className={styles.header}>
      <div>
        <div className={styles.eyebrow}>{lang === 'cs' ? 'Referenční trend ČNB · 3 kalendářní měsíce' : 'CNB reference trend · 3 calendar months'}</div>
        <div className={styles.pair}>
          <select value={base} onChange={event => { setLoading(true); setFailed(false); setBase(event.target.value) }} aria-label={lang === 'cs' ? 'Měna trendu' : 'Trend currency'}>{availableBases.map(currency => <option key={currency} value={currency}>{currency}</option>)}</select>
          <h2>/ {quote}</h2>
        </div>
      </div>
      {summary && <div className={styles.change} style={{ color: tone }} aria-label={lang === 'cs' ? `Změna za období ${summary.changePercent.toFixed(2)} procenta` : `Period change ${summary.changePercent.toFixed(2)} percent`}>
        {direction === 'up' ? <TrendingUp size={16} aria-hidden="true" /> : direction === 'down' ? <TrendingDown size={16} aria-hidden="true" /> : <Minus size={16} aria-hidden="true" />}
        {summary.changePercent >= 0 ? '+' : ''}{summary.changePercent.toFixed(2)} %
      </div>}
    </div>

    {loading ? <div className={styles.state} aria-live="polite">{lang === 'cs' ? 'Načítám trend…' : 'Loading trend…'}</div> : failed ? <div className={styles.state} role="alert"><div><p>{lang === 'cs' ? 'Historický trend teď nelze načíst.' : 'Historical trend is unavailable right now.'}</p><button type="button" className="btn btn-secondary btn-sm" onClick={() => { setLoading(true); setFailed(false); setAttempt(value => value + 1) }}>{lang === 'cs' ? 'Zkusit znovu' : 'Try again'}</button></div></div> : !summary || !geometry ? <div className={styles.state}>{lang === 'cs' ? 'Pro tento pár zatím není dost historických fixingů.' : 'There are not enough historical fixings for this pair yet.'}</div> : <>
      <div className={styles.chartShell}>
        <svg className={styles.chart} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={chartLabel || undefined} preserveAspectRatio="none">
          <defs><linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor={tone} stopOpacity=".24" /><stop offset="1" stopColor={tone} stopOpacity=".02" /></linearGradient></defs>
          {[.25, .5, .75].map(fraction => <line key={fraction} x1={PADDING_X} y1={PADDING_TOP + fraction * (HEIGHT - PADDING_TOP - PADDING_BOTTOM)} x2={WIDTH - PADDING_X} y2={PADDING_TOP + fraction * (HEIGHT - PADDING_TOP - PADDING_BOTTOM)} stroke="var(--border)" strokeWidth=".6" vectorEffect="non-scaling-stroke" />)}
          <path d={geometry.area} fill={`url(#${gradientId})`} />
          <path d={geometry.line} fill="none" stroke={tone} strokeWidth="2" vectorEffect="non-scaling-stroke" strokeLinecap="round" strokeLinejoin="round" />
          <circle cx={geometry.first.x} cy={geometry.first.y} r="3" fill="var(--surface)" stroke={tone} strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
          <circle cx={geometry.last.x} cy={geometry.last.y} r="3" fill={tone} stroke="var(--surface)" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
        </svg>
        <div className={styles.axis} aria-hidden="true"><span>{formatDate(summary.first.timestamp)}</span><span>{formatDate(summary.last.timestamp)}</span></div>
      </div>
      <div className={styles.metrics}>
        {[
          { label: lang === 'cs' ? 'Začátek' : 'Start', point: summary.first },
          { label: lang === 'cs' ? 'Aktuálně' : 'Latest', point: summary.last },
          { label: lang === 'cs' ? 'Minimum' : 'Minimum', point: summary.minimum },
          { label: lang === 'cs' ? 'Maximum' : 'Maximum', point: summary.maximum },
        ].map(metric => <div className={styles.metric} key={metric.label}><span className={styles.metricLabel}>{metric.label}</span><span className={styles.metricValue}>{formatRate(metric.point.rate)} {quote}</span><span className={styles.metricDate}>{formatDate(metric.point.timestamp)}</span></div>)}
      </div>
    </>}
    <p className={styles.note}><strong>{lang === 'cs' ? 'Jak číst graf:' : 'How to read this:'}</strong> {lang === 'cs' ? `kladná změna znamená, že za jednu jednotku ${base} je nyní potřeba více ${quote}. Orientační střed ČNB; nejde o historickou závaznou klientskou nabídku.` : `a positive change means one unit of ${base} now costs more ${quote}. Indicative CNB mid-rate; not a binding historical customer quote.`}</p>
  </section>
}
