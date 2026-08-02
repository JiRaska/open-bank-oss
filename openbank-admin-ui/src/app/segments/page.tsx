// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import { Users } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'

// Read-only by design. ADR-0201 D1: a segment is a versioned artifact defined in code, reviewed and
// released like anything else — "no free-form SQL from a UI". A marketer picks from this catalogue;
// a new segment is a pull request. Preview exists so that choice is informed, not so it is editable.

interface Segment {
  name: string
  version: number
  rules: string[]
}

interface Preview {
  size?: number
  asOf?: string
  state: string
}

export default function SegmentsPage() {
  const { t, language } = useLanguage()
  const [items, setItems] = useState<Segment[]>([])
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)
  const [previews, setPreviews] = useState<Record<string, Preview | 'loading'>>({})

  useEffect(() => {
    fetch('/api/segments')
      .then(r => r.json())
      .then((d: { items: Segment[]; state: string }) => {
        if (d.state !== 'ok') {
          setUnavailable(
            d.state === 'unauthorized' ? 'unauthorized' : d.state === 'not_deployed' ? 'not_deployed' : 'unreachable',
          )
          return
        }
        setItems(d.items ?? [])
      })
      .catch(() => setUnavailable('unreachable'))
      .finally(() => setLoading(false))
  }, [])

  const key = (s: Segment) => `${s.name}@${s.version}`

  // Previews are on demand, not eager: each one runs a real cohort evaluation against the silver
  // layer, so loading the page must not fire one per row.
  const loadPreview = (s: Segment) => {
    const k = key(s)
    setPreviews(p => ({ ...p, [k]: 'loading' }))
    fetch(`/api/segments/${encodeURIComponent(s.name)}/${s.version}/preview`)
      .then(r => r.json())
      .then((d: Preview) => setPreviews(p => ({ ...p, [k]: d })))
      .catch(() => setPreviews(p => ({ ...p, [k]: { state: 'unreachable' } })))
  }

  const formatAsOf = (iso: string) =>
    new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', {
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(iso))

  const renderPreview = (s: Segment) => {
    const p = previews[key(s)]
    if (!p) {
      return (
        <button
          onClick={() => loadPreview(s)}
          className="rounded-md border px-2 py-1 text-xs hover:bg-muted"
        >
          {t('Spočítat', 'Count')}
        </button>
      )
    }
    if (p === 'loading') return <span className="text-xs text-muted-foreground">{t('Počítám…', 'Counting…')}</span>
    if (p.state !== 'ok') {
      // Never render a failed preview as 0 — "nobody matches" is a business answer a marketer
      // would act on, and a 403 or a timeout is not that answer.
      return (
        <span className="text-xs text-amber-600">
          {p.state === 'unauthorized'
            ? t('Bez oprávnění', 'Not permitted')
            : p.state === 'unknown_segment'
              ? t('Neznámý segment', 'Unknown segment')
              : t('Nedostupné', 'Unavailable')}
        </span>
      )
    }
    return (
      <span className="text-sm">
        <strong>{p.size?.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong>{' '}
        <span className="text-muted-foreground">{t('lidí', 'people')}</span>
        {p.asOf && (
          // The cohort moves as the silver layer moves; a number without its timestamp is a claim
          // with no time attached, which is what ADR-0201 D1's "provably a different version" rules out.
          <span className="ml-2 text-xs text-muted-foreground">{t('k', 'as of')} {formatAsOf(p.asOf)}</span>
        )}
      </span>
    )
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('Segmenty', 'Segments')}
        subtitle={t(
          'Na koho lze cílit. Definice jsou v kódu a verzované — nový segment je pull request.',
          'Who can be targeted. Definitions live in code and are versioned — a new segment is a pull request.',
        )}
        icon={<Users className="h-6 w-6" />}
      />

      {loading && <p className="text-sm text-muted-foreground">{t('Načítám…', 'Loading…')}</p>}

      {!loading && unavailable && (
        <DataUnavailable kind={unavailable} service="Campaign-service" feature={t('Segmenty', 'Segments')} />
      )}

      {!loading && !unavailable && items.length === 0 && (
        <p className="text-sm text-muted-foreground">{t('Katalog je prázdný.', 'The catalogue is empty.')}</p>
      )}

      {!loading && !unavailable && items.length > 0 && (
        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full text-sm">
            <thead className="bg-muted/50 text-left">
              <tr>
                <th className="px-4 py-2 font-medium">{t('Segment', 'Segment')}</th>
                <th className="px-4 py-2 font-medium">{t('Verze', 'Version')}</th>
                <th className="px-4 py-2 font-medium">{t('Koho zahrnuje', 'Who it selects')}</th>
                <th className="px-4 py-2 font-medium">{t('Velikost', 'Size')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map(s => (
                <tr key={key(s)} className="border-t">
                  <td className="px-4 py-2 font-medium">{s.name}</td>
                  <td className="px-4 py-2 tabular-nums text-muted-foreground">v{s.version}</td>
                  <td className="px-4 py-2">
                    <ul className="list-inside list-disc text-muted-foreground">
                      {s.rules.map(r => (
                        <li key={r}>{r}</li>
                      ))}
                    </ul>
                  </td>
                  <td className="px-4 py-2">{renderPreview(s)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
