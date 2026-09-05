// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { ArrowLeft, Megaphone } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge, type Tone } from '@/components/ui'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { JourneyCanvas, type DecisionPathSelection, type JourneyDecision, type StepFunnel } from '@/components/campaigns/JourneyCanvas'
import { SectionBoundary } from '@/components/feedback/SectionBoundary'
import { PeopleSummary } from '@/components/campaigns/PeopleSummary'
import { CampaignOutcomeBrief } from '@/components/campaigns/CampaignOutcomeBrief'
import { CampaignAttentionFunnel, type CampaignAttentionMetric } from '@/components/campaigns/CampaignAttentionFunnel'
import { trapDialogFocus } from '@/lib/a11y/trapDialogFocus'

interface Campaign {
  id: string
  name: string
  goal: string
  segmentRef: { name: string; version: number }
  incentiveOfferRef?: { id: string; name: string; version: number } | null
  state: string
  createdBy: string
  approvedBy: string | null
  steps: {
    order: number
    template: string
    delaySeconds: number
    channel?: 'EMAIL' | 'PUSH' | 'BANNER'
    condition?: 'IF_PREVIOUS_CONFIRMED' | 'IF_PREVIOUS_NOT_CONFIRMED'
    conditionSourceOrder?: number
    nextStepOrder?: number
    variantBVariables?: Record<string, string> | null
    fallbackToPush?: boolean
    mobileDestination?: 'HOME' | 'SAVINGS' | 'CARDS' | 'PAYMENTS' | 'PRODUCT_HUB' | null
  }[]
  /** ADR-0245: a ConversionCatalog key, or absent when the campaign measures no conversion. */
  conversionRule?: string | null
  /** Percentage deliberately kept in the no-contact control cohort. */
  holdoutPercent?: number
  schedule?: { cadence: string; endAt?: string | null } | null
  trigger?: string | null
  decisions?: JourneyDecision[]
}

interface Enrolment {
  id: string
  partyId: string
  state: string
  currentStep: number
  decisionPath?: (DecisionPathSelection & { decidedAt?: string })[]
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
  /** Actual request medium, including a consent-authorized EMAIL → PUSH fallback. */
  channel?: 'EMAIL' | 'PUSH' | null
}

interface SendPage {
  items: Send[]
  total: number
  page: number
  size: number
}

interface Experiment {
  holdoutPercent: number
  treatment: { assigned: number; converted: number; conversionRate: number | null }
  holdout: { assigned: number; converted: number; conversionRate: number | null }
  observedLiftPercentagePoints: number | null
  /** Optional during a mixed-version rollout; absence is not an experiment verdict. */
  decision?: {
    state: 'COLLECTING_DATA' | 'INCONCLUSIVE' | 'TREATMENT_OUTPERFORMS_HOLDOUT' | 'HOLDOUT_OUTPERFORMS_TREATMENT'
    minimumAssignedPerCohort: number
    treatmentConfidenceInterval: { lower: number; upper: number } | null
    holdoutConfidenceInterval: { lower: number; upper: number } | null
  }
}

interface ContentExperiment {
  a: { assigned: number; converted: number; conversionRate: number | null }
  b: { assigned: number; converted: number; conversionRate: number | null }
  observedLiftPercentagePoints: number | null
  decision?: {
    state: 'COLLECTING_DATA' | 'INCONCLUSIVE' | 'A_OUTPERFORMS_B' | 'B_OUTPERFORMS_A'
    minimumAssignedPerVariant: number
    aConfidenceInterval: { lower: number; upper: number } | null
    bConfidenceInterval: { lower: number; upper: number } | null
  }
}

