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
import dynamic from 'next/dynamic'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { MessageSquare, RefreshCw } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import type { ScreenFeedbackBoard } from '@/app/api/feedback/screen-feedback/route'

// The board can render loading and recovery states before feedback exists. Keep the 326 kB chart
// engine out of that critical path and reserve its final height while the chunk arrives.
const ScreenPainChart = dynamic(
  () => import('@/components/feedback/ScreenPainChart').then(module => module.ScreenPainChart),
  { ssr: false, loading: () => <div style={{ height: 320 }} aria-hidden="true" /> },
)

const CATEGORY_LABEL: Record<string, { cs: string; en: string }> = {
  BUG: { cs: 'Chyba', en: 'Bug' },
  IDEA: { cs: 'Nápad', en: 'Idea' },
  CONFUSING: { cs: 'Nesrozumitelné', en: 'Confusing' },
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

  const catLabel = (c: string) => CATEGORY_LABEL[c]?.[language === 'cs' ? 'cs' : 'en'] ?? c

  if (loading && !data) {
    return <div className="page"><p role="status" aria-live="polite">{language === 'cs' ? 'Načítám…' : 'Loading…'}</p></div>
  }
  if (!data?.available) {
    return (
      <div className="page">
        <PageHeader icon={<MessageSquare size={18} aria-hidden="true" />} title={language === 'cs' ? 'Zpětná vazba k obrazovkám' : 'Screen feedback'} subtitle={language === 'cs' ? 'Kvalitativní signály z operátorských obrazovek.' : 'Qualitative signals from operator screens.'} />
        <DataUnavailable
          kind={data?.error ? 'unreachable' : 'no_data'}
          service="ClickHouse"
          feature={language === 'cs' ? 'Zpětná vazba k obrazovkám' : 'Screen feedback'}
          lang={language === 'cs' ? 'cs' : 'en'}
        />
      </div>
    )
  }

  const chartData = data.screens.slice(0, 12).map((s) => ({
    screen: s.screenId,
    bug: s.bugs,
    idea: s.ideas,
    confusing: s.confusing,
  }))

  return (
    <div className="page">
      <PageHeader
        icon={<MessageSquare size={18} aria-hidden="true" />}
        title={language === 'cs' ? 'Zpětná vazba k obrazovkám' : 'Screen feedback'}
        subtitle={language === 'cs' ? 'Kvalitativní signály z operátorských obrazovek.' : 'Qualitative signals from operator screens.'}
        actions={<button
          type="button"
          className="btn btn-secondary"
          onClick={() => void load()}
          disabled={loading}
          aria-busy={loading}
          aria-label={language === 'cs' ? 'Obnovit zpětnou vazbu k obrazovkám' : 'Refresh screen feedback'}
        >
          <RefreshCw size={16} aria-hidden="true" /> {language === 'cs' ? 'Obnovit' : 'Refresh'}
        </button>}
      />

      <section>
        <h2>{language === 'cs' ? 'Které obrazovky bolí' : 'Which screens hurt'}</h2>
        <ScreenPainChart data={chartData} cs={cs} />
      </section>

      <section>
        <h2>{language === 'cs' ? 'Poslední hlášení' : 'Recent reports'}</h2>
        <table className="table">
          <thead>
            <tr>
              <th>{language === 'cs' ? 'Kdy' : 'When'}</th>
              <th>{language === 'cs' ? 'Obrazovka' : 'Screen'}</th>
              <th>{language === 'cs' ? 'Typ' : 'Category'}</th>
              <th>{language === 'cs' ? 'Komentář' : 'Comment'}</th>
              <th>{language === 'cs' ? 'Prostředí' : 'Context'}</th>
              <th>{language === 'cs' ? 'Snímek' : 'Screenshot'}</th>
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
        <h2>{language === 'cs' ? 'Kde to selhává' : 'Where it breaks'}</h2>
        <p className="muted">
          {language === 'cs'
            ? 'Kombinace prostředí seřazené podle počtu chybových hlášení. Když se hlásí jen jedna kombinace, jde o chybu vykreslení, ne o produktový problém.'
            : 'Context combinations ranked by bug reports. A fault confined to one combination is a rendering regression, not a product problem.'}
        </p>
        <table className="table">
          <thead>
            <tr>
              <th>{language === 'cs' ? 'Obrazovka' : 'Screen'}</th>
              <th>{language === 'cs' ? 'Platforma' : 'Platform'}</th>
              <th>OS</th>
              <th>{language === 'cs' ? 'Motiv' : 'Theme'}</th>
              <th>{language === 'cs' ? 'Jazyk' : 'Locale'}</th>
              <th>{language === 'cs' ? 'Chyby' : 'Bugs'}</th>
              <th>{language === 'cs' ? 'Celkem' : 'Total'}</th>
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
