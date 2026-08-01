// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { Megaphone } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'

// Read-only by design (#2895). Authoring is ADR-0221: `submit` → `activate`-by-a-different-approver
// is a two-people-at-a-screen flow, and exposing half of it as buttons would lose the point of the
// four-eyes gate while looking like it had one.

interface Campaign {
  id: string
  name: string
  goal: string
  segmentRef: { name: string; version: number }
  state: string
  createdBy: string
  approvedBy: string | null
  createdAt: string
}

export default function CampaignsPage() {
  const { t, language } = useLanguage()
  const [items, setItems] = useState<Campaign[]>([])
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [stateFilter, setStateFilter] = useState('')

  useEffect(() => {
    fetch('/api/campaigns')
      .then(r => r.json())
      .then((d: { items: Campaign[]; state: string }) => {
        if (d.state !== 'ok') {
          setUnavailable(d.state === 'unauthorized' ? 'unauthorized' : d.state === 'not_deployed' ? 'not_deployed' : 'unreachable')
          return
        }
        setItems(d.items ?? [])
      })
      .catch(() => setUnavailable('unreachable'))
      .finally(() => setLoading(false))
  }, [])

  const fmtDate = (iso: string | null | undefined) =>
    iso ? new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'medium' }).format(new Date(iso)) : '—'

  // States present in the data, not a hardcoded list: a state the API starts returning would be
  // missing from the filter forever, and the rows would look like they had vanished.
  const states = Array.from(new Set(items.map(c => c.state))).sort()

  const needle = search.trim().toLowerCase()
  const filtered = items.filter(
    c =>
      (!stateFilter || c.state === stateFilter) &&
      (!needle || c.name.toLowerCase().includes(needle) || (c.goal ?? '').toLowerCase().includes(needle)),
  )

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('Kampaně', 'Campaigns')}
        subtitle={t('Co běží, kdo byl zařazen a co k lidem opravdu dorazilo', 'What is running, who was enrolled, and what actually reached them')}
        icon={<Megaphone className="h-6 w-6" />}
      />

      <Link
        href="/campaigns/new"
        className="inline-flex items-center gap-1 rounded-md border px-3 py-1.5 text-sm hover:bg-muted"
      >
        {t('Nová kampaň', 'New campaign')}
      </Link>

      {loading && <p className="text-sm text-muted-foreground">{t('Načítám…', 'Loading…')}</p>}

      {!loading && unavailable && <DataUnavailable kind={unavailable} service="Campaign-service" feature={t('Kampaně', 'Campaigns')} />}

      {/* Filtered in the browser, unlike the send log. A campaign list has one row per campaign,
          not one per recipient, so it is bounded by how many campaigns a bank runs — the send log
          is bounded by the audience, which is why that one pages on the server. */}
      {!loading && !unavailable && items.length > 0 && (
        <div className="flex flex-wrap items-center gap-3">
          <input
            type="search"
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder={t('Hledat podle názvu nebo cíle…', 'Search by name or goal…')}
            aria-label={t('Hledat kampaně', 'Search campaigns')}
            className="w-72 rounded-md border bg-transparent px-3 py-1.5 text-sm"
          />
          <select
            value={stateFilter}
            onChange={e => setStateFilter(e.target.value)}
            aria-label={t('Filtr stavu', 'State filter')}
            className="rounded-md border bg-transparent px-2 py-1.5 text-sm"
          >
            <option value="">{t('Všechny stavy', 'All states')}</option>
            {states.map(st => (
              <option key={st} value={st}>
                {st} ({items.filter(c => c.state === st).length})
              </option>
            ))}
          </select>
          {filtered.length !== items.length && (
            <span className="text-xs text-muted-foreground">
              {t('Zobrazeno', 'Showing')} {filtered.length} {t('z', 'of')} {items.length}
            </span>
          )}
        </div>
      )}

      {!loading && !unavailable && items.length === 0 && (
        <p className="text-sm text-muted-foreground">{t('Zatím žádné kampaně.', 'No campaigns yet.')}</p>
      )}

      {!loading && !unavailable && items.length > 0 && filtered.length === 0 && (
        // Distinct from "no campaigns yet": one is an empty estate, the other is a filter the
        // user can undo, and rendering the same sentence for both hides the undo.
        <p className="text-sm text-muted-foreground">
          {t('Žádná kampaň neodpovídá filtru.', 'No campaign matches the filter.')}
        </p>
      )}

      {!loading && !unavailable && filtered.length > 0 && (
        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full text-sm">
            <thead className="bg-muted/50 text-left">
              <tr>
                <th className="px-4 py-2 font-medium">{t('Název', 'Name')}</th>
                <th className="px-4 py-2 font-medium">{t('Stav', 'State')}</th>
                <th className="px-4 py-2 font-medium">{t('Segment', 'Segment')}</th>
                <th className="px-4 py-2 font-medium">{t('Vytvořil', 'Created by')}</th>
                <th className="px-4 py-2 font-medium">{t('Schválil', 'Approved by')}</th>
                <th className="px-4 py-2 font-medium">{t('Vytvořeno', 'Created')}</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(c => (
                <tr key={c.id} className="border-t">
                  <td className="px-4 py-2">
                    <Link href={`/campaigns/${c.id}`} className="font-medium hover:underline">
                      {c.name}
                    </Link>
                    <div className="text-xs text-muted-foreground">{c.goal}</div>
                  </td>
                  <td className="px-4 py-2">
                    <StatusBadge status={c.state} />
                  </td>
                  <td className="px-4 py-2 font-mono text-xs">
                    {c.segmentRef?.name}@{c.segmentRef?.version}
                  </td>
                  <td className="px-4 py-2 text-xs">{c.createdBy}</td>
                  {/* The checker, shown next to the maker on purpose: the maker/checker pair is
                      the audit-relevant fact about an ACTIVE campaign, not a detail. */}
                  <td className="px-4 py-2 text-xs">{c.approvedBy ?? '—'}</td>
                  <td className="px-4 py-2 text-xs whitespace-nowrap">{fmtDate(c.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