type Detail = {
  campaign: Campaign | null
  enrolments: Enrolment[]
  sends: SendPage
  partyNames: Record<string, string>
  sendSummary: Record<string, number>
  journey: StepFunnel[]
  engagement: CampaignAttentionMetric[]
  incentives: { reserved: number; committed: number; released: number; expired: number } | null
  experiment: Experiment | null
  contentExperiment: ContentExperiment | null
  entryCatalogues?: {
    cadences: { cadence: string; humanForm: string; zone: string }[]
    triggers: { trigger: string; humanForm: string }[]
  }
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
  const router = useRouter()
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
  const [actingAction, setActingAction] = useState<string | null>(null)
  const [actionIntent, setActionIntent] = useState<string | null>(null)
  const actionTriggerRef = useRef<HTMLButtonElement | null>(null)
  const [duplicating, setDuplicating] = useState(false)

  /**
   * Reuse does not change the source campaign. The server makes a separate DRAFT owned by this
   * maker and Studio then reloads the real stored definition for review — never a browser copy of
   * an ACTIVE journey or its history.
   */
  const duplicateAsDraft = () => {
    if (!id) return
    setDuplicating(true)
    setActionError(null)
    fetch(`/api/campaigns/${encodeURIComponent(id)}/duplicate`, { method: 'POST' })
      .then(r => r.json())
      .then((d: { state: string; campaign?: { id: string }; error?: string }) => {
        if (d.state === 'ok' && d.campaign?.id) {
          router.push(`/campaigns/new?draft=${encodeURIComponent(d.campaign.id)}`)
          return
        }
        setActionError(
          d.error ??
          (d.state === 'forbidden'
            ? t('Nemáte oprávnění založit nový koncept z této kampaně.', 'You are not permitted to create a new draft from this campaign.')
            : t('Koncept se nepodařilo založit. Zdrojová cesta se nezměnila.', 'The draft could not be created. The source journey is unchanged.')),
        )
      })
      .catch(() => setActionError(t('Campaign-service neodpovídá.', 'Campaign-service is not responding.')))
      .finally(() => setDuplicating(false))
  }

  const runAction = async (action: string): Promise<boolean> => {
    setActingAction(action)
    setActionError(null)
    try {
      const response = await fetch(`/api/campaigns/${encodeURIComponent(id ?? '')}/actions`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ action }),
      })
      const d = await response.json() as { state: string; error?: string }
      if (d.state === 'ok') {
        // Drop the paged override too: after a transition the first page is the right thing to
        // show, and keeping page 7 of a log that just changed is a stale view of a new state.
        setSendOverride(null)
        setReloadToken(n => n + 1)
        return true
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
      return false
    } catch {
      setActionError(t('Campaign-service neodpovídá.', 'Campaign-service is not responding.'))
      return false
    } finally {
      setActingAction(null)
    }
  }

  const closeActionReview = () => {
    if (actingAction !== null) return
    setActionIntent(null)
    requestAnimationFrame(() => actionTriggerRef.current?.focus())
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
  const actionPermission = (action: string): 'campaign:create' | 'campaign:submit' | 'campaign:activate' =>
    action === 'activate' ? 'campaign:activate' : action === 'submit' ? 'campaign:submit' : 'campaign:create'

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
  const engagement = Array.isArray(detail?.engagement) ? detail.engagement : []
  const incentiveFunnel = detail?.incentives
  const experiment = detail?.experiment
  const contentExperiment = detail?.contentExperiment
  const cadence = c?.schedule
    ? detail?.entryCatalogues?.cadences.find(x => x.cadence === c.schedule?.cadence)
    : undefined
  const trigger = c?.trigger
    ? detail?.entryCatalogues?.triggers.find(x => x.trigger === c.trigger)
    : undefined
  const decisionPaths = detail?.enrolments.flatMap(enrolment => enrolment.decisionPath ?? []) ?? []

  // From the server-side summary, never from the loaded page: a headline derived from the rows on
  // screen understates every campaign larger than one page.
  const suppressed = Object.entries(summary)
    .filter(([outcome]) => outcome.startsWith('SUPPRESSED'))
    .reduce((n, [, count]) => n + count, 0)
  const impressions = engagement
    .filter(metric => metric.type === 'IMPRESSION')
    .reduce((n, metric) => n + metric.count, 0)
  const interactions = engagement
    .filter(metric => metric.type === 'CLICK' || metric.type === 'DISMISS')
    .reduce((n, metric) => n + metric.count, 0)

  const fmtDateTime = (iso: string | null | undefined) =>
    iso
      ? new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', {
          dateStyle: 'medium', timeStyle: 'short',
        }).format(new Date(iso))
      : '—'

  const fmtRate = (rate: number | null | undefined) =>
    rate === null || rate === undefined ? '—' : `${(rate * 100).toFixed(1)} %`

  const experimentDecisionText = (decision: Experiment['decision']) => {
    if (!decision) {
      return t(
        'Tato verze služby zatím neposkytuje bránu připravenosti rozhodnutí.',
        'This service version does not yet provide a decision-readiness gate.',
      )
    }
    switch (decision.state) {
      case 'COLLECTING_DATA':
        return t(
          `Sbíráme data: pro obě skupiny je potřeba alespoň ${decision.minimumAssignedPerCohort} zařazených lidí.`,
          `Collecting data: each cohort needs at least ${decision.minimumAssignedPerCohort} assigned people.`,
        )
      case 'INCONCLUSIVE':
        return t(
          'Požadovaný rozsah dat už máme, ale 95% intervaly se překrývají. Strategii teď neměňte.',
          'The sample threshold is met, but the 95% intervals overlap. Do not change strategy yet.',
        )
      case 'TREATMENT_OUTPERFORMS_HOLDOUT':
        return t(
          '95% intervaly se oddělily ve prospěch oslovené skupiny. Je to konzervativní signál, ne automatická změna kampaně.',
          'The 95% intervals separate in favour of treatment. It is a conservative signal, not an automatic campaign change.',
        )
      case 'HOLDOUT_OUTPERFORMS_TREATMENT':
        return t(
          '95% intervaly se oddělily ve prospěch kontroly. Zkontrolujte obsah a provedení kampaně; systém nic automaticky nevypíná.',
          'The 95% intervals separate in favour of holdout. Review campaign content and delivery; the system does not stop anything automatically.',
        )
    }
  }

  const contentExperimentDecisionText = (decision: ContentExperiment['decision']) => {
    if (!decision) return t('Tato verze služby zatím neposkytuje bránu připravenosti rozhodnutí.', 'This service version does not yet provide a decision-readiness gate.')
    switch (decision.state) {
      case 'COLLECTING_DATA':
        return t(
          `Sbíráme data: pro obě varianty je potřeba alespoň ${decision.minimumAssignedPerVariant} zařazených lidí.`,
          `Collecting data: each variant needs at least ${decision.minimumAssignedPerVariant} assigned people.`,
        )
      case 'INCONCLUSIVE':
        return t(
          'Požadovaný rozsah dat už máme, ale 95% intervaly se překrývají. Obsah teď neměňte.',
          'The sample threshold is met, but the 95% intervals overlap. Do not change the content yet.',
        )
      case 'A_OUTPERFORMS_B':
        return t(
          '95% intervaly se oddělily ve prospěch varianty A. Je to konzervativní signál, ne automatická změna kampaně.',
          'The 95% intervals separate in favour of variant A. It is a conservative signal, not an automatic campaign change.',
        )
      case 'B_OUTPERFORMS_A':
        return t(
          '95% intervaly se oddělily ve prospěch varianty B. Zkontrolujte výsledek; systém ji sám nenasazuje.',
          'The 95% intervals separate in favour of variant B. Review the result; the system does not deploy it automatically.',
        )
    }
  }

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

  /** Short id for scanning; the full value stays in `title` and is what you copy. */
  const shortId = (id: string) => id.slice(0, 8)

  // Suppressions grouped by reason: "2 suppressed" tells an operator something is off,
  // "2 × quiet hours" tells them whether to act.
  const byReason = Object.fromEntries(
    Object.entries(summary).filter(([outcome, count]) => outcome.startsWith('SUPPRESSED') && count > 0),
  )

  const campaignNextAction = () => {
    if (!c) return { title: '', detail: '' }
    if (c.state === 'DRAFT') return {
      title: t('Dokončete zadání', 'Finish the brief'),
      detail: t('Pak ho předejte k nezávislému schválení.', 'Then submit it for independent approval.'),
    }
    if (c.state === 'PENDING_APPROVAL') return {
      title: t('Vyžádejte druhé oči', 'Ask for a second pair of eyes'),
      detail: t('Autor ji nemůže sám aktivovat.', 'The author cannot activate it alone.'),
    }
    if (c.state === 'PAUSED') return {
      title: t('Rozhodněte o pokračování', 'Decide whether to resume'),
      detail: t('Zkontrolujte cestu a teprve pak změňte stav.', 'Review the journey before changing its state.'),
    }
    if ((summary.FAILED ?? 0) > 0) return {
      title: t('Prověřte neúspěšná předání', 'Review failed handoffs'),
      detail: t('Použijte detailní log níže; potlačení pravidlem není chyba.', 'Use the detailed log below; policy suppression is not an error.'),
    }
    if (!c.conversionRule) return {
      title: t('Zvažte měřitelný cíl', 'Consider a measurable outcome'),
      detail: t('Bez něj uvidíte průchod, ale ne skutečný obchodní výsledek.', 'Without one you see flow, but not the real business outcome.'),
    }
    return {
      title: t('Sledujte průchod a výsledek', 'Follow the journey and outcome'),
      detail: t('Doručení, potlačení a konverze jsou níže oddělené, aby se nezaměnily.', 'Delivery, suppression and conversion remain separate below so they cannot be confused.'),
    }
  }
  const nextAction = campaignNextAction()

  return <AuthGuard permission="campaign:view">
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
          {c.state === 'DRAFT' && <Can permission="campaign:create"><Link href={`/campaigns/new?draft=${encodeURIComponent(c.id)}`} className="rounded-md border px-3 py-1.5 text-sm">
            {t('Upravit koncept', 'Edit draft')}
          </Link></Can>}
          {actionsFor(c.state).map(a => (
            <Can key={a} permission={actionPermission(a)} fallback={<span className="text-xs text-muted-foreground">{t('Čeká na oprávněného operátora', 'Awaiting an authorized operator')}</span>}>
              <button
                type="button"
                onClick={event => {
                  actionTriggerRef.current = event.currentTarget
                  setActionError(null)
                  setActionIntent(a)
                }}
                disabled={actingAction !== null}
                className="rounded-md border px-3 py-1.5 text-sm disabled:opacity-40"
              >
                {actionLabel(a)}
              </button>
            </Can>
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

      {c && actionIntent && <CampaignActionReviewDialog
        campaign={c}
        action={actionIntent}
        busy={actingAction === actionIntent}
        error={actionError}
        onCancel={closeActionReview}
        onConfirm={async () => {
          if (await runAction(actionIntent)) setActionIntent(null)
        }}
      />}

      {!loading && !unavailable && c && (
        <aside className="rounded-xl border border-indigo-100 bg-indigo-50/50 p-3 text-sm" data-testid="campaign-reuse-draft">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="font-semibold text-foreground">{t('Použít cestu jako výchozí bod', 'Use this journey as a starting point')}</p>
              <p className="mt-0.5 text-xs text-muted-foreground">
                {t(
                  'Vznikne nový koncept k vaší úpravě. Stav, schválení, příjemci a výsledky této kampaně se nikdy nekopírují.',
                  'A new draft opens for your edits. This campaign’s state, approval, recipients and results are never copied.',
                )}
              </p>
            </div>
            <Can permission="campaign:create" fallback={<span className="text-xs text-muted-foreground">{t('Kopii může vytvořit jen oprávněný operátor', 'Only an authorized operator can create a copy')}</span>}>
              <button
                type="button"
                className="btn btn-secondary"
                onClick={duplicateAsDraft}
                disabled={duplicating}
                aria-busy={duplicating}
              >
                {duplicating ? t('Zakládám koncept…', 'Creating draft…') : t('Vytvořit kopii jako koncept', 'Create draft copy')}
              </button>
            </Can>
          </div>
        </aside>
      )}

      {actionError && !actionIntent && <p role="alert" className="text-sm text-red-600">{actionError}</p>}

      {loading && <p className="text-sm text-muted-foreground">{t('Načítám…', 'Loading…')}</p>}
      {!loading && unavailable && (
        <DataUnavailable kind={unavailable} service="Campaign-service" feature={t('Detail kampaně', 'Campaign detail')} />
      )}

      {!loading && !unavailable && c && (
        <>
          <CampaignOutcomeBrief
            state={c.state}
            audience={detail?.enrolments.length ?? 0}
            handedOff={summary.SENT ?? 0}
            suppressed={suppressed}
            conversion={c.conversionRule ? (summary.CONVERTED ?? 0) : null}
            conversionLabel={c.conversionRule ? conversionLabel(c.conversionRule) : t('Cíl není měřen', 'Outcome is not measured')}
            inAppImpressions={detail?.sources?.engagement === 'ok' ? impressions : null}
            inAppInteractions={detail?.sources?.engagement === 'ok' ? interactions : null}
            nextAction={nextAction.title}
            nextActionDetail={nextAction.detail}
          />

          <section className="rounded-lg border p-3 text-sm" data-campaign-entry>
            <h2 className="font-semibold">{t('Vstup do cesty', 'Journey entry')}</h2>
            {c.trigger ? (
              <p className="mt-1 text-muted-foreground">
                {t('Při události: ', 'On an event: ')}
                <span className="font-medium text-foreground">{trigger?.humanForm ?? c.trigger}</span>
                {t(
                  ' — událost určí okamžik, ale člověk musí stále patřit do schváleného segmentu.',
                  ' — the event chooses the moment, but the person must still belong to the approved segment.',
                )}
              </p>
            ) : c.schedule ? (
              <p className="mt-1 text-muted-foreground">
                {t('Opakovaně: ', 'Recurring: ')}
                <span className="font-medium text-foreground">
                  {cadence ? `${cadence.humanForm} (${cadence.zone})` : c.schedule.cadence}
                </span>
                {c.schedule.endAt && ` · ${t('do ', 'until ')}${fmtDateTime(c.schedule.endAt)}`}
              </p>
            ) : (
              <p className="mt-1 text-muted-foreground">
                {t(
                  'Jednorázově — po spuštění se aktuální publikum zařadí ručně.',
                  'One time — the current audience is enrolled manually after activation.',
                )}
              </p>
            )}
          </section>

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

          {c.holdoutPercent && c.holdoutPercent > 0 && (
            <section className="space-y-2 rounded-lg border p-3" data-experiment>
              <div>
                <h2 className="text-sm font-semibold">{t('Kontrolní skupina', 'Control group')}</h2>
                <p className="text-xs text-muted-foreground">
                  {t(
                    'Porovnáváme skutečné bankovní konverze osloveného publika s lidmi, kterým kampaň záměrně neposlala zprávu.',
                    'Compares real banking conversions of contacted people with people deliberately sent no campaign message.',
                  )}
                </p>
              </div>
              {detail?.sources?.experiment === 'ok' && experiment ? (
                <>
                  <div className="grid gap-3 sm:grid-cols-3">
                    <StatCard
                      label={t('Oslovení', 'Treatment')}
                      value={`${fmtRate(experiment.treatment.conversionRate)} · ${experiment.treatment.converted}/${experiment.treatment.assigned}`}
                    />
                    <StatCard
                      label={t('Kontrola', 'Holdout')}
                      value={`${fmtRate(experiment.holdout.conversionRate)} · ${experiment.holdout.converted}/${experiment.holdout.assigned}`}
                    />
                    <StatCard
                      label={t('Rozdíl (p. b.)', 'Difference (pp)')}
                      value={experiment.observedLiftPercentagePoints === null ? '—' : experiment.observedLiftPercentagePoints.toFixed(1)}
                    />
                  </div>
                  <div className="rounded-md bg-muted p-2 text-xs text-muted-foreground" data-experiment-decision>
                    <span className="font-medium text-foreground">{t('Připravenost rozhodnutí: ', 'Decision readiness: ')}</span>
                    {experimentDecisionText(experiment.decision)}
                    {experiment.decision?.treatmentConfidenceInterval && experiment.decision?.holdoutConfidenceInterval && (
                      <span>
                        {' '}
                        {t('95% intervaly — oslovení ', '95% intervals — treatment ')}
                        {fmtRate(experiment.decision.treatmentConfidenceInterval.lower)}–{fmtRate(experiment.decision.treatmentConfidenceInterval.upper)}
                        {t(', kontrola ', ', holdout ')}
                        {fmtRate(experiment.decision.holdoutConfidenceInterval.lower)}–{fmtRate(experiment.decision.holdoutConfidenceInterval.upper)}.
                      </span>
                    )}
                  </div>
                </>
              ) : (
                <DataUnavailable
                  kind={detail?.sources?.experiment === 'unauthorized' ? 'unauthorized' : 'unreachable'}
                  service="Campaign-service"
                  feature={t('Vyhodnocení kontrolní skupiny', 'Control-group result')}
                  dense
                />
              )}
              <p className="text-xs text-muted-foreground">
                {t(
                  'Rozdíl je popisný. Brána používá konzervativní 95% Wilsonovy intervaly a nemůže sama prokázat příčinu ani změnit běžící kampaň.',
                  'The difference is descriptive. The gate uses conservative 95% Wilson intervals; it cannot prove causality or change a running campaign itself.',
                )}
              </p>
            </section>
          )}

          {c.steps.some(step => step.variantBVariables) && (
            <section className="space-y-2 rounded-lg border p-3" data-content-experiment>
              <div>
                <h2 className="text-sm font-semibold">{t('Porovnání obsahu A/B', 'A/B content comparison')}</h2>
                <p className="text-xs text-muted-foreground">
                  {t(
                    'Každý oslovený člověk zůstává ve variantě A nebo B po celou cestu. Porovnává se jen skutečná bankovní konverze.',
                    'Each contacted person stays in A or B throughout the journey. Only real banking conversion is compared.',
                  )}
                </p>
              </div>
              {detail?.sources?.contentExperiment === 'ok' && contentExperiment ? (
                <>
                  <div className="grid gap-3 sm:grid-cols-3">
                    <StatCard label={t('Varianta A', 'Variant A')} value={`${fmtRate(contentExperiment.a.conversionRate)} · ${contentExperiment.a.converted}/${contentExperiment.a.assigned}`} />
                    <StatCard label={t('Varianta B', 'Variant B')} value={`${fmtRate(contentExperiment.b.conversionRate)} · ${contentExperiment.b.converted}/${contentExperiment.b.assigned}`} />
                    <StatCard label={t('B − A (p. b.)', 'B − A (pp)')} value={contentExperiment.observedLiftPercentagePoints === null ? '—' : contentExperiment.observedLiftPercentagePoints.toFixed(1)} />
                  </div>
                  <div className="rounded-md bg-muted p-2 text-xs text-muted-foreground" data-content-experiment-decision>
                    <span className="font-medium text-foreground">{t('Připravenost rozhodnutí: ', 'Decision readiness: ')}</span>
                    {contentExperimentDecisionText(contentExperiment.decision)}
                    {contentExperiment.decision?.aConfidenceInterval && contentExperiment.decision?.bConfidenceInterval && (
                      <span>
                        {' '}{t('95% intervaly — A ', '95% intervals — A ')}
                        {fmtRate(contentExperiment.decision.aConfidenceInterval.lower)}–{fmtRate(contentExperiment.decision.aConfidenceInterval.upper)}
                        {t(', B ', ', B ')}
                        {fmtRate(contentExperiment.decision.bConfidenceInterval.lower)}–{fmtRate(contentExperiment.decision.bConfidenceInterval.upper)}.
                      </span>
                    )}
                  </div>
                </>
              ) : (
                <DataUnavailable
                  kind={detail?.sources?.contentExperiment === 'unauthorized' ? 'unauthorized' : 'unreachable'}
                  service="Campaign-service"
                  feature={t('Vyhodnocení variant obsahu', 'Content-variant result')}
                  dense
                />
              )}
              <p className="text-xs text-muted-foreground">
                {t(
                  'Rozdíl je popisný. Brána používá 95% Wilsonovy intervaly, automaticky nemění aktivní cestu a sama nedokazuje příčinu.',
                  'The difference is descriptive. The 95% Wilson gate never changes an active journey and does not establish causality itself.',
                )}
              </p>
            </section>
          )}

          {c.steps.some(step => step.fallbackToPush) && (
            <section className="space-y-1 rounded-lg border p-3" data-channel-fallback>
              <h2 className="text-sm font-semibold">{t('Záložní kanál', 'Fallback channel')}</h2>
              <p className="text-xs text-muted-foreground">
                {t(
                  'U vybraných e-mailových kroků se při chybějícím e-mailovém souhlasu zkusí push. I ten projde vlastním souhlasem, frekvenčním limitem, quiet hours a suppression listem; po nedoručení e-mailu se druhá zpráva neposílá.',
                  'Selected email steps may try push when email consent is absent. Push still passes its own consent, frequency cap, quiet hours and suppression list; an email delivery failure never triggers a second message.',
                )}
              </p>
            </section>
          )}

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
                  decisions={c.decisions ?? []}
                  decisionPaths={decisionPaths}
                  decisionPathsKnown={detail?.sources?.enrolments === 'ok'}
                />
              </SectionBoundary>
            )}
          </section>

          {detail?.sources?.engagement === 'ok' && (
            <CampaignAttentionFunnel
              metrics={engagement}
              hasMeasuredOutcome={c.conversionRule !== null && c.conversionRule !== undefined}
              hasHoldout={(c.holdoutPercent ?? 0) > 0}
            />
          )}

          <section className="space-y-2">
            <h2 className="text-sm font-semibold">{t('Motivace', 'Incentive')}</h2>
            <p className="text-sm text-muted-foreground">
              {c.incentiveOfferRef
                ? `${c.incentiveOfferRef.name}@${c.incentiveOfferRef.version}`
                : t('Bez odměny', 'No reward')}
            </p>
            {c.incentiveOfferRef && detail?.sources?.incentives !== 'ok' ? (
              <DataUnavailable
                kind={detail?.sources?.incentives === 'not_ready' ? 'no_data' : detail?.sources?.incentives === 'unauthorized' ? 'unauthorized' : detail?.sources?.incentives === 'not_deployed' ? 'not_deployed' : 'unreachable'}
                service="Campaign-service"
                feature={t('Výsledky odměn', 'Reward outcomes')}
                title={detail?.sources?.incentives === 'not_ready' ? t('Projekce výsledků se připravuje', 'Outcome projection is preparing') : undefined}
                detail={detail?.sources?.incentives === 'not_ready' ? t('Kafka projekce se po nasazení ještě ověřuje; nuly by nebyly spolehlivý výsledek.', 'The Kafka projection is still being verified after rollout; zeroes would not be reliable outcomes.') : undefined}
                dense
              />
            ) : c.incentiveOfferRef && incentiveFunnel ? (
              <div className="grid grid-cols-2 gap-2 md:grid-cols-4" data-testid="campaign-incentive-funnel">
                {[
                  [t('Rezervováno', 'Reserved'), incentiveFunnel.reserved, t('Držená odměna, ne uplatnění', 'Held, not redeemed')],
                  [t('Uplatněno', 'Redeemed'), incentiveFunnel.committed, t('Pouze potvrzené splnění', 'Committed only')],
                  [t('Uvolněno', 'Released'), incentiveFunnel.released, t('Nesplněná podmínka', 'Qualification not completed')],
                  [t('Expirováno', 'Expired'), incentiveFunnel.expired, t('Rezervace vypršela', 'Reservation expired')],
                ].map(([label, value, hint]) => (
                  <div key={String(label)} className="rounded-lg border p-3">
                    <p className="text-xs text-muted-foreground">{label}</p>
                    <p className="text-xl font-semibold tabular-nums">{value}</p>
                    <p className="text-xs text-muted-foreground">{hint}</p>
                  </div>
                ))}
              </div>
            ) : null}
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
                    type="button"
                    onClick={() => loadSends(sendPage.page - 1, outcomeFilter)}
                    disabled={sendPage.page === 0 || sendsLoading}
                    aria-busy={sendsLoading}
                    aria-label={t('Předchozí stránka logu odeslání', 'Previous send-log page')}
                    className="rounded-md border px-2 py-1 text-xs disabled:opacity-40"
                  >
                    {t('Předchozí', 'Previous')}
                  </button>
                  <button
                    type="button"
                    onClick={() => loadSends(sendPage.page + 1, outcomeFilter)}
                    disabled={(sendPage.page + 1) * sendPage.size >= sendPage.total || sendsLoading}
                    aria-busy={sendsLoading}
                    aria-label={t('Další stránka logu odeslání', 'Next send-log page')}
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
                      <th title={t(
                        'Kanál, který campaign-service skutečně předal notification-service.',
                        'The channel campaign-service actually handed to notification-service.',
                      )}>{t('Kanál', 'Channel')}</th>
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
                        <td>
                          {s.channel === 'EMAIL'
                            ? t('E-mail', 'Email')
                            : s.channel === 'PUSH'
                              ? t('Push', 'Push')
                              : <span className="text-xs text-muted-foreground">—</span>}
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
  </AuthGuard>
}

function CampaignActionReviewDialog({ campaign, action, busy, error, onCancel, onConfirm }: {
  campaign: Campaign
  action: string
  busy: boolean
  error: string | null
  onCancel: () => void
  onConfirm: () => Promise<void>
}) {
  const { t } = useLanguage()
  const dialogRef = useRef<HTMLDivElement>(null)
  const titleId = `campaign-${campaign.id}-action-title`
  const impactId = `campaign-${campaign.id}-action-impact`
  const label = ({
    submit: t('Odeslat ke schválení', 'Submit for approval'),
    activate: t('Schválit a spustit', 'Approve and activate'),
    enrol: t('Zařadit publikum', 'Enrol audience'),
    pause: t('Pozastavit kampaň', 'Pause campaign'),
    resume: t('Obnovit kampaň', 'Resume campaign'),
    close: t('Uzavřít kampaň', 'Close campaign'),
  } as Record<string, string>)[action] ?? action
  const impact = ({
    submit: t('Koncept předáte k nezávislému schválení. Kampaň se ještě neaktivuje ani nic neodešle.', 'The draft moves to independent approval. The campaign is not activated and nothing is sent yet.'),
    activate: t('Kampaň se stane aktivní. Autor ji nemůže schválit sám; služba znovu ověří čtyři oči.', 'The campaign becomes active. Its maker cannot self-approve; the service rechecks four-eyes.'),
    enrol: t('Aktuálně způsobilí členové schváleného publika budou zařazeni do této aktivní cesty. Souhlas a kontaktní ochrany se vyhodnocují při každém odeslání.', 'Currently eligible members of the approved audience will enter this active journey. Consent and contact protections are evaluated for every send.'),
    pause: t('Nový průchod se pozastaví, dokud kampaň znovu neobnovíte. Dosavadní auditní stopa zůstane zachována.', 'Further progression pauses until the campaign is resumed. Existing audit history remains intact.'),
    resume: t('Pozastavená cesta znovu pokračuje podle své uložené definice a ochranných pravidel.', 'The paused journey resumes under its stored definition and protection rules.'),
    close: t('Kampaň se uzavře a tato lifecycle akce není běžně vratná. Dosavadní výsledky a auditní stopa zůstanou dostupné.', 'The campaign closes and this lifecycle action is not normally reversible. Existing outcomes and audit history remain available.'),
  } as Record<string, string>)[action] ?? t('Ověřte dopad před změnou stavu kampaně.', 'Review the impact before changing campaign state.')

  return <div
    ref={dialogRef}
    role="alertdialog"
    aria-modal="true"
    aria-labelledby={titleId}
    aria-describedby={impactId}
    aria-busy={busy}
    onKeyDown={event => {
      if (event.key === 'Escape' && !busy) onCancel()
      trapDialogFocus(event, dialogRef.current)
    }}
    className="fixed inset-0 z-[1200] grid place-items-center bg-slate-950/70 p-5"
  >
    <div className="w-full max-w-xl overflow-y-auto rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl" style={{ maxHeight: 'calc(100dvh - 40px)' }}>
      <div className="flex items-start gap-3">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-violet-50 text-violet-700"><Megaphone className="h-5 w-5" aria-hidden="true" /></span>
        <div>
          <h2 id={titleId} className="text-lg font-semibold text-slate-950">{label}</h2>
          <p id={impactId} className="mt-1 text-sm leading-6 text-slate-600">{impact}</p>
        </div>
      </div>
      <dl className="mt-5 grid gap-3 rounded-xl border border-slate-200 bg-slate-50 p-4 text-sm">
        <div><dt className="text-xs font-bold uppercase tracking-wide text-slate-400">{t('Kampaň', 'Campaign')}</dt><dd className="mt-1 font-semibold text-slate-900">{campaign.name}</dd></div>
        <div><dt className="text-xs font-bold uppercase tracking-wide text-slate-400">{t('Cíl', 'Goal')}</dt><dd className="mt-1 text-slate-700">{campaign.goal}</dd></div>
        <div className="grid gap-3 sm:grid-cols-3">
          <div><dt className="text-xs font-bold uppercase tracking-wide text-slate-400">{t('Autor', 'Maker')}</dt><dd className="mt-1 text-slate-700">{campaign.createdBy}</dd></div>
          <div><dt className="text-xs font-bold uppercase tracking-wide text-slate-400">{t('Publikum', 'Audience')}</dt><dd className="mt-1 text-slate-700">{campaign.segmentRef.name} · v{campaign.segmentRef.version}</dd></div>
          <div><dt className="text-xs font-bold uppercase tracking-wide text-slate-400">{t('Kroků', 'Steps')}</dt><dd className="mt-1 text-slate-700">{campaign.steps.length}</dd></div>
        </div>
      </dl>
      {error && <p role="alert" className="mt-4 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">{error}</p>}
      <div className="mt-5 flex justify-end gap-2">
        <button type="button" autoFocus disabled={busy} onClick={onCancel} className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-700 disabled:opacity-60">{t('Zpět ke kontrole', 'Back to review')}</button>
        <button type="button" disabled={busy} aria-busy={busy} onClick={() => void onConfirm()} className="rounded-lg bg-violet-700 px-4 py-2 text-sm font-semibold text-white disabled:cursor-wait disabled:opacity-60">{busy ? t('Provádím změnu…', 'Applying change…') : t('Potvrdit akci', 'Confirm action')}</button>
      </div>
    </div>
  </div>
}
