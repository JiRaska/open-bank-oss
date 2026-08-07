// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, Megaphone } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge, type Tone } from '@/components/ui'
import { JourneyCanvas, type StepFunnel } from '@/components/campaigns/JourneyCanvas'
import { SectionBoundary } from '@/components/feedback/SectionBoundary'
import { PeopleSummary } from '@/components/campaigns/PeopleSummary'

interface Campaign {
  id: string
  name: string
  goal: string
  segmentRef: { name: string; version: number }
  state: string
  createdBy: string
  approvedBy: string | null
  steps: { order: number; template: string; delaySeconds: number }[]
  /** ADR-0245: a ConversionCatalog key, or absent when the campaign measures no conversion. */
  conversionRule?: string | null
}

interface Enrolment {
  id: string
  partyId: string
  state: string
  currentStep: number
}

interface Send {
  id: string
  partyId: string
  stepOrder: number
  outcome: string
  occurredAt: string
  /**
   * ADR-0239 D3, added to the campaign API in 1.8.0. Optional here because a response from an
   * older deployment simply does not carry it — the column then reads "—" rather than claiming
   * PENDING, which would be a statement this UI cannot support.
   */
  deliveryStatus?: string
  deliveryReason?: string | null
}

interface SendPage {
  items: Send[]
  total: number
  page: number
  size: number
}

type Detail = {
  campaign: Campaign | null
  enrolments: Enrolment[]
  sends: SendPage
  partyNames: Record<string, string>
  sendSummary: Record<string, number>
  journey: StepFunnel[]
  sources: Record<string, string>
}

/** Outcomes the send-log filter offers, in the order an operator scans them. */
const OUTCOMES = ['SENT', 'CONVERTED', 'DRY_RUN', 'FAILED', 'SUPPRESSED_CONSENT', 'SUPPRESSED_CAP', 'SUPPRESSED_QUIET_HOURS'] as const

/**
 * Outcome colouring, passed explicitly rather than added to the shared tone map (see `tone.ts`:
 * a domain that disagrees passes `tone`, it does not edit the table).
 *
 * A suppression is NEUTRAL, not a warning: consent withdrawn, a frequency cap or quiet hours are
 * the system doing exactly what ADR-0200 D6 asks of it. Colouring them red would train operators to
 * treat correct behaviour as an incident. Only FAILED — the send that was supposed to happen and
 * did not — is a problem.
 */
function outcomeTone(outcome: string): Tone {
  if (outcome === 'SENT') return 'success'
  if (outcome === 'FAILED') return 'danger'
  return 'neutral'
}

/**
 * Delivery colouring (ADR-0239 D3). `PENDING` is NEUTRAL, never a warning: no outcome has arrived
 * yet, which is the normal state for a send made moments ago and the permanent state for a send the
 * contact gate denied — nothing was handed off, so nothing can ever report back. Colouring it as a
 * problem would make the common case look like an incident, which is the mistake `outcomeTone`
 * above already avoids for suppressions.
 */
function deliveryTone(status: string): Tone {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'neutral'
}

