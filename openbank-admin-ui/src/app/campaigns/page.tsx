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
// EVIDENCE SEMANTICS
// Campaign-service supplies enrolment and notification handoff. Analytics independently supplies
// server-attributed app observations. The UI keeps those stages separate and renders unavailable or
// not-observed explicitly; neither state is a numeric zero and neither handoff nor click is delivery.
//
// Read-only by design (#2895). Authoring is ADR-0221: `submit` → `activate`-by-a-DIFFERENT-approver
// is a two-people-at-a-screen flow, and exposing half of it as buttons would lose the point of the
// four-eyes gate while looking like it had one.

'use client'

import { useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import {
  Activity,
  ArrowRight,
  Clock3,
  Eye,
  Gauge,
  GitBranch,
  Megaphone,
  PauseCircle,
  PenLine,
  Play,
  ShieldCheck,
  Sparkles,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { StageBoard, summariseBy, type StageDef } from '@/components/flow/StageBoard'
import { CampaignPlanningBoard, type CampaignPlan } from '@/components/campaigns/CampaignPlanningBoard'

/** `/api/v1/campaigns/summary` (#3296). Null when the deployed service predates the endpoint —
 *  the page then keeps saying reach is not available rather than showing zeros that look like
 *  "nobody was reached". */
interface CampaignSummary {
  campaignId: string
  enrolled: number
  sent: number
  suppressed: number
  failed: number
  outcomes?: { outcome: string; count: number }[]
}

interface CampaignEngagement {
  campaignId: string
  impressions: number
  clicks: number
  dismissals: number
  firstObservedAt: string
  lastObservedAt: string
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

/** A marketing landing page should surface the next decision, not merely count lifecycle states.
 *  This is a display priority only — it never changes the campaign state machine. */
const DECISION_PRIORITY: Record<string, number> = {
  PENDING_APPROVAL: 0,
  PAUSED: 1,
  DRAFT: 2,
  ACTIVE: 3,
  CLOSED: 9,
}

export default function CampaignsPage() {
  const { t, language } = useLanguage()
  const [items, setItems] = useState<Campaign[]>([])
  const [summary, setSummary] = useState<Record<string, CampaignSummary> | null>(null)
  const [engagement, setEngagement] = useState<Record<string, CampaignEngagement>>({})
  const [engagementState, setEngagementState] = useState<'ok' | 'unavailable'>('unavailable')
  const [planning, setPlanning] = useState<CampaignPlan[]>([])
  const [planningState, setPlanningState] = useState<'loading' | 'ok' | 'unavailable'>('loading')
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [stateFilter, setStateFilter] = useState<string | null>(null)

  useEffect(() => {
    fetch('/api/campaigns')
      .then(r => r.json())
      .then((d: {
        items: Campaign[]
        state: string
        summary?: CampaignSummary[] | null
        engagement?: { state: 'ok' | 'unavailable'; items: CampaignEngagement[] }
      }) => {
        if (d.state !== 'ok') {
          setUnavailable(d.state === 'unauthorized' ? 'unauthorized' : d.state === 'not_deployed' ? 'not_deployed' : 'unreachable')
          return
        }
        setItems(d.items ?? [])
        setSummary(
          Array.isArray(d.summary) ? Object.fromEntries(d.summary.map(x => [x.campaignId, x])) : null,
        )
        setEngagementState(d.engagement?.state ?? 'unavailable')
        setEngagement(
          Array.isArray(d.engagement?.items)
            ? Object.fromEntries(d.engagement.items.map(x => [x.campaignId, x]))
            : {},
        )
      })
      .catch(() => setUnavailable('unreachable'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    fetch('/api/campaigns/planning')
      .then(r => r.json())
      .then((d: { items?: CampaignPlan[]; state?: string }) => {
        if (d.state === 'ok') {
          setPlanning(d.items ?? [])
          setPlanningState('ok')
        } else {
          setPlanningState('unavailable')
        }
      })
      .catch(() => setPlanningState('unavailable'))
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

  /** Portfolio-level delivery evidence. These are sent/suppressed/failed decisions from the
   * service, not customer reach or conversion; the labels must retain that distinction. */
  const deliveryHealth = useMemo(() => {
    if (!summary) return null
    const values = Object.values(summary)
    const sent = values.reduce((total, row) => total + row.sent, 0)
    const suppressed = values.reduce((total, row) => total + row.suppressed, 0)
    const failed = values.reduce((total, row) => total + row.failed, 0)
    const converted = values.reduce(
      (total, row) => total + (row.outcomes?.find(outcome => outcome.outcome === 'CONVERTED')?.count ?? 0),
      0,
    )
    return { sent, suppressed, failed, converted, affected: values.filter(row => row.failed > 0).length }
  }, [summary])

  const decisionQueue = useMemo(
    () => [...items]
      .filter(c => c.state !== 'CLOSED')
      .sort((a, b) => {
        const priority = (DECISION_PRIORITY[a.state] ?? 8) - (DECISION_PRIORITY[b.state] ?? 8)
        return priority !== 0 ? priority : Date.parse(a.createdAt) - Date.parse(b.createdAt)
      })
      .slice(0, 3),
    [items],
  )

  const deliveryPulse = useMemo(() => {
    if (!summary) return null
    return Object.values(summary).reduce(
      (total, item) => ({
        enrolled: total.enrolled + item.enrolled,
        sent: total.sent + item.sent,
        suppressed: total.suppressed + item.suppressed,
      }),
      { enrolled: 0, sent: 0, suppressed: 0 },
    )
  }, [summary])

  const engagementPulse = useMemo(() => {
    if (engagementState !== 'ok') return null
    const values = Object.values(engagement)
    if (values.length === 0) return null
    return values.reduce(
      (total, item) => ({
        impressions: total.impressions + item.impressions,
        clicks: total.clicks + item.clicks,
        dismissals: total.dismissals + item.dismissals,
      }),
      { impressions: 0, clicks: 0, dismissals: 0 },
    )
  }, [engagement, engagementState])

  const authoritativeConversions = useMemo(() => {
    if (!summary) return null
    const observed = Object.values(summary)
      .flatMap(item => item.outcomes ?? [])
      .filter(item => item.outcome === 'CONVERTED')
    return observed.length > 0 ? observed.reduce((total, item) => total + item.count, 0) : null
  }, [summary])

  /** This is a campaign-operation rate, not customer delivery or conversion. Campaign-service
   * can establish that it handed work to notification-service or protected someone with a
   * suppression rule; only the channel and a campaign-attributed outcome could establish more. */
  const handoffRate = useMemo(() => {
    if (!deliveryPulse) return null
    const decided = deliveryPulse.sent + deliveryPulse.suppressed
    return decided > 0 ? Math.round((deliveryPulse.sent / decided) * 100) : null
  }, [deliveryPulse])

  const needle = search.trim().toLowerCase()
  const filtered = items.filter(
    c =>
      (!stateFilter || c.state === stateFilter) &&
      (!needle || c.name.toLowerCase().includes(needle) || (c.goal ?? '').toLowerCase().includes(needle)),
  )

  const nextAction = (campaign: Campaign) => {
    if (campaign.state === 'PENDING_APPROVAL') return t('Čeká na druhé oči', 'Needs a second pair of eyes')
    if (campaign.state === 'PAUSED') return t('Rozhodněte o pokračování', 'Decide whether to resume')
    if (campaign.state === 'DRAFT') return t('Dokončete zadání', 'Finish the brief')
    return t('Sledujte živou cestu', 'Follow the live journey')
  }

  return <AuthGuard permission="campaign:view">
    <div className="space-y-6">
      <PageHeader
        title={t('Kampaně', 'Campaigns')}
        subtitle={t(
          'Co běží, co čeká na schválení a co je rozpracované. Doručení a odezvu najdete v detailu kampaně.',
          'What is running, what waits for approval, and what is unfinished. Delivery and response live in the campaign detail.',
        )}
        icon={<Megaphone className="h-6 w-6" />}
        actions={
          <Can permission="campaign:create">
            <Link href="/campaigns/new" className="btn btn-primary" style={{ fontSize: 12 }}>
              {t('Nová kampaň', 'New campaign')}
            </Link>
          </Can>
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

          <section className="campaign-control-room" aria-label={t('Řídicí místnost kampaní', 'Campaign control room')} data-testid="campaign-control-room">
            <div className="campaign-control-hero">
              <div className="campaign-control-orbit campaign-control-orbit-one" aria-hidden="true" />
              <div className="campaign-control-orbit campaign-control-orbit-two" aria-hidden="true" />
              <div className="campaign-control-kicker"><Sparkles size={13} /> {t('Campaign OS', 'Campaign OS')}</div>
              <div className="campaign-control-hero-copy">
                <div>
                  <h2>{t('Od nápadu k další akci. V jednom tahu.', 'From idea to next action. In one flow.')}</h2>
                  <p>{t('Pracovní plocha pro rozhodnutí, ne inventář kampaní.', 'A decision workspace, not a campaign inventory.')}</p>
                </div>
                <Can permission="campaign:create">
                  <Link href="/campaigns/new" className="campaign-control-create">
                    <Sparkles size={14} /> {t('Vytvořit cestu', 'Create journey')}
                  </Link>
                </Can>
              </div>
              <div className="campaign-control-flow" aria-label={t('Tok práce kampaně', 'Campaign work flow')}>
                <div className="campaign-flow-node" data-state="brief">
                  <PenLine size={15} /><span>{t('Zadání', 'Brief')}</span><strong>{counts('DRAFT')}</strong>
                </div>
                <ArrowRight className="campaign-flow-arrow" size={15} aria-hidden="true" />
                <div className="campaign-flow-node" data-state="review">
                  <ShieldCheck size={15} /><span>{t('Druhé oči', 'Review')}</span><strong>{counts('PENDING_APPROVAL')}</strong>
                </div>
                <ArrowRight className="campaign-flow-arrow" size={15} aria-hidden="true" />
                <div className="campaign-flow-node" data-state="live">
                  <Activity size={15} /><span>{t('Živá cesta', 'Live journey')}</span><strong>{counts('ACTIVE')}</strong>
                </div>
                <ArrowRight className="campaign-flow-arrow" size={15} aria-hidden="true" />
                <div className="campaign-flow-node" data-state="learn">
                  <Eye size={15} /><span>{t('Důkaz', 'Evidence')}</span>
                  <strong>{engagementState === 'unavailable' ? t('neznámé', 'unknown') : engagementPulse ? t('živě', 'live') : t('čeká', 'waiting')}</strong>
                </div>
              </div>
            </div>

            <div className="campaign-control-radar">
              <div className="campaign-radar-heading">
                <div>
                  <p>{t('Signály operátora', 'Operator signals')}</p>
                  <h3>{t('Co vyžaduje pozornost', 'What needs attention')}</h3>
                </div>
                <Gauge size={17} aria-hidden="true" />
              </div>
              <div className="campaign-radar-cards">
                <div className="campaign-radar-card" data-tone={counts('PENDING_APPROVAL') > 0 ? 'attention' : 'clear'}>
                  <div><Clock3 size={15} /><span>{t('Rozhodnutí čekají', 'Decisions waiting')}</span></div>
                  <strong>{counts('PENDING_APPROVAL')}</strong>
                  <p>{counts('PENDING_APPROVAL') > 0
                    ? t('Kampaň potřebuje nezávislé schválení.', 'A campaign needs independent approval.')
                    : t('Nic nečeká na schválení.', 'Nothing is waiting for approval.')}
                  </p>
                </div>
                <div className="campaign-radar-card" data-tone={counts('PAUSED') > 0 ? 'attention' : 'clear'}>
                  <div><PauseCircle size={15} /><span>{t('Zastavené cesty', 'Paused journeys')}</span></div>
                  <strong>{counts('PAUSED')}</strong>
                  <p>{counts('PAUSED') > 0
                    ? t('Rozhodněte, zda pokračovat nebo uzavřít.', 'Decide whether to resume or close.')
                    : t('Žádná cesta není pozastavená.', 'No journey is paused.')}
                  </p>
                </div>
              </div>
              <div className="campaign-evidence-strip" data-testid="campaign-evidence-strip">
                <GitBranch size={15} aria-hidden="true" />
                <div>
                  <strong>{handoffRate === null ? t('Čeká na provozní data', 'Waiting for operating data') : `${handoffRate} % ${t('předáno do kanálu', 'handed to channel')}`}</strong>
                  <span>{t('Je to pouze handoff do kanálu. Pozorované reakce aplikace jsou odděleně níže.', 'This is channel handoff only. Observed app response is reported separately below.')}</span>
                </div>
              </div>
            </div>
          </section>

          <CampaignPlanningBoard items={planning} state={planningState} />

          <section className="campaign-decision-desk" aria-label={t('Dnešní priority kampaní', 'Today’s campaign priorities')} data-testid="campaign-decision-desk">
            <div className="campaign-decision-queue">
              <div className="campaign-desk-heading">
                <div>
                  <p>{t('Dnešní fokus', 'Today’s focus')}</p>
                  <h2>{t('Co posune kampaně dál', 'What moves campaigns forward')}</h2>
                </div>
                <span>{decisionQueue.length}</span>
              </div>
              {decisionQueue.length > 0 ? (
                <div className="campaign-decision-items">
                  {decisionQueue.map((campaign, index) => (
                    <Link key={campaign.id} href={`/campaigns/${campaign.id}`} className="campaign-decision-item" data-decision-campaign={campaign.id}>
                      <span className="campaign-decision-rank">0{index + 1}</span>
                      <span className="campaign-decision-copy">
                        <strong>{campaign.name}</strong>
                        <small>{nextAction(campaign)}</small>
                      </span>
                      <StatusBadge status={campaign.state} label={label(campaign.state)} />
                    </Link>
                  ))}
                </div>
              ) : (
                <p className="campaign-decision-empty">{t('Nic nečeká. Můžete připravit další nápad.', 'Nothing is waiting. You can prepare the next idea.')}</p>
              )}
            </div>

            <div className="campaign-delivery-pulse" data-testid="campaign-delivery-pulse">
              <div className="campaign-desk-heading">
                <div>
                  <p>{t('Doručení', 'Delivery')}</p>
                  <h2>{t('Pulse portfolia', 'Portfolio pulse')}</h2>
                </div>
                <span className={deliveryPulse ? 'campaign-pulse-live' : 'campaign-pulse-muted'}>{deliveryPulse ? t('Živě', 'Live') : t('Čeká na data', 'Waiting for data')}</span>
              </div>
              {deliveryPulse ? (
                <div className="campaign-pulse-numbers">
                  <div><strong>{deliveryPulse.enrolled.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong><span>{t('zařazeno', 'enrolled')}</span></div>
                  <div><strong>{deliveryPulse.sent.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong><span>{t('odesláno', 'sent')}</span></div>
                  <div><strong>{deliveryPulse.suppressed.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong><span>{t('potlačeno', 'suppressed')}</span></div>
                </div>
              ) : (
                <p className="campaign-pulse-empty">{t('Nasazená služba zatím neposílá souhrn doručení. Nuly by byly zavádějící.', 'The deployed service does not yet return a delivery aggregate. Zeros would mislead.')}</p>
              )}
              <p className="campaign-pulse-footnote">{t('Tento panel končí handoffem do kanálu; níže jsou samostatně pozorované reakce aplikace.', 'This panel stops at channel handoff; observed app response is separate below.')}</p>
              <div
                data-testid="campaign-engagement-pulse"
                style={{ marginTop: 16, paddingTop: 14, borderTop: '1px solid var(--border)' }}
              >
                <div className="campaign-desk-heading">
                  <div>
                    <p>{t('Reakce v aplikaci', 'App response')}</p>
                    <h2>{t('Pozorovaný engagement', 'Observed engagement')}</h2>
                  </div>
                  <span className={engagementPulse ? 'campaign-pulse-live' : 'campaign-pulse-muted'}>
                    {engagementState === 'unavailable'
                      ? t('Neznámé', 'Unknown')
                      : engagementPulse
                        ? t('Živě', 'Live')
                        : t('Zatím nepozorováno', 'Not yet observed')}
                  </span>
                </div>
                {engagementPulse ? (
                  <div className="campaign-pulse-numbers" style={{ gridTemplateColumns: 'repeat(2, 1fr)' }}>
                    <div><strong>{engagementPulse.impressions.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong><span>{t('zobrazení', 'impressions')}</span></div>
                    <div><strong>{engagementPulse.clicks.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong><span>{t('kliknutí', 'clicks')}</span></div>
                    <div><strong>{engagementPulse.dismissals.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong><span>{t('odmítnutí', 'dismissals')}</span></div>
                    <div><strong>{authoritativeConversions === null ? '—' : authoritativeConversions.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong><span>{t('produktové konverze', 'product-event conversions')}</span></div>
                  </div>
                ) : (
                  <p className="campaign-pulse-empty">
                    {engagementState === 'unavailable'
                      ? t('Analytická projekce právě není čitelná. Stav je neznámý, nikoli nula.', 'The analytics projection is not readable right now. The state is unknown, not zero.')
                      : t('Pro kampaně zatím nebyla pozorována žádná serverově přiřazená reakce.', 'No server-attributed app response has been observed for these campaigns yet.')}
                  </p>
                )}
                <p className="campaign-pulse-footnote">{t('Zobrazení, kliknutí a odmítnutí jsou akce aplikace. Produktová konverze přichází z autoritativní account/card události, nikdy z telefonu ani kliku.', 'Impressions, clicks and dismissals are app actions. Product conversion comes from an authoritative account/card event, never from the phone or a click.')}</p>
              </div>
            </div>
          </section>

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

          {deliveryHealth && (
            <section className="rounded-xl border bg-gradient-to-r from-slate-950 via-slate-900 to-indigo-950 p-5 text-white shadow-sm" data-testid="campaign-delivery-health">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <p className="text-xs font-bold uppercase tracking-widest text-indigo-200">{t('Zdraví doručování', 'Delivery health')}</p>
                  <h2 className="mt-1 text-lg font-semibold">{t('Co se skutečně rozhodlo napříč kampaněmi', 'What was actually decided across campaigns')}</h2>
                </div>
                {deliveryHealth.failed > 0 && <span className="rounded-full bg-rose-400/15 px-3 py-1 text-xs font-semibold text-rose-200">{deliveryHealth.affected} {t('kampaní s chybou', 'campaigns with failures')}</span>}
              </div>
              <div className="mt-4 grid gap-3 sm:grid-cols-4">
                <div className="rounded-lg bg-white/10 p-3"><p className="text-xs text-slate-300">{t('Odesláno', 'Sent')}</p><strong className="text-2xl">{deliveryHealth.sent}</strong></div>
                <div className="rounded-lg bg-amber-300/10 p-3"><p className="text-xs text-amber-100">{t('Potlačeno politikou', 'Suppressed by policy')}</p><strong className="text-2xl">{deliveryHealth.suppressed}</strong></div>
                <div className="rounded-lg bg-rose-400/10 p-3"><p className="text-xs text-rose-100">{t('Selhalo', 'Failed')}</p><strong className="text-2xl">{deliveryHealth.failed}</strong></div>
                <div className="rounded-lg bg-emerald-300/10 p-3"><p className="text-xs text-emerald-100">{t('Potvrzené konverze', 'Confirmed conversions')}</p><strong className="text-2xl">{deliveryHealth.converted}</strong></div>
              </div>
              <p className="mt-3 text-xs text-slate-300">{t('Potlačení chrání souhlas, klidové hodiny a frekvenční limit; není to nedoručený kontakt. Konverze jsou jen skutečné bankovní outcome události, nikdy kliky ani odhad.', 'Suppression protects consent, quiet hours and frequency caps; it is not an undelivered contact. Conversions are real banking outcome events only, never clicks or an estimate.')}</p>
            </section>
          )}

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
              <button type="button" className="btn btn-secondary" style={{ fontSize: 11 }} onClick={() => setStateFilter(null)} data-testid="clear-state">
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
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t('Název', 'Name')}</th>
                    <th>{t('Stav', 'State')}</th>
                    <th>{t('Segment', 'Segment')}</th>
                    {summary && <th>{t('Zařazeno', 'Enrolled')}</th>}
                    {summary && <th>{t('Doručeno', 'Sent')}</th>}
                    {summary && <th>{t('Potlačeno', 'Suppressed')}</th>}
                    <th>{t('Reakce v aplikaci', 'App response')}</th>
                    <th>{t('Vytvořil', 'Created by')}</th>
                    <th>{t('Schválil', 'Approved by')}</th>
                    <th>{t('Vytvořeno', 'Created')}</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map(c => (
                    <tr key={c.id}>
                      <td>
                        <Link href={`/campaigns/${c.id}`} className="font-medium hover:underline">
                          {c.name}
                        </Link>
                        <div className="text-xs text-muted-foreground">{c.goal}</div>
                      </td>
                      {/* Human label for the reader, raw state kept as the title so the screen and
                          the state machine can never end up describing different things. */}
                      <td>
                        <span title={c.state}><StatusBadge status={c.state} label={label(c.state)} /></span>
                      </td>
                      <td className="font-mono text-xs">
                        {c.segmentRef?.name}@{c.segmentRef?.version}
                      </td>
                      {summary && <td className="text-xs">{summary[c.id]?.enrolled ?? 0}</td>}
                      {summary && <td className="text-xs">{summary[c.id]?.sent ?? 0}</td>}
                      {/* Suppressed is tinted only when it happened: "0 suppressed" is the normal
                          case and colouring it would make every row look like it needs attention.
                          A non-zero here is the answer to "why was reach low", so it must not read
                          as ordinary. */}
                      {summary && (
                        <td
                          className="text-xs"
                          style={(summary[c.id]?.suppressed ?? 0) > 0 ? { color: 'var(--warning)', fontWeight: 600 } : undefined}
                        >
                          {summary[c.id]?.suppressed ?? 0}
                        </td>
                      )}
                      <td className="text-xs" data-testid={`campaign-engagement-${c.id}`}>
                        {engagementState === 'unavailable'
                          ? t('Neznámé', 'Unknown')
                          : engagement[c.id]
                            ? t(
                                `${engagement[c.id].impressions} zobrazení · ${engagement[c.id].clicks} kliknutí`,
                                `${engagement[c.id].impressions} impressions · ${engagement[c.id].clicks} clicks`,
                              )
                            : t('Zatím nepozorováno', 'Not yet observed')}
                      </td>
                      <td className="text-xs">{c.createdBy}</td>
                      {/* The checker, shown next to the maker on purpose: the maker/checker pair is
                          the audit-relevant fact about an ACTIVE campaign, not a detail. */}
                      <td className="text-xs">{c.approvedBy ?? '—'}</td>
                      <td className="text-xs whitespace-nowrap">{fmtDate(c.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  </AuthGuard>
}
