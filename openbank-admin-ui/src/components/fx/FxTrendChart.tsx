'use client'
import { useEffect, useMemo, useState } from 'react'
import { TrendingDown, TrendingUp } from 'lucide-react'
import { fxTrendChange, normaliseFxTrend, type FxTrendPoint } from '@/lib/fx/trend'

export function FxTrendChart({ base, quote, lang }: { base: string; quote: string; lang: 'cs' | 'en' }) {
  const [points, setPoints] = useState<FxTrendPoint[]>([])
  const [loading, setLoading] = useState(true)
  useEffect(() => {
    const controller = new AbortController()
    fetch(`/api/fx/history/${base}/${quote}`, { cache: 'no-store', signal: controller.signal })
      .then(r => r.ok ? r.json() : [])
      .then(rows => setPoints(normaliseFxTrend(Array.isArray(rows) ? rows : [])))
      .catch(() => setPoints([])).finally(() => setLoading(false))
    return () => controller.abort()
  }, [base, quote])
  const change = fxTrendChange(points)
  const geometry = useMemo(() => {
    if (points.length < 2) return ''
    const values = points.map(p => p.rate), min = Math.min(...values), max = Math.max(...values)
    const range = max - min || 1
    return points.map((p, i) => `${i ? 'L' : 'M'} ${(i / (points.length - 1)) * 100} ${44 - ((p.rate - min) / range) * 40}`).join(' ')
  }, [points])
  const up = (change ?? 0) >= 0
  const tone = up ? 'var(--success)' : 'var(--danger)'
  return <section className="card" aria-label={lang === 'cs' ? `Tříměsíční trend ${base}/${quote}` : `Three-month ${base}/${quote} trend`} style={{ padding: 20 }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'start', gap: 16 }}>
      <div><div style={{ fontSize: 11, fontWeight: 750, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '.07em' }}>{lang === 'cs' ? 'Referenční trend ČNB · 3 měsíce' : 'CNB reference trend · 3 months'}</div><h2 style={{ margin: '5px 0 0', fontSize: 18 }}>{base}/{quote}</h2></div>
      {change !== null && <div style={{ color: tone, display: 'flex', alignItems: 'center', gap: 5, fontWeight: 750 }}>{up ? <TrendingUp size={16} aria-hidden="true" /> : <TrendingDown size={16} aria-hidden="true" />}{change >= 0 ? '+' : ''}{change.toFixed(2)} %</div>}
    </div>
    {loading ? <div style={{ height: 150, display: 'grid', placeItems: 'center', color: 'var(--text-tertiary)' }}>{lang === 'cs' ? 'Načítám trend…' : 'Loading trend…'}</div> : points.length < 2 ? <div style={{ height: 150, display: 'grid', placeItems: 'center', color: 'var(--text-tertiary)', textAlign: 'center' }}>{lang === 'cs' ? 'Pro tento pár zatím není dost historických fixingů.' : 'There are not enough historical fixings for this pair yet.'}</div> : <>
      <svg viewBox="0 0 100 48" role="img" aria-label={lang === 'cs' ? `Kurz se změnil o ${change?.toFixed(2)} procenta` : `Rate changed by ${change?.toFixed(2)} percent`} style={{ width: '100%', height: 150, marginTop: 12, overflow: 'visible' }} preserveAspectRatio="none"><path d={geometry} fill="none" stroke={tone} strokeWidth="1.6" vectorEffect="non-scaling-stroke" strokeLinecap="round" strokeLinejoin="round" /><line x1="0" y1="46" x2="100" y2="46" stroke="var(--border)" vectorEffect="non-scaling-stroke" /></svg>
      <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-tertiary)', fontSize: 11 }}><span>{new Date(points[0].timestamp).toLocaleDateString(lang === 'cs' ? 'cs-CZ' : 'en-GB')}</span><span>{points.at(-1)!.rate.toLocaleString(lang === 'cs' ? 'cs-CZ' : 'en-GB', { maximumFractionDigits: 6 })} {quote}</span></div>
    </>}
    <p style={{ margin: '12px 0 0', color: 'var(--text-tertiary)', fontSize: 11 }}>{lang === 'cs' ? 'Orientační střed ČNB; nejde o historickou závaznou klientskou nabídku.' : 'Indicative CNB mid-rate; not a binding historical customer quote.'}</p>
  </section>
}
