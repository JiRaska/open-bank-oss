// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Screen-feedback board (ADR-0192) — the qualitative counterpart to the onboarding funnel at
// /onboarding/analytics. The funnel says WHERE users drop; this says WHY, in their own words.
//
// Three views, in the order an operator actually needs them: which screens generate reports, the
// newest reports themselves, and the OS/theme/locale combinations behind them — a fault confined to
// one combination is a rendering regression rather than a product problem.
//
// Privacy (ADR-0192): comments are personal data and screenshots doubly so. The screenshot is
// referenced by object key only — this board never renders the image, so viewing one stays a
// deliberate, separately-authorised act.

import { useState, useEffect, useCallback } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { MessageSquare, RefreshCw } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import {
  ResponsiveContainer, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend,
} from 'recharts'
import type { ScreenFeedbackBoard } from '@/app/api/feedback/screen-feedback/route'

const C_BUG = '#ef4444'
const C_IDEA = '#6366f1'
const C_CONFUSING = '#f59e0b'

const CATEGORY_LABEL_CS: Record<string, string> = {
  BUG: 'Chyba', IDEA: 'Nápad', CONFUSING: 'Nesrozumitelné',
}
const CATEGORY_LABEL_EN: Record<string, string> = {
  BUG: 'Bug', IDEA: 'Idea', CONFUSING: 'Confusing',
}

function formatFeedbackTimestamp(value: string | null | undefined, locale: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' })
}

export default function ScreenFeedbackPage() {
  const { language } = useLanguage()
  const cs = language === 'cs'
  const dateLocale = cs ? 'cs-CZ' : 'en-GB'
  const [data, setData] = useState<ScreenFeedbackBoard | null>(null)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/feedback/screen-feedback', { cache: 'no-store' })
      setData(await res.json())
    } catch {
      setData(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const catLabel = (c: string) => (cs ? CATEGORY_LABEL_CS[c] : CATEGORY_LABEL_EN[c]) ?? c

  if (loading && !data) {
    return <div className="page"><p role="status" aria-live="polite">{cs ? 'Načítám…' : 'Loading…'}</p></div>
  }
  if (!data?.available) {
    return (
      <div className="page">
        <PageHeader icon={<MessageSquare size={18} aria-hidden="true" />} title={cs ? 'Zpětná vazba k obrazovkám' : 'Screen feedback'} subtitle={cs ? 'Kvalitativní signály z operátorských obrazovek.' : 'Qualitative signals from operator screens.'} />
        <DataUnavailable
          kind={data?.error ? 'unreachable' : 'no_data'}
          service="ClickHouse"
          feature={cs ? 'Zpětná vazba k obrazovkám' : 'Screen feedback'}
          lang={cs ? 'cs' : 'en'}
        />
      </div>
    )
  }

  const chartData = data.screens.slice(0, 12).map((s) => ({
    screen: s.screenId,
    [cs ? 'Chyba' : 'Bug']: s.bugs,
    [cs ? 'Nápad' : 'Idea']: s.ideas,
    [cs ? 'Nesrozumitelné' : 'Confusing']: s.confusing,
  }))

  return (
    <div className="page">
      <PageHeader
        icon={<MessageSquare size={18} aria-hidden="true" />}
        title={cs ? 'Zpětná vazba k obrazovkám' : 'Screen feedback'}
        subtitle={cs ? 'Kvalitativní signály z operátorských obrazovek.' : 'Qualitative signals from operator screens.'}
        actions={<button
          type="button"
          className="btn btn-secondary"
          onClick={() => void load()}
          disabled={loading}
          aria-busy={loading}
          aria-label={cs ? 'Obnovit zpětnou vazbu k obrazovkám' : 'Refresh screen feedback'}
        >
          <RefreshCw size={16} aria-hidden="true" /> {cs ? 'Obnovit' : 'Refresh'}
        </button>}
      />

      <section>
        <h2>{cs ? 'Které obrazovky bolí' : 'Which screens hurt'}</h2>
        <ResponsiveContainer width="100%" height={320}>
          <BarChart data={chartData} layout="vertical" margin={{ left: 120 }}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis type="number" allowDecimals={false} />
            <YAxis type="category" dataKey="screen" width={120} />
            <Tooltip />
            <Legend />
            <Bar dataKey={cs ? 'Chyba' : 'Bug'} stackId="a" fill={C_BUG} />
            <Bar dataKey={cs ? 'Nápad' : 'Idea'} stackId="a" fill={C_IDEA} />
            <Bar dataKey={cs ? 'Nesrozumitelné' : 'Confusing'} stackId="a" fill={C_CONFUSING} />
          </BarChart>
        </ResponsiveContainer>
      </section>

      <section>
        <h2>{cs ? 'Poslední hlášení' : 'Recent reports'}</h2>
        <table className="table">
          <thead>
            <tr>
              <th>{cs ? 'Kdy' : 'When'}</th>
              <th>{cs ? 'Obrazovka' : 'Screen'}</th>
              <th>{cs ? 'Typ' : 'Category'}</th>
              <th>{cs ? 'Komentář' : 'Comment'}</th>
              <th>{cs ? 'Prostředí' : 'Context'}</th>
              <th>{cs ? 'Snímek' : 'Screenshot'}</th>
            </tr>
          </thead>
          <tbody>
            {data.recent.map((r) => (
              <tr key={r.reference}>
                <td>{formatFeedbackTimestamp(r.occurredAt, dateLocale)}</td>
                <td>{r.screenId}</td>
                <td>{catLabel(r.category)}</td>
                <td>{r.comment}</td>
                <td>{[r.platform, r.osVersion, r.theme, r.locale].filter(Boolean).join(' · ')}</td>
                {/* Key only — the board deliberately never renders the image. */}
                <td>{r.screenshotStatus === 'STORED' ? r.screenshotKey : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section>
        <h2>{cs ? 'Kde to selhává' : 'Where it breaks'}</h2>
        <p className="muted">
          {cs
            ? 'Kombinace prostředí seřazené podle počtu chybových hlášení. Když se hlásí jen jedna kombinace, jde o chybu vykreslení, ne o produktový problém.'
            : 'Context combinations ranked by bug reports. A fault confined to one combination is a rendering regression, not a product problem.'}
        </p>
        <table className="table">
          <thead>
            <tr>
              <th>{cs ? 'Obrazovka' : 'Screen'}</th>
              <th>{cs ? 'Platforma' : 'Platform'}</th>
              <th>OS</th>
              <th>{cs ? 'Motiv' : 'Theme'}</th>
              <th>{cs ? 'Jazyk' : 'Locale'}</th>
              <th>{cs ? 'Chyby' : 'Bugs'}</th>
              <th>{cs ? 'Celkem' : 'Total'}</th>
            </tr>
          </thead>
          <tbody>
            {data.context.map((c, i) => (
              <tr key={`${c.screenId}-${c.platform}-${c.osVersion}-${c.theme}-${c.locale}-${i}`}>
                <td>{c.screenId}</td>
                <td>{c.platform}</td>
                <td>{c.osVersion}</td>
                <td>{c.theme}</td>
                <td>{c.locale}</td>
                <td>{c.bugs}</td>
                <td>{c.submissions}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  )
}
