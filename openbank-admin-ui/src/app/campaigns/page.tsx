// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The campaign operator's landing surface.
//
// WHAT WAS WRONG
// A table of raw enum states (`PENDING_APPROVAL`) with a search box. That is an inventory, and a
// marketer does not open a console to take inventory. Their questions are:
//   - what is live right now
//   - what is stuck waiting for someone to approve it, and for how long
//   - what am I sitting on unfinished
// The campaign LIFECYCLE answers all three at a glance, so it is the primary object; the rows are
// the drill-down. Same board, motion and age semantics as the lending pipeline — a third visual
// language on one console would be worse than either (see components/flow/StageBoard).
//
// WHAT THIS PAGE HONESTLY CANNOT SHOW
// Reach, delivery and conversion — the numbers a CDP screen is really about. `GET /api/v1/campaigns`
// returns campaign records only; enrolments and sends are per-campaign endpoints, so a performance
// overview here would mean one request per campaign or an API that does not exist yet. Inventing
// the numbers, or quietly implying the page covers performance, would be worse than saying so — the
// board carries that sentence rather than hiding it in a doc.
//
// Read-only by design (#2895). Authoring is ADR-0221: `submit` → `activate`-by-a-DIFFERENT-approver
// is a two-people-at-a-screen flow, and exposing half of it as buttons would lose the point of the
// four-eyes gate while looking like it had one.

'use client'

import { useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { Megaphone, Play, PauseCircle, PenLine, ShieldCheck } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import { StageBoard, summariseBy, type StageDef } from '@/components/flow/StageBoard'

/** `/api/v1/campaigns/summary` (#3296). Null when the deployed service predates the endpoint —
 *  the page then keeps saying reach is not available rather than showing zeros that look like
 *  "nobody was reached". */
interface CampaignSummary {
  campaignId: string
  enrolled: number
  sent: number
  suppressed: number
  failed: number
}

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

/** `CampaignState` in campaign-service: DRAFT → PENDING_APPROVAL → ACTIVE → PAUSED → CLOSED.
 *  Only CLOSED ends the lifecycle, so only CLOSED is exempt from age tinting — a campaign paused
 *  for a week IS something someone should look at, unlike one that finished a week ago. */
const LIFECYCLE: { key: string; cs: string; en: string; terminal?: boolean }[] = [
  { key: 'DRAFT', cs: 'Rozpracovaná', en: 'Draft' },
  { key: 'PENDING_APPROVAL', cs: 'Čeká na schválení', en: 'Awaiting approval' },
  { key: 'ACTIVE', cs: 'Běží', en: 'Running' },
  { key: 'PAUSED', cs: 'Pozastavená', en: 'Paused' },
  { key: 'CLOSED', cs: 'Ukončená', en: 'Closed', terminal: true },
]

export default function CampaignsPage() {
  const { t, language } = useLanguage()
  const [items, setItems] = useState<Campaign[]>([])
  const [summary, setSummary] = useState<Record<string, CampaignSummary> | null>(null)
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [stateFilter, setStateFilter] = useState<string | null>(null)

  useEffect(() => {
    fetch('/api/campaigns')
      .then(r => r.json())
      .then((d: { items: Campaign[]; state: string; summary?: CampaignSummary[] | null }) => {
        if (d.state !== 'ok') {
          setUnavailable(d.state === 'unauthorized' ? 'unauthorized' : d.state === 'not_deployed' ? 'not_deployed' : 'unreachable')
          return
        }
        setItems(d.items ?? [])
        setSummary(
          Array.isArray(d.summary) ? Object.fromEntries(d.summary.map(x => [x.campaignId, x])) : null,
        )
      })
      .catch(() => setUnavailable('unreachable'))
      .finally(() => setLoading(false))
  }, [])

  const fmtDate = (iso: string | null | undefined) =>
    iso ? new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'medium' }).format(new Date(iso)) : '—'

  const label = (state: string) => {
    const s = LIFECYCLE.find(x => x.key === state)
    return s ? (language === 'cs' ? s.cs : s.en) : state
  }

  const stages: StageDef[] = LIFECYCLE.map(s => ({
    key: s.key,
    label: language === 'cs' ? s.cs : s.en,
    terminal: s.terminal,
  }))

  const stats = useMemo(
    () => summariseBy(items, c => c.state, c => c.createdAt),
    [items],
  )

  /** Any state the API returns that the lifecycle above does not name. Shown rather than dropped:
   *  a campaign silently missing from both the board and the count is worse than an unknown label. */
  const unknownStates = useMemo(
    () => Array.from(new Set(items.map(c => c.state))).filter(s => !LIFECYCLE.some(l => l.key === s)),
    [items],
  )

  const counts = (k: string) => stats.get(k)?.count ?? 0

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
        subtitle={t(
          'Co běží, co čeká na schválení a co je rozpracované. Doručení a odezvu najdete v detailu kampaně.',
          'What is running, what waits for approval, and what is unfinished. Delivery and response live in the campaign detail.',
        )}
        icon={<Megaphone className="h-6 w-6" />}
        actions={
          <Link href="/campaigns/new" className="btn btn-primary" style={{ fontSize: 12 }}>
            {t('Nová kampaň', 'New campaign')}
          </Link>
        }
      />

      {loading && <p className="text-sm text-muted-foreground">{t('Načítám…', 'Loading…')}</p>}

      {!loading && unavailable && <DataUnavailable kind={unavailable} service="Campaign-service" feature={t('Kampaně', 'Campaigns')} />}

      {!loading && !unavailable && items.length === 0 && (
        <p className="text-sm text-muted-foreground">{t('Zatím žádné kampaně.', 'No campaigns yet.')}</p>
      )}

      {!loading && !unavailable && items.length > 0 && (
        <>
          <div className="grid-4">
            <StatCard label={t('Běží', 'Running')} value={counts('ACTIVE')} icon={<Play size={13} />} />
            <StatCard
              label={t('Čeká na schválení', 'Awaiting approval')}
              value={counts('PENDING_APPROVAL')}
              tone={counts('PENDING_APPROVAL') > 0 ? 'warning' : undefined}
              hint={t('potřebuje druhý pár očí', 'needs a second approver')}
              icon={<ShieldCheck size={13} />}
            />
            <StatCard label={t('Rozpracované', 'Drafts')} value={counts('DRAFT')} icon={<PenLine size={13} />} />
            <StatCard label={t('Pozastavené', 'Paused')} value={counts('PAUSED')}
                      tone={counts('PAUSED') > 0 ? 'warning' : undefined} icon={<PauseCircle size={13} />} />
          </div>

          <StageBoard
            stages={stages}
            stats={stats}
            selected={stateFilter}
            onSelect={setStateFilter}
            lang={language}
            ariaLabel={t('Životní cyklus kampaní podle stavu', 'Campaign lifecycle by state')}
            footnote={summary
              ? t('Dosah a doručení v tabulce níže.', 'Reach and delivery in the table below.')
              : t(
                  'Doručení a odezva zde nejsou — nasazená služba je zatím nevrací.',
                  'Delivery and response are not here — the deployed service does not return them yet.',
                )}
          />

          {unknownStates.length > 0 && (
            <p className="text-xs" style={{ color: 'var(--warning)' }} data-testid="unknown-states">
              {t(
                `Stav mimo známý životní cyklus: ${unknownStates.join(', ')} — na tabuli chybí, v tabulce je najdete.`,
                `State outside the known lifecycle: ${unknownStates.join(', ')} — absent from the board, still listed below.`,
              )}
            </p>
          )}

          <div className="flex flex-wrap items-center gap-3">
            <input
              type="search"
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder={t('Hledat podle názvu nebo cíle…', 'Search by name or goal…')}
              aria-label={t('Hledat kampaně', 'Search campaigns')}
              className="w-72 rounded-md border bg-transparent px-3 py-1.5 text-sm"
            />
            {stateFilter && (
              <button className="btn btn-secondary" style={{ fontSize: 11 }} onClick={() => setStateFilter(null)} data-testid="clear-state">
                {t('Filtr:', 'Filter:')} {label(stateFilter)} ✕
              </button>
            )}
            {filtered.length !== items.length && (
              <span className="text-xs text-muted-foreground">
                {t('Zobrazeno', 'Showing')} {filtered.length} {t('z', 'of')} {items.length}
              </span>
            )}
          </div>

          {filtered.length === 0 && (
            // Distinct from "no campaigns yet": one is an empty estate, the other is a filter the
            // user can undo, and rendering the same sentence for both hides the undo.
            <p className="text-sm text-muted-foreground">
              {t('Žádná kampaň neodpovídá filtru.', 'No campaign matches the filter.')}
            </p>
          )}

          {filtered.length > 0 && (
            <div className="overflow-x-auto rounded-lg border">
              <table className="w-full text-sm">
                <thead className="bg-muted/50 text-left">
                  <tr>
                    <th className="px-4 py-2 font-medium">{t('Název', 'Name')}</th>
                    <th className="px-4 py-2 font-medium">{t('Stav', 'State')}</th>
                    <th className="px-4 py-2 font-medium">{t('Segment', 'Segment')}</th>
                    {summary && <th className="px-4 py-2 font-medium">{t('Zařazeno', 'Enrolled')}</th>}
                    {summary && <th className="px-4 py-2 font-medium">{t('Doručeno', 'Sent')}</th>}
                    {summary && <th className="px-4 py-2 font-medium">{t('Potlačeno', 'Suppressed')}</th>}
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
                      {/* Human label for the reader, raw state kept as the title so the screen and
                          the state machine can never end up describing different things. */}
                      <td className="px-4 py-2">
                        <span title={c.state}><StatusBadge status={c.state} label={label(c.state)} /></span>
                      </td>
                      <td className="px-4 py-2 font-mono text-xs">
                        {c.segmentRef?.name}@{c.segmentRef?.version}
                      </td>
                      {summary && <td className="px-4 py-2 text-xs">{summary[c.id]?.enrolled ?? 0}</td>}
                      {summary && <td className="px-4 py-2 text-xs">{summary[c.id]?.sent ?? 0}</td>}
                      {/* Suppressed is tinted only when it happened: "0 suppressed" is the normal
                          case and colouring it would make every row look like it needs attention.
                          A non-zero here is the answer to "why was reach low", so it must not read
                          as ordinary. */}
                      {summary && (
                        <td
                          className="px-4 py-2 text-xs"
                          style={(summary[c.id]?.suppressed ?? 0) > 0 ? { color: 'var(--warning)', fontWeight: 600 } : undefined}
                        >
                          {summary[c.id]?.suppressed ?? 0}
                        </td>
                      )}
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
        </>
      )}
    </div>
  )
}