export default function CampaignDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { t, language } = useLanguage()
  // Params are unwrapped in an effect rather than with React `use()`. `use()` suspends until the
  // promise settles, which forces every caller — including tests — to provide a Suspense boundary
  // and, in jsdom, leaves the tree on the fallback indefinitely. An effect keeps the page mountable
  // anywhere and costs one render.
  const [id, setId] = useState<string | null>(null)
  useEffect(() => {
    params.then(p => setId(p.id))
  }, [params])
  const [detail, setDetail] = useState<Detail | null>(null)
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)
  // Bumped after a lifecycle action so the screen re-reads the campaign rather than guessing the
  // new state locally — the service owns the state machine and may refuse a transition.
  const [reloadToken, setReloadToken] = useState(0)

  useEffect(() => {
    if (!id) return
    fetch(`/api/campaigns/${id}`)
      .then(r => r.json())
      .then((d: Detail) => {
        if (d.sources?.campaign !== 'ok') {
          setUnavailable(d.sources?.campaign === 'unauthorized' ? 'unauthorized' : 'unreachable')
          return
        }
        setDetail(d)
      })
      .catch(() => setUnavailable('unreachable'))
      .finally(() => setLoading(false))
  }, [id, reloadToken])

  const c = detail?.campaign

  // The bundle carries page 0; every later page and every filter change comes from the dedicated
  // sends route, so turning a page does not re-read the campaign and its enrolments.
  const [sendOverride, setSendOverride] = useState<SendPage | null>(null)
  const [sendState, setSendState] = useState<string>('ok')
  const [outcomeFilter, setOutcomeFilter] = useState<string>('')
  const [sendsLoading, setSendsLoading] = useState(false)

  const sendPage = sendOverride ?? detail?.sends ?? { items: [], total: 0, page: 0, size: 50 }
  const sends = sendPage.items

  // Lifecycle actions (ADR-0221 D2). Which ones are offered follows the campaign's own state
  // machine; whether the caller may run them is decided by OPA, and the domain re-asserts
  // maker != checker on activate. The UI renders capability, the policy decides it.
  const [actionError, setActionError] = useState<string | null>(null)
  const [acting, setActing] = useState(false)

  const runAction = (action: string) => {
    setActing(true)
    setActionError(null)
    fetch(`/api/campaigns/${encodeURIComponent(id ?? '')}/actions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ action }),
    })
      .then(r => r.json())
      .then((d: { state: string; error?: string }) => {
        if (d.state === 'ok') {
          // Drop the paged override too: after a transition the first page is the right thing to
          // show, and keeping page 7 of a log that just changed is a stale view of a new state.
          setSendOverride(null)
          setReloadToken(n => n + 1)
          return
        }
        // The service answers a refused transition with the invariant that blocked it — including
        // "the approver must differ from the creator". That sentence IS the four-eyes gate becoming
        // visible; replacing it with "action failed" would make a working control look like a bug.
        setActionError(
          d.error ??
            (d.state === 'forbidden'
              ? t('Nemáte oprávnění k této akci.', 'You are not permitted to do that.')
              : t('Campaign-service neodpovídá.', 'Campaign-service is not responding.')),
        )
      })
      .catch(() => setActionError(t('Campaign-service neodpovídá.', 'Campaign-service is not responding.')))
      .finally(() => setActing(false))
  }

  const actionsFor = (state?: string): string[] => {
    switch (state) {
      case 'DRAFT':
        return ['submit']
      case 'PENDING_APPROVAL':
        return ['activate']
      case 'ACTIVE':
        return ['enrol', 'pause', 'close']
      case 'PAUSED':
        return ['resume', 'close']
      default:
        return []
    }
  }

  const actionLabel = (a: string): string =>
    ({
      submit: t('Odeslat ke schválení', 'Submit for approval'),
      activate: t('Schválit a spustit', 'Approve and activate'),
      pause: t('Pozastavit', 'Pause'),
      resume: t('Obnovit', 'Resume'),
      close: t('Uzavřít', 'Close'),
      enrol: t('Zařadit publikum', 'Enrol audience'),
    })[a] ?? a

  const loadSends = (page: number, outcome: string) => {
    setSendsLoading(true)
    const qs = new URLSearchParams({ page: String(page), size: String(sendPage.size || 50) })
    if (outcome) qs.set('outcome', outcome)
    fetch(`/api/campaigns/${encodeURIComponent(id ?? '')}/sends?${qs.toString()}`)
      .then(r => r.json())
      .then((d: SendPage & { state: string }) => {
        setSendState(d.state)
        // A failed page must not replace a good one with an empty table — that renders as
        // "nothing was suppressed", the misreading this screen exists to prevent.
        if (d.state === 'ok') setSendOverride({ items: d.items, total: d.total, page: d.page, size: d.size })
      })
      .catch(() => setSendState('unreachable'))
      .finally(() => setSendsLoading(false))
  }

  const applyFilter = (outcome: string) => {
    setOutcomeFilter(outcome)
    loadSends(0, outcome)
  }
  const summary = detail?.sendSummary ?? {}

  // From the server-side summary, never from the loaded page: a headline derived from the rows on
  // screen understates every campaign larger than one page.
  const suppressed = Object.entries(summary)
    .filter(([outcome]) => outcome.startsWith('SUPPRESSED'))
    .reduce((n, [, count]) => n + count, 0)

  const fmtDateTime = (iso: string | null | undefined) =>
    iso
      ? new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', {
          dateStyle: 'medium', timeStyle: 'short',
        }).format(new Date(iso))
      : '—'

  /**
   * Human phrasing for a send outcome. The raw enum is what the API returns and what an engineer
   * debugs with; a marketer needs to know WHY nothing was sent, in words. The raw value stays in
   * the badge title so the two are never disconnected.
   */
  const conversionLabel = (r: string): string => {
    switch (r) {
      case 'ACCOUNT_OPENED': return t('Založení účtu', 'Account opened')
      case 'CARD_ISSUED': return t('Vydání karty', 'Card issued')
      default: return r
    }
  }

  const outcomeLabel = (o: string): string => {
    switch (o) {
      case 'SENT': return t('Odesláno', 'Sent')
      // Phrased as what it is and no more (ADR-0245 D3): attribution is last-touch inside a window,
      // so this says the party converted WHILE ENROLLED — never that the campaign caused it.
      case 'CONVERTED': return t('Splnil cíl (v době kampaně)', 'Converted (while enrolled)')
      // Said as plainly as possible: a rehearsal is not a delivery, and the two must never read
      // as the same number on a screen someone reports upwards.
      case 'DRY_RUN': return t('Nazkoušeno (neodesláno)', 'Rehearsed (not sent)')
      case 'SUPPRESSED_CONSENT': return t('Odvolaný souhlas', 'Consent withdrawn')
      case 'SUPPRESSED_CAP': return t('Limit četnosti', 'Frequency cap')
      case 'SUPPRESSED_QUIET_HOURS': return t('Tiché hodiny', 'Quiet hours')
      case 'FAILED': return t('Selhalo', 'Failed')
      default: return o
    }
  }

  /**
   * Human phrasing for the delivery state. Deliberately worded so it cannot be read as a synonym
   * of the outcome beside it: "Accepted" (the handoff) and "Delivered" (the message) are the two
   * facts this column exists to keep apart (ADR-0239 D3, issue #3663).
   */
  const deliveryLabel = (d: string): string => {
    switch (d) {
      case 'PENDING': return t('Čeká na potvrzení', 'Awaiting confirmation')
      case 'CONFIRMED': return t('Doručeno', 'Delivered')
      case 'SUPPRESSED': return t('Potlačeno', 'Suppressed')
      case 'FAILED': return t('Nedoručeno', 'Not delivered')
      default: return d
    }
  }

  const stateLabel = (s: string): string => {
    switch (s) {
      case 'ACTIVE': return t('Aktivní', 'Active')
      case 'COMPLETED': return t('Dokončeno', 'Completed')
      case 'TERMINATED_CONSENT_REVOKED': return t('Ukončeno — odvolaný souhlas', 'Ended — consent withdrawn')
      case 'TERMINATED_SUPPRESSED': return t('Ukončeno — potlačeno', 'Ended — suppressed')
      case 'STOPPED_MAX_SENDS': return t('Zastaveno — limit odeslání', 'Stopped — send cap reached')
      default: return s
    }
  }

  /** Short id for scanning; the full value stays in `title` and is what you copy. */
  const shortId = (id: string) => id.slice(0, 8)

  // Suppressions grouped by reason: "2 suppressed" tells an operator something is off,
  // "2 × quiet hours" tells them whether to act.
  const byReason = Object.fromEntries(
    Object.entries(summary).filter(([outcome, count]) => outcome.startsWith('SUPPRESSED') && count > 0),
  )

  return (
    <div className="space-y-6">
      <Link href="/campaigns" className="inline-flex items-center gap-1 text-sm hover:underline">
        <ArrowLeft className="h-4 w-4" /> {t('Kampaně', 'Campaigns')}
      </Link>

      <PageHeader
        title={c?.name ?? t('Kampaň', 'Campaign')}
        subtitle={c?.goal}
        icon={<Megaphone className="h-6 w-6" />}
      />

      {/* Only the transitions this state actually allows are offered. Rendering every button and
          letting the service reject four of them teaches operators that red messages are normal,
          which is how a real refusal stops being read. */}
      {!loading && !unavailable && c && actionsFor(c.state).length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          {actionsFor(c.state).map(a => (
            <button
              key={a}
              onClick={() => runAction(a)}
              disabled={acting}
              className="rounded-md border px-3 py-1.5 text-sm disabled:opacity-40"
            >
              {actionLabel(a)}
            </button>
          ))}
          {c.state === 'PENDING_APPROVAL' && (
            // Said out loud, because the refusal is otherwise indistinguishable from a bug: the
            // approver is taken from the token, so the person who created it cannot approve it.
            <span className="text-xs text-muted-foreground">
              {t(
                `Schválit musí někdo jiný než ${c.createdBy}.`,
                `Someone other than ${c.createdBy} must approve this.`,
              )}
            </span>
          )}
        </div>
      )}

      {actionError && <p className="text-sm text-red-600">{actionError}</p>}

      {loading && <p className="text-sm text-muted-foreground">{t('Načítám…', 'Loading…')}</p>}
      {!loading && unavailable && (
        <DataUnavailable kind={unavailable} service="Campaign-service" feature={t('Detail kampaně', 'Campaign detail')} />
      )}

      {!loading && !unavailable && c && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label={t('Stav', 'State')} value={c.state} />
            <StatCard label={t('Segment', 'Segment')} value={`${c.segmentRef?.name}@${c.segmentRef?.version}`} />
            <StatCard label={t('Zařazeno', 'Enrolled')} value={String(detail?.enrolments.length ?? 0)} />
            {/* Surfaced as a headline number on purpose: "how many were deliberately not
                contacted" is the question the send log exists to answer (#2895). */}
            <StatCard label={t('Potlačených odeslání', 'Suppressed sends')} value={String(suppressed)} />
          </div>

          {/* Absent rule and zero conversions are different facts and must never render the same:
              a campaign measuring nothing has no number, and saying so is the honest empty state
              (ADR-0245 D1). */}
          <div className="grid gap-4 sm:grid-cols-2">
            {c.conversionRule ? (
              <StatCard
                label={t('Splnili cíl', 'Converted')}
                value={String(summary.CONVERTED ?? 0)}
              />
            ) : (
              <div className="rounded-lg border p-3 text-xs text-muted-foreground" data-no-conversion-rule>
                {t(
                  'Tahle kampaň konverzi neměří — nemá nastavené pravidlo. To není totéž jako nula splněných cílů.',
                  'This campaign measures no conversion — it has no rule set. That is not the same as nobody converting.',
                )}
              </div>
            )}
            {c.conversionRule && (
              <div className="rounded-lg border p-3 text-xs text-muted-foreground">
                {t('Měří se: ', 'Measuring: ')}
                <span className="font-medium text-foreground">{conversionLabel(c.conversionRule)}</span>
                {t(
                  ' — počítá se, kdo cíl splnil po prvním odeslání a v atribučním okně pravidla.',
                  ' — counted when it happens after the first send and inside the rule\'s attribution window.',
                )}
              </div>
            )}
          </div>

          {Object.keys(byReason).length > 0 && (
            <p className="text-xs text-muted-foreground">
              {t('Potlačeno: ', 'Suppressed: ')}
              {Object.entries(byReason)
                .map(([reason, n]) => `${n}× ${outcomeLabel(reason)}`)
                .join(', ')}
            </p>
          )}

          <section className="space-y-2">
            <h2 className="text-sm font-semibold">{t('Průchod kampaní', 'Journey')}</h2>
            {/* Replaces a three-column table of order / template id / delay-in-minutes. That table
                showed the campaign's DEFINITION; a marketer needs its RESULT — who it reached and
                where the rest went — and had to correlate it against the send log by eye to get
                there. The definition is still visible, just drawn as the flow it describes. */}
            {detail?.sources?.journey !== 'ok' ? (
              <DataUnavailable
                kind={detail?.sources?.journey === 'unauthorized' ? 'unauthorized' : 'unreachable'}
                service="Campaign-service"
                feature={t('Průchod kampaní', 'Journey')}
                dense
              />
            ) : (
              <SectionBoundary name="Journey">
                <JourneyCanvas
                  steps={c.steps ?? []}
                  funnel={detail?.journey ?? []}
                  audienceSize={(detail?.enrolments?.length ?? 0) > 0 ? (detail?.enrolments?.length ?? 0) : null}
                />
              </SectionBoundary>
            )}
          </section>

          <section className="space-y-2">
            <h2 className="text-sm font-semibold">{t('Čtyři oči', 'Four-eyes')}</h2>
            <p className="text-sm text-muted-foreground">
              {t('Vytvořil', 'Created by')} <span className="font-mono">{c.createdBy}</span>{t(', schválil ', ', approved by ')}
              <span className="font-mono">{c.approvedBy ?? t('— zatím neschváleno', '— not yet approved')}</span>
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-sm font-semibold">{t('Zařazení', 'Enrolments')}</h2>
            {detail?.sources?.enrolments !== 'ok' ? (
              <DataUnavailable
                kind={detail?.sources?.enrolments === 'unauthorized' ? 'unauthorized' : detail?.sources?.enrolments === 'not_deployed' ? 'not_deployed' : 'unreachable'}
                service="Campaign-service"
                feature={t('Zařazení', 'Enrolments')}
                dense
              />
            ) : (
              <SectionBoundary name="People">
                <PeopleSummary enrolments={detail.enrolments} partyNames={detail.partyNames ?? {}} />
              </SectionBoundary>
            )}

            {sends.length > 0 && (
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">
                  {/* The range and the total together: "1–50" alone cannot distinguish the whole
                      log from the first slice of a much larger one. */}
                  {t('Zobrazeno', 'Showing')} {sendPage.page * sendPage.size + 1}–
                  {sendPage.page * sendPage.size + sends.length} {t('z', 'of')}{' '}
                  {sendPage.total.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}
                </span>
                <span className="flex gap-2">
                  <button
                    onClick={() => loadSends(sendPage.page - 1, outcomeFilter)}
                    disabled={sendPage.page === 0 || sendsLoading}
                    className="rounded-md border px-2 py-1 text-xs disabled:opacity-40"
                  >
                    {t('Předchozí', 'Previous')}
                  </button>
                  <button
                    onClick={() => loadSends(sendPage.page + 1, outcomeFilter)}
                    disabled={(sendPage.page + 1) * sendPage.size >= sendPage.total || sendsLoading}
                    className="rounded-md border px-2 py-1 text-xs disabled:opacity-40"
                  >
                    {t('Další', 'Next')}
                  </button>
                </span>
              </div>
            )}
          </section>

          <section className="space-y-2">
            <h2 className="text-sm font-semibold">{t('Log odeslání', 'Send log')}</h2>
            <p className="text-xs text-muted-foreground">
              {t(
                'Včetně potlačených pokusů. Předání je rozhodnutí kampaně; Doručení je to, co hlásí notification-service — běžně se liší a jen druhé se týká zákazníka.',
                'Includes suppressed attempts. Handoff is what the campaign decided; Delivery is what notification-service reported back — they routinely differ, and only the second one is about the customer.',
              )}
            </p>
            {/* Stated on screen because an evening test run produces nothing but quiet-hours
                suppressions, which reads as a broken campaign rather than a working rule. */}
            <p className="text-xs text-muted-foreground">
              {t(
                'Tiché hodiny: 21:00–8:00. Odeslání v tomto okně se potlačí (ADR-0200 D6).',
                'Quiet hours: 21:00–08:00. Sends in that window are suppressed (ADR-0200 D6).',
              )}
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <label htmlFor="outcome-filter" className="text-xs text-muted-foreground">
                {t('Výsledek', 'Outcome')}
              </label>
              <select
                id="outcome-filter"
                value={outcomeFilter}
                onChange={e => applyFilter(e.target.value)}
                className="rounded-md border bg-transparent px-2 py-1 text-sm"
              >
                <option value="">{t('Vše', 'All')}</option>
                {OUTCOMES.map(o => (
                  <option key={o} value={o}>
                    {outcomeLabel(o)}
                    {summary[o] !== undefined ? ` (${summary[o]})` : ''}
                  </option>
                ))}
              </select>
              {sendsLoading && <span className="text-xs text-muted-foreground">{t('Načítám…', 'Loading…')}</span>}
            </div>

            {detail?.sources?.sends !== 'ok' || sendState !== 'ok' ? (
              <DataUnavailable
                kind={
                  detail?.sources?.sends === 'unauthorized' || sendState === 'unauthorized'
                    ? 'unauthorized'
                    : detail?.sources?.sends === 'not_deployed'
                      ? 'not_deployed'
                      : 'unreachable'
                }
                service="Campaign-service"
                feature={t('Log odeslání', 'Send log')}
                dense
              />
            ) : sends.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('Zatím nic odesláno ani pokusem.', 'Nothing sent or attempted yet.')}</p>
            ) : (
              <div className="overflow-x-auto rounded-lg border">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t('Party', 'Party')}</th>
                      <th>{t('Krok', 'Step')}</th>
                      <th title={t(
                        'Co rozhodla kampaň — že požadavek byl předán notification-service.',
                        'What the campaign decided — that the request was handed to notification-service.',
                      )}>{t('Předání', 'Handoff')}</th>
                      <th title={t(
                        'Co se se zprávou skutečně stalo, podle notification-service.',
                        'What actually became of the message, as reported by notification-service.',
                      )}>{t('Doručení', 'Delivery')}</th>
                      <th>{t('Kdy', 'When')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sends.map(s => (
                      <tr key={s.id}>
                        <td className="text-xs" title={s.partyId}>
                          {detail?.partyNames?.[s.partyId] ?? (
                            <span className="font-mono">{shortId(s.partyId)}</span>
                          )}
                        </td>
                        <td>{s.stepOrder}</td>
                        <td>
                          <span title={s.outcome}>
                            <StatusBadge status={s.outcome} tone={outcomeTone(s.outcome)} label={outcomeLabel(s.outcome)} />
                          </span>
                        </td>
                        <td>
                          {s.deliveryStatus ? (
                            <span title={s.deliveryReason ? `${s.deliveryStatus} — ${s.deliveryReason}` : s.deliveryStatus}>
                              <StatusBadge
                                status={s.deliveryStatus}
                                tone={deliveryTone(s.deliveryStatus)}
                                label={deliveryLabel(s.deliveryStatus)}
                              />
                            </span>
                          ) : (
                            <span className="text-xs text-muted-foreground">—</span>
                          )}
                        </td>
                        <td className="text-xs whitespace-nowrap">{fmtDateTime(s.occurredAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  )
}
