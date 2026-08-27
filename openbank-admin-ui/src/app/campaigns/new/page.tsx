// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, BellRing, Clock3, Mail, Megaphone, PanelsTopLeft, Send, Sparkles, Users } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader } from '@/components/ui'
import { AuthGuard } from '@/components/auth/AuthGuard'
import {
  JourneyEditor,
  MAX_STEPS,
  type EditorChannel,
  type EditorDecision,
  type EditorInAppSurface,
  type EditorStep,
} from '@/components/campaigns/JourneyEditor'
import { StepEditor } from '@/components/campaigns/StepEditor'
import { CampaignExperiencePreview } from '@/components/campaigns/CampaignExperiencePreview'
import { CampaignLaunchReadiness, type CampaignContactGuardrails } from '@/components/campaigns/CampaignLaunchReadiness'
import {
  JourneyRecipePicker,
  type JourneyRecipe,
  type JourneyRecipeId,
} from '@/components/campaigns/JourneyRecipePicker'

/**
 * Campaign Studio — authoring on a canvas (ADR-0221 D1).
 *
 * The form this replaces asked for `template`, `variables` and `delaySeconds`: the engine's
 * vocabulary in the engine's order. A marketer now sees the journey they are assembling while they
 * assemble it, and edits one node at a time.
 *
 * On ADR-0221 D5, which rejects "a drag-and-drop journey canvas": the objection is a free-form
 * 40-node graph, and this is not one. The flow is linear and capped at the domain's five steps
 * (`Campaign.MAX_STEPS`) — nothing to drag, nothing to branch, nothing to arrange. It is D5's own
 * "the wizard's step list covers the honest use cases", drawn rather than listed.
 *
 * Still absent on purpose, because the service refuses both: any free-text body (only declared
 * template variables), and any way to author a segment — that is a pull request against the
 * catalogue (ADR-0201 D1).
 *
 * It also stops at "create draft". Activation is a different person's action, and offering both
 * buttons to one author would render the four-eyes gate decorative.
 */

interface Segment {
  name: string
  version: number
  rules: string[]
}

interface Cadence {
  cadence: string
  humanForm: string
  zone: string
}

interface CampaignTrigger {
  trigger: string
  humanForm: string
}

interface IncentiveOffer {
  ref: { id: string; name: string; version: number }
  productScope: string[]
  effectiveFrom: string
  expiresAt: string
  stackingPolicy: 'EXCLUSIVE' | 'STACKABLE'
}

function isIncentiveOffer(value: unknown): value is IncentiveOffer {
  if (!value || typeof value !== 'object') return false
  const offer = value as Partial<IncentiveOffer>
  const ref = offer.ref as Partial<IncentiveOffer['ref']> | undefined
  return Boolean(
    ref
    && typeof ref.id === 'string'
    && ref.id.length > 0
    && typeof ref.name === 'string'
    && ref.name.length > 0
    && typeof ref.version === 'number'
    && Number.isInteger(ref.version)
    && ref.version > 0
    && Array.isArray(offer.productScope)
    && offer.productScope.every(scope => typeof scope === 'string'),
  )
}

type IncentiveCatalogueState = 'loading' | 'ok' | 'not_deployed' | 'unauthorized' | 'unreachable'

/** The reviewed content choice served by campaign-service, rather than a second client-side copy. */
interface CampaignTemplate {
  template: string
  channel: EditorChannel
  variables: string[]
  inAppSurface?: EditorInAppSurface | null
}

/** Server definitions retain their own (potentially sparse) order; canvas cards do not. */
interface StoredCampaignStep extends EditorStep {
  order: number
}

type EntryMode = 'MANUAL' | 'SCHEDULE' | 'TRIGGER'

// Notification providers acknowledge asynchronously.  A branch taken immediately after handoff
// would almost always see PENDING and turn a real delivery question into a disguised "no" path.
// One day is the reviewed default window; the API still records the exact value with the decision.
const DELIVERY_CONFIRMATION_DELAY_SECONDS = 86_400

const newStep = (choice: CampaignTemplate): EditorStep => ({
  // The studio starts with the primary owned surface: the bank app. E-mail remains a supported
  // channel, but leading an app-first campaign with it made the canvas teach the wrong product.
  template: choice.template,
  channel: choice.channel,
  variables: {},
  delaySeconds: 0,
  ...(choice.channel === 'PUSH' || choice.channel === 'BANNER' ? { mobileDestination: 'HOME' as const } : {}),
  ...(choice.inAppSurface ? { inAppSurface: choice.inAppSurface } : {}),
})

export default function NewCampaignPage() {
  const { t } = useLanguage()
  const router = useRouter()
  const searchParams = useSearchParams()
  const requestedAudience = searchParams.get('audience')
  const draftId = searchParams.get('draft')

  const [name, setName] = useState('')
  const [goal, setGoal] = useState('')
  const [segment, setSegment] = useState('')
  const [segments, setSegments] = useState<Segment[]>([])
  const [incentiveOffers, setIncentiveOffers] = useState<IncentiveOffer[]>([])
  const [incentiveOfferRef, setIncentiveOfferRef] = useState<IncentiveOffer['ref'] | null>(null)
  const [incentiveCatalogueState, setIncentiveCatalogueState] = useState<IncentiveCatalogueState>('loading')
  const [segmentSource, setSegmentSource] = useState<'audiences' | 'segments' | null>(null)
  const [cadences, setCadences] = useState<Cadence[]>([])
  const [triggers, setTriggers] = useState<CampaignTrigger[]>([])
  const [contentCatalogue, setContentCatalogue] = useState<CampaignTemplate[]>([])
  const [contentCatalogueState, setContentCatalogueState] = useState<'loading' | 'ok' | 'unavailable'>('loading')
  const [guardrails, setGuardrails] = useState<CampaignContactGuardrails | null>(null)
  const [entryMode, setEntryMode] = useState<EntryMode>('MANUAL')
  const [cadence, setCadence] = useState('')
  const [trigger, setTrigger] = useState('')
  const [entryUnavailable, setEntryUnavailable] = useState(false)
  const [steps, setSteps] = useState<EditorStep[]>([])
  const [decisions, setDecisions] = useState<EditorDecision[]>([])
  const [journeyRecipe, setJourneyRecipe] = useState<JourneyRecipeId | null>('RETURN_TO_APP')
  const [selected, setSelected] = useState<number | null>(0)
  const [reach, setReach] = useState<number | null>(null)
  // Null = no cap, which is the service's own default (absent stopCondition runs every step).
  const [stopAfter, setStopAfter] = useState<number | null>(null)
  // Null = measure nothing, which is the service's default and an honest state rather than a gap.
  const [conversionRule, setConversionRule] = useState<string | null>(null)
  // A bounded, explicit control group is the only way to compare outcome rates with no-contact
  // peers; zero preserves the existing all-treatment behaviour.
  const [holdoutPercent, setHoldoutPercent] = useState(0)
  // A/B compares two contacted content arms. It needs the same observed conversion fact as a
  // holdout, but it never withholds a message and never chooses a winner automatically.
  const [contentExperiment, setContentExperiment] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  // State only disables the button after React renders. Keep the network action single-flight so a
  // rapid second submit cannot allocate another campaign draft before that render.
  const saveInFlight = useRef(false)

  const templates = Object.fromEntries(contentCatalogue.map(entry => [entry.template, entry.variables])) as Record<string, string[]>
  const templateChannel = Object.fromEntries(contentCatalogue.map(entry => [entry.template, entry.channel])) as Record<string, EditorChannel>
  const templateSurface = Object.fromEntries(
    contentCatalogue.flatMap(entry => entry.inAppSurface ? [[entry.inAppSurface, entry.template] as const] : []),
  ) as Partial<Record<EditorInAppSurface, string>>
  const defaultStep = () => contentCatalogue.find(entry => entry.channel === 'PUSH') ?? contentCatalogue[0]

  const templateLabels: Record<string, string> = {
    MARKETING_PRODUCT_OFFER: t('Nabídka produktu', 'Product offer'),
    MARKETING_PRODUCT_OFFER_PUSH: t('Nabídka produktu', 'Product offer'),
    MARKETING_PRODUCT_OFFER_BANNER: t('Nabídka v banneru', 'Banner offer'),
    MARKETING_PRODUCT_OFFER_CAROUSEL: t('Nabídka v carouselu', 'Carousel offer'),
    MARKETING_PRODUCT_OFFER_STORY: t('Nabídka v příběhu', 'Story offer'),
    MARKETING_PRODUCT_OFFER_PRODUCT_FEED: t('Nabídka ve feedu produktů', 'Product feed offer'),
    MARKETING_PRODUCT_OFFER_REWARDS_HUB: t('Nabídka v centru odměn', 'Rewards hub offer'),
  }

  // The template declares `offerTitle`; a marketer writes a headline. Same field, and only one of
  // those two words belongs on a screen someone uses to write an email.
  const variableLabels: Record<string, { label: string; example: string }> = {
    offerTitle: {
      label: t('Titulek', 'Headline'),
      example: t('Spoření s úrokem 4 %', 'Savings at 4% interest'),
    },
    offerText: {
      label: t('Text nabídky', 'Offer text'),
      example: t('Jedna věta, proč to stojí za to.', 'One sentence on why it is worth it.'),
    },
    ctaText: {
      label: t('Text tlačítka', 'Button text'),
      example: t('Chci spořit', 'Start saving'),
    },
  }

  useEffect(() => {
    Promise.all([fetch('/api/audiences').then(r => r.json()), fetch('/api/segments').then(r => r.json())])
      .then(([audiences, catalogue]: [{ items?: Array<Segment & { state?: string }>; state?: string }, { items?: Segment[]; state?: string }]) => {
        const approved = audiences.state === 'ok'
          ? (audiences.items ?? []).filter(item => (item.state ?? 'APPROVED') === 'APPROVED')
          : catalogue.items ?? []
        setSegments(approved)
        setSegmentSource(audiences.state === 'ok' ? 'audiences' : 'segments')
      })
      .catch(() => undefined)
  }, [])

  useEffect(() => {
    fetch('/api/incentives')
      .then(r => r.json())
      .then((response: { items?: IncentiveOffer[]; state?: string }) => {
        if (response.state === 'ok') {
          const items = response.items ?? []
          if (!Array.isArray(items) || !items.every(isIncentiveOffer)) {
            setIncentiveOffers([])
            setIncentiveCatalogueState('unreachable')
            return
          }
          setIncentiveOffers(items)
          setIncentiveCatalogueState('ok')
          return
        }
        setIncentiveCatalogueState(
          response.state === 'not_deployed' || response.state === 'unauthorized'
            ? response.state
            : 'unreachable',
        )
      })
      .catch(() => setIncentiveCatalogueState('unreachable'))
  }, [])

  // The reach is the segment's own preview, run by the service — the same evaluation enrolment runs.
  // A number computed here from a different query would agree with the send only by luck.
  function previewReach(ref: string) {
    setReach(null)
    const [segName, segVersion] = ref.split('@')
    if (!segName || !segmentSource) return
    fetch(`/api/${segmentSource}/${encodeURIComponent(segName)}/${encodeURIComponent(segVersion)}/preview`)
      .then(r => r.json())
      .then((d: { size?: number; state: string }) => {
        if (d.state === 'ok') setReach(d.size ?? 0)
      })
      .catch(() => undefined)
  }

  // A campaign is editable only before submit.  Load the real stored definition rather than
  // reconstructing it from the canvas, otherwise an omitted later field could silently disappear
  // when a maker saves an unrelated change.
  useEffect(() => {
    if (!draftId) return
    fetch(`/api/campaigns/${encodeURIComponent(draftId)}`)
      .then(r => r.json())
      .then((d: { campaign?: {
        state?: string; name?: string; goal?: string; segmentRef?: { name: string; version: number }
        steps?: StoredCampaignStep[]; decisions?: Array<{
          sourceStepOrder: number; evaluationDelaySeconds?: number
          confirmedStepOrder: number; notConfirmedStepOrder: number
        }>; stopCondition?: { maxSendsPerParty: number } | null; conversionRule?: string | null
        holdoutPercent?: number; schedule?: { cadence: string } | null; trigger?: string | null
        incentiveOfferRef?: { id: string; name: string; version: number } | null
      }; sources?: { campaign?: string } }) => {
        const campaign = d.campaign
        if (d.sources?.campaign !== 'ok' || !campaign || campaign.state !== 'DRAFT') {
          setError(t('Tento koncept už nelze upravit.', 'This campaign draft can no longer be edited.'))
          return
        }
        setName(campaign.name ?? '')
        setGoal(campaign.goal ?? '')
        if (campaign.segmentRef) {
          const ref = `${campaign.segmentRef.name}@${campaign.segmentRef.version}`
          setSegment(ref)
          previewReach(ref)
        }
        if (campaign.steps?.length) {
          // The API permits zero-based and sparse order values. The Studio owns its contiguous
          // card indexes, so map every stored edge through the actual ordered definition instead
          // of assuming a value such as 4 refers to its fifth visual card.
          const storedSteps = [...campaign.steps].sort((a, b) => a.order - b.order)
          const editorIndexByOrder = new Map(storedSteps.map((step, index) => [step.order, index]))
          const editorIndex = (order: number) => editorIndexByOrder.get(order)
          const decisionsForEditor = (campaign.decisions ?? []).map(decision => ({
            sourceStepOrder: editorIndex(decision.sourceStepOrder),
            evaluationDelaySeconds: decision.evaluationDelaySeconds ?? DELIVERY_CONFIRMATION_DELAY_SECONDS,
            confirmedStepOrder: editorIndex(decision.confirmedStepOrder),
            notConfirmedStepOrder: editorIndex(decision.notConfirmedStepOrder),
          }))
          if (decisionsForEditor.some(decision =>
            decision.sourceStepOrder === undefined ||
            decision.confirmedStepOrder === undefined ||
            decision.notConfirmedStepOrder === undefined,
          )) {
            setError(t('Koncept má neplatný odkaz v rozhodovací cestě.', 'This draft has an invalid decision-path reference.'))
            return
          }
          setSteps(storedSteps.map(step => ({
            ...step,
            ...(step.conditionSourceOrder !== undefined && step.conditionSourceOrder !== null
              ? { conditionSourceOrder: editorIndex(step.conditionSourceOrder) } : {}),
            ...(step.nextStepOrder !== undefined && step.nextStepOrder !== null
              ? { nextStepOrder: editorIndex(step.nextStepOrder) } : {}),
          })))
          if (storedSteps.some(step =>
            (step.conditionSourceOrder !== undefined && step.conditionSourceOrder !== null && editorIndex(step.conditionSourceOrder) === undefined) ||
            (step.nextStepOrder !== undefined && step.nextStepOrder !== null && editorIndex(step.nextStepOrder) === undefined),
          )) {
            setError(t('Koncept má neplatný odkaz mezi kroky.', 'This draft has an invalid step-path reference.'))
            return
          }
          setDecisions(decisionsForEditor as EditorDecision[])
          setSelected(0)
        } else {
          setDecisions([])
        }
        setStopAfter(campaign.stopCondition?.maxSendsPerParty ?? null)
        setConversionRule(campaign.conversionRule ?? null)
        setHoldoutPercent(campaign.holdoutPercent ?? 0)
        setIncentiveOfferRef(campaign.incentiveOfferRef ?? null)
        setContentExperiment(campaign.steps?.some(step => step.variantBVariables !== undefined) ?? false)
        if (campaign.schedule) {
          setEntryMode('SCHEDULE')
          setCadence(campaign.schedule.cadence)
        } else if (campaign.trigger) {
          setEntryMode('TRIGGER')
          setTrigger(campaign.trigger)
        }
        setJourneyRecipe(null)
      })
      .catch(() => setError(t('Koncept se nepodařilo načíst.', 'The campaign draft could not be loaded.')))
  // previewReach is deliberately called from this initial hydration only; it has no unstable
  // dependencies and is declared in the component closure.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftId])

  // The Audience Library hands the exact, versioned identifier to Studio. The service still
  // evaluates this audience on create; the query parameter only saves the marketer from selecting
  // the same reviewed item twice and never carries an audience definition itself.
  useEffect(() => {
    if (!requestedAudience || !segments.some(s => `${s.name}@${s.version}` === requestedAudience)) return
    setSegment(requestedAudience)
    const [name, version] = requestedAudience.split('@')
    fetch(`/api/${segmentSource}/${encodeURIComponent(name)}/${encodeURIComponent(version)}/preview`)
      .then(r => r.json())
      .then((d: { size?: number; state: string }) => {
        if (d.state === 'ok') setReach(d.size ?? 0)
      })
      .catch(() => undefined)
  }, [requestedAudience, segments, segmentSource])

  // Draft hydration can finish before the rolling audience catalogue tells us which preview
  // endpoint exists. Re-run only once that source is known; otherwise an old deployment renders
  // a real legacy draft with a permanently unknown reach.
  useEffect(() => {
    if (segment && segmentSource) previewReach(segment)
  // previewReach is intentionally a closure: only the selected immutable ref and resolved source
  // determine this external lookup.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [segment, segmentSource])

  // Entry catalogues come from campaign-service rather than a second hard-coded list: an event
  // whose consumer was removed must disappear from Studio, and a cadence may never become a raw
  // cron field that looks valid while doing something different in Temporal.
  useEffect(() => {
    Promise.all([
      fetch('/api/campaigns/cadences').then(r => r.json()),
      fetch('/api/campaigns/triggers').then(r => r.json()),
      fetch('/api/campaigns/templates').then(r => r.json()),
      fetch('/api/campaigns/guardrails').then(r => r.json()),
    ])
      .then(([cadenceResponse, triggerResponse, templateResponse, guardrailResponse]: [
        { items?: Cadence[]; state?: string },
        { items?: CampaignTrigger[]; state?: string },
        { items?: CampaignTemplate[]; state?: string },
        { guardrails?: CampaignContactGuardrails | null; state?: string },
      ]) => {
        if (cadenceResponse.state === 'ok') setCadences(cadenceResponse.items ?? [])
        if (triggerResponse.state === 'ok') setTriggers(triggerResponse.items ?? [])
        if (cadenceResponse.state !== 'ok' || triggerResponse.state !== 'ok') setEntryUnavailable(true)
        if (templateResponse.state === 'ok' && (templateResponse.items?.length ?? 0) > 0) {
          setContentCatalogue(templateResponse.items ?? [])
          setContentCatalogueState('ok')
        } else {
          setContentCatalogueState('unavailable')
        }
        if (guardrailResponse.state === 'ok' && guardrailResponse.guardrails) setGuardrails(guardrailResponse.guardrails)
      })
      .catch(() => {
        setEntryUnavailable(true)
        setContentCatalogueState('unavailable')
      })
  }, [])

  // A new journey gets a server-approved app-first step only after the catalogue arrives. There is
  // intentionally no browser fallback: a stale template fails after a marketer has authored it.
  useEffect(() => {
    if (draftId || contentCatalogueState !== 'ok') return
    const first = defaultStep()
    if (!first) return
    setSteps(previous => previous.length === 0 ? [newStep(first)] : previous)
    setSelected(previous => previous ?? 0)
  // The catalogue state changes only when its authoritative response arrives.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contentCatalogueState, draftId])

  const updateStep = (i: number, next: EditorStep) =>
    setSteps(prev => prev.map((s, k) => (k === i ? next : s)))

  const addStep = () =>
    setSteps(prev => {
      if (prev.length >= MAX_STEPS) return prev
      const first = defaultStep()
      if (!first) return prev
      setSelected(prev.length)
      return [...prev, {
        ...newStep(first),
        ...(contentExperiment ? { variantBVariables: {} } : {}),
      }]
    })

  /** Create a real reviewed decision node, not two hidden linear conditions. */
  const addDeliveryDecision = () =>
    setSteps(prev => {
      if (prev.length < 1 || prev.length > MAX_STEPS - 2) return prev
      const first = defaultStep()
      if (!first) return prev
      const decisionStep = (): EditorStep => ({
        ...newStep(first),
        ...(contentExperiment ? { variantBVariables: {} } : {}),
      })
      setSelected(prev.length)
      setDecisions(current => [...current, {
        sourceStepOrder: prev.length - 1,
        evaluationDelaySeconds: DELIVERY_CONFIRMATION_DELAY_SECONDS,
        confirmedStepOrder: prev.length,
        notConfirmedStepOrder: prev.length + 1,
      }])
      return [
        ...prev,
        decisionStep(),
        decisionStep(),
      ]
    })

  const applyRecipe = (recipe: JourneyRecipe) => {
    const verified = recipe.steps.every(step =>
      templates[step.template] !== undefined && templateChannel[step.template] === step.channel,
    )
    if (!verified) {
      setError(t('Tento recept používá obsah, který už není v ověřeném katalogu.', 'This recipe uses content no longer in the verified catalogue.'))
      return
    }
    // A recipe is only an authoring shortcut. Clone every map so opening one step can never alter
    // another step's values through a shared object reference.
    setSteps(recipe.steps.map(step => ({
      ...step,
      variables: { ...step.variables },
      ...(step.variantBVariables ? { variantBVariables: { ...step.variantBVariables } } : {}),
    })))
    setJourneyRecipe(recipe.id)
    setDecisions([])
    setSelected(0)
  }

  const removeStep = (i: number) =>
    setSteps(prev => {
      const next = prev.filter((_, k) => k !== i)
      // Renumbering graph edges after a deletion can silently choose a different customer path.
      // Removing any card therefore intentionally returns the draft to its explicit linear shape.
      setDecisions([])
      setSelected(null)
      return next
    })

  const incomplete = steps.some(s =>
    templates[s.template] === undefined ||
    templates[s.template].some(v => !(s.variables[v] ?? '').trim()) ||
    (contentExperiment && (templates[s.variantBTemplate ?? s.template] ?? [])
      .some(v => !(s.variantBVariables?.[v] ?? '').trim())),
  )
  const entryConfigured =
    entryMode === 'MANUAL' ||
    (entryMode === 'SCHEDULE' && cadence !== '') ||
    (entryMode === 'TRIGGER' && trigger !== '')
  const pinnedIncentiveUnavailable = incentiveOfferRef !== null &&
    !incentiveOffers.some(offer => offer.ref.id === incentiveOfferRef.id)
  const ready = name.trim() !== '' && goal.trim() !== '' && segment !== '' && steps.length > 0 &&
    contentCatalogueState === 'ok' && !incomplete && entryConfigured && (!contentExperiment || conversionRule !== null) &&
    !pinnedIncentiveUnavailable
  // A campaign is an experience across surfaces, not a list of transport rows. Keep this compact
  // overview next to the canvas so a marketer can scan the whole customer footprint without
  // opening every node. It is derived solely from the steps that will be sent to campaign-service.
  const surfaceLabel = (surface: EditorInAppSurface) => {
    const labels: Record<EditorInAppSurface, [string, string]> = {
      HOME_BANNER: ['Banner na domovské obrazovce', 'Home banner'],
      HOME_CAROUSEL: ['Carousel na domovské obrazovce', 'Home carousel'],
      STORIES: ['Příběh v aplikaci', 'In-app story'],
      PRODUCT_FEED: ['Feed produktů', 'Product feed'],
      REWARDS_HUB: ['Centrum odměn', 'Rewards hub'],
    }
    const [cs, en] = labels[surface]
    return t(cs, en)
  }

  const destinationLabel = (destination: NonNullable<EditorStep['mobileDestination']>) => {
    const labels: Record<NonNullable<EditorStep['mobileDestination']>, [string, string]> = {
      HOME: ['Domov', 'Home'],
      SAVINGS: ['Spoření', 'Savings'],
      CARDS: ['Karty', 'Cards'],
      PAYMENTS: ['Platby', 'Payments'],
      PRODUCT_HUB: ['Produkty', 'Products'],
    }
    const [cs, en] = labels[destination]
    return t(cs, en)
  }

  const setContentExperimentEnabled = (enabled: boolean) => {
    setContentExperiment(enabled)
    if (enabled) {
      // Copy A once when the test is enabled. Later edits intentionally diverge, otherwise both
      // arms would change together and the test would be a convincing-looking no-op.
      setSteps(prev => prev.map(s => ({ ...s, variantBVariables: { ...s.variables } })))
    }
  }

  const chooseEntryMode = (next: EntryMode) => {
    setEntryMode(next)
    if (next === 'SCHEDULE' && cadence === '' && cadences[0]) setCadence(cadences[0].cadence)
    if (next === 'TRIGGER' && trigger === '' && triggers[0]) setTrigger(triggers[0].trigger)
  }

  const submit = () => {
    if (saveInFlight.current) return
    saveInFlight.current = true
    setSaving(true)
    setError(null)
    const [segName, segVersion] = segment.split('@')
    fetch(draftId ? `/api/campaigns/${encodeURIComponent(draftId)}` : '/api/campaigns', {
      method: draftId ? 'PUT' : 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        name: name.trim(),
        goal: goal.trim(),
        segmentName: segName,
        segmentVersion: Number(segVersion),
        ...(stopAfter !== null ? { stopCondition: { maxSendsPerParty: stopAfter } } : {}),
        ...(conversionRule ? { conversionRule } : {}),
        ...(holdoutPercent > 0 ? { holdoutPercent } : {}),
        ...(incentiveOfferRef ? { incentiveOfferRef } : {}),
        ...(entryMode === 'SCHEDULE' && cadence ? { schedule: { cadence } } : {}),
        ...(entryMode === 'TRIGGER' && trigger ? { trigger } : {}),
        ...(decisions.length > 0 ? {
          decisions: decisions.map(d => ({
            sourceStepOrder: d.sourceStepOrder + 1,
            evaluationDelaySeconds: d.evaluationDelaySeconds,
            confirmedStepOrder: d.confirmedStepOrder + 1,
            notConfirmedStepOrder: d.notConfirmedStepOrder + 1,
          })),
        } : {}),
        steps: steps.map((s, i) => ({
          order: i + 1,
          template: s.template,
          channel: s.channel,
          ...(s.condition ? { condition: s.condition } : {}),
          ...(s.conditionSourceOrder !== undefined ? { conditionSourceOrder: s.conditionSourceOrder + 1 } : {}),
          variables: s.variables,
          ...(contentExperiment ? { variantBVariables: s.variantBVariables ?? {} } : {}),
          ...(contentExperiment && s.variantBTemplate ? { variantBTemplate: s.variantBTemplate } : {}),
          ...(contentExperiment && s.variantBChannel ? { variantBChannel: s.variantBChannel } : {}),
          ...(contentExperiment && s.variantBDelaySeconds !== undefined ? { variantBDelaySeconds: s.variantBDelaySeconds } : {}),
          ...(s.fallbackToPush ? { fallbackToPush: true } : {}),
          ...(s.mobileDestination ? { mobileDestination: s.mobileDestination } : {}),
          ...(s.inAppSurface ? { inAppSurface: s.inAppSurface } : {}),
          ...(s.nextStepOrder !== undefined ? { nextStepOrder: s.nextStepOrder + 1 } : {}),
          delaySeconds: s.delaySeconds,
        })),
      }),
    })
      .then(r => r.json())
      .then((d: { state: string; campaign?: { id: string }; error?: string; message?: string }) => {
        if (d.state === 'ok' && d.campaign) {
          router.push(`/campaigns/${d.campaign.id}`)
          return
        }
        // The service's own message names what to change — an unknown template, an undeclared
        // variable. Replacing it with "could not create" removes the only actionable part.
        setError(
          d.message ??
            d.error ??
            (d.state === 'forbidden'
              ? t('Nemáte oprávnění tento koncept upravit.', 'You are not permitted to revise this draft.')
              : t('Campaign-service neodpovídá.', 'Campaign-service is not responding.')),
        )
      })
      .catch(() => setError(t('Campaign-service neodpovídá.', 'Campaign-service is not responding.')))
      .finally(() => {
        saveInFlight.current = false
        setSaving(false)
      })
  }

  return <AuthGuard permission="campaign:create">
    <div className="campaign-composer">
      <header className="campaign-composer-hero">
        <Link href="/campaigns" className="campaign-composer-back">
          <ArrowLeft className="h-4 w-4" /> {t('Kampaně', 'Campaigns')}
        </Link>
        <div className="campaign-composer-hero-main">
          <div>
            <p className="campaign-composer-eyebrow"><Sparkles className="h-3.5 w-3.5" /> {t('Campaign studio', 'Campaign studio')}</p>
            <PageHeader
              title={draftId ? t('Upravit koncept', 'Edit draft') : t('Nová kampaň', 'New campaign')}
              subtitle={t(
                'Navrhněte zážitek v aplikaci, zprávu a okamžik, kdy má přijít. Aktivaci pak vždy potvrdí druhý člověk.',
                'Design the in-app moment, the message and when it appears. A second person always confirms activation.',
              )}
              icon={<Megaphone className="h-6 w-6" />}
            />
          </div>
          <div className="campaign-composer-principles" aria-label={t('Principy kampaně', 'Campaign principles')}>
            <span><Users className="h-4 w-4" /> {t('Správné publikum', 'Right audience')}</span>
            <span><Send className="h-4 w-4" /> {t('Aplikace napřed', 'App first')}</span>
            <span><Clock3 className="h-4 w-4" /> {t('Schválení ve dvou', 'Four eyes')}</span>
          </div>
        </div>
      </header>

      {/* A marketer names a campaign and picks who gets it. Both were `<label>` + bare box, which is
          how a database table looks, not how a campaign brief does. The name behaves like a document
          title; the audience is a set of tiles carrying its plain-language rule and its reach, which
          is the choice being made — a dropdown hides exactly the number the choice turns on. */}
      <section className="campaign-setup-grid">
        <div className="campaign-brief-card">
          <div className="campaign-section-heading">
            <span className="campaign-section-number">01</span>
            <div><p>{t('Kreativní brief', 'Creative brief')}</p><h2>{t('Začněte záměrem', 'Start with intent')}</h2></div>
          </div>
          <input
            id="c-name"
            aria-label={t('Název kampaně', 'Campaign name')}
            className="input w-full"
            style={{ fontSize: '1.5rem', fontWeight: 600, padding: '0.7rem 0.9rem' }}
            placeholder={t('Pojmenujte kampaň', 'Name this campaign')}
            value={name}
            onChange={e => setName(e.target.value)}
          />
          <input
            id="c-goal"
            aria-label={t('Cíl kampaně', 'Campaign goal')}
            className="input w-full"
            style={{ marginTop: '0.75rem' }}
            placeholder={t(
              'Čeho má dosáhnout? Třeba „víc lidí si založí spoření"',
              'What should it achieve? e.g. "more people open a savings account"',
            )}
            value={goal}
            onChange={e => setGoal(e.target.value)}
          />
        </div>

        <div className="campaign-audience-card">
          <div className="campaign-section-heading">
            <span className="campaign-section-number">02</span>
            <div><p>{t('Publikum', 'Audience')}</p><h2>{t('Komu to půjde', 'Who gets it')}</h2></div>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            {segments.map(s => {
              const ref = `${s.name}@${s.version}`
              const active = segment === ref
              return (
                <button
                  key={ref}
                  type="button"
                  data-segment={ref}
                  data-selected={active ? 'true' : 'false'}
                  aria-pressed={active}
                  aria-label={t(`Vybrat publikum ${s.name}`, `Select ${s.name} audience`)}
                  onClick={() => {
                    setSegment(ref)
                    previewReach(ref)
                  }}
                  className="rounded-xl border text-left"
                  style={{
                    padding: '0.9rem 1rem',
                    background: 'var(--surface)',
                    borderColor: active ? 'var(--accent)' : 'var(--border)',
                    boxShadow: active ? '0 0 0 1px var(--accent)' : undefined,
                  }}
                >
                  <p className="text-sm font-semibold">{s.name}</p>
                  <p className="text-xs text-muted-foreground" style={{ marginTop: '0.25rem' }}>
                    {s.rules.join('; ')}
                  </p>
                  {/* The reach lands on the tile that was chosen, next to the rule that produced it —
                      the two facts a marketer weighs together. */}
                  <p className="text-xs text-muted-foreground" style={{ marginTop: '0.5rem' }}>
                    {active && reach !== null
                      ? t(`≈ ${reach} lidí`, `≈ ${reach} people`)
                      : t(`verze ${s.version}`, `version ${s.version}`)}
                  </p>
                </button>
              )
            })}
          </div>
          {/* Segments are code (ADR-0201 D1). Saying so is cheaper than letting someone hunt for an
              "add segment" button that will never exist. */}
          <p className="text-xs text-muted-foreground" style={{ marginTop: '0.75rem' }}>
            {t(
              'Segmenty jsou definované v kódu a verzované. Nový segment je pull request, ne akce v UI.',
              'Segments are defined in code and versioned. A new segment is a pull request, not a UI action.',
            )}
          </p>
        </div>

        <div className="campaign-audience-card" data-incentive-selection>
          <div className="campaign-section-heading">
            <div>
              <p>{t('Motivace', 'Incentive')}</p>
              <h2>{t('Volitelná odměna', 'Optional reward')}</h2>
            </div>
          </div>
          <label htmlFor="c-incentive" className="text-sm font-medium">
            {t('Publikovaná nabídka', 'Published offer')}
          </label>
          <select
            id="c-incentive"
            className="input w-full"
            value={incentiveOfferRef?.id ?? ''}
            disabled={incentiveCatalogueState !== 'ok'}
            onChange={event => setIncentiveOfferRef(
              incentiveOffers.find(offer => offer.ref.id === event.target.value)?.ref ?? null,
            )}
          >
            <option value="">{t('Bez odměny', 'No reward')}</option>
            {pinnedIncentiveUnavailable && incentiveOfferRef && (
              <option value={incentiveOfferRef.id}>
                {incentiveOfferRef.name}@{incentiveOfferRef.version} · {t('již není dostupná', 'no longer available')}
              </option>
            )}
            {incentiveOffers.map(offer => (
              <option key={offer.ref.id} value={offer.ref.id}>
                {offer.ref.name}@{offer.ref.version} · {offer.productScope.join(', ')}
              </option>
            ))}
          </select>
          {incentiveCatalogueState !== 'ok' && (
            <p role="status" className="text-xs text-muted-foreground" style={{ marginTop: '0.5rem' }}>
              {incentiveCatalogueState === 'not_deployed'
                ? t('Incentive service není v tomto prostředí nasazená.', 'Incentive service is not deployed in this environment.')
                : incentiveCatalogueState === 'unauthorized'
                  ? t('Katalog odměn nemáte oprávnění zobrazit.', 'You are not authorized to view the incentive catalogue.')
                  : incentiveCatalogueState === 'loading'
                    ? t('Načítám katalog odměn…', 'Loading incentive catalogue…')
                    : t('Katalog odměn teď není dostupný.', 'The incentive catalogue is currently unavailable.')}
            </p>
          )}
          {incentiveCatalogueState === 'ok' && pinnedIncentiveUnavailable && incentiveOfferRef && (
            <p role="alert" className="text-xs text-muted-foreground" style={{ marginTop: '0.5rem' }}>
              {t(
                `Připnutá nabídka ${incentiveOfferRef.name}@${incentiveOfferRef.version} už není publikovaná. Vyberte jinou nebo odměnu výslovně odeberte.`,
                `Pinned offer ${incentiveOfferRef.name}@${incentiveOfferRef.version} is no longer published. Choose another offer or explicitly remove the reward.`,
              )}
            </p>
          )}
          <p className="text-xs text-muted-foreground" style={{ marginTop: '0.5rem' }}>
            {t(
              'Kampaň uloží přesnou publikovanou verzi. Rezervaci kódu a hodnotu odměny řídí Incentive service.',
              'The campaign pins the exact published revision. Incentive service owns code reservation and reward value.',
            )}
          </p>
        </div>

        <div className="campaign-entry-card" data-entry-mode={entryMode}>
          <div className="campaign-section-heading">
            <span className="campaign-section-number">03</span>
            <div><p>{t('Vstup do cesty', 'Journey entry')}</p><h2>{t('Kdy cesta začne', 'When the journey starts')}</h2></div>
            <p className="text-xs text-muted-foreground" style={{ marginTop: '0.25rem' }}>
              {t(
                'Vyberte jeden přezkoumatelný zdroj vstupu. Publikum stále určuje segment výše.',
                'Choose one reviewable entry source. The segment above still decides who may enter.',
              )}
            </p>
          </div>
          <div className="campaign-entry-options">
            <button
              type="button"
              data-entry-pick="MANUAL"
              data-selected={entryMode === 'MANUAL' ? 'true' : 'false'}
              aria-pressed={entryMode === 'MANUAL'}
              onClick={() => chooseEntryMode('MANUAL')}
              className="rounded-lg border p-3 text-left text-sm"
              style={entryMode === 'MANUAL' ? { borderColor: 'var(--accent)', boxShadow: '0 0 0 1px var(--accent)' } : undefined}
            >
              <span className="font-medium">{t('Jednorázově', 'One time')}</span>
              <span className="mt-1 block text-xs text-muted-foreground">
                {t('Po spuštění ručně zařadíte aktuální publikum.', 'After activation, enrol the current audience manually.')}
              </span>
            </button>
            <button
              type="button"
              data-entry-pick="SCHEDULE"
              data-selected={entryMode === 'SCHEDULE' ? 'true' : 'false'}
              aria-pressed={entryMode === 'SCHEDULE'}
              onClick={() => chooseEntryMode('SCHEDULE')}
              disabled={cadences.length === 0}
              className="rounded-lg border p-3 text-left text-sm disabled:opacity-40"
              style={entryMode === 'SCHEDULE' ? { borderColor: 'var(--accent)', boxShadow: '0 0 0 1px var(--accent)' } : undefined}
            >
              <span className="font-medium">{t('Opakovaně', 'Recurring')}</span>
              <span className="mt-1 block text-xs text-muted-foreground">
                {t('Pravidelně zkontroluje, kdo do segmentu nově patří.', 'Rechecks who newly belongs to the segment on a schedule.')}
              </span>
            </button>
            <button
              type="button"
              data-entry-pick="TRIGGER"
              data-selected={entryMode === 'TRIGGER' ? 'true' : 'false'}
              aria-pressed={entryMode === 'TRIGGER'}
              onClick={() => chooseEntryMode('TRIGGER')}
              disabled={triggers.length === 0}
              className="rounded-lg border p-3 text-left text-sm disabled:opacity-40"
              style={entryMode === 'TRIGGER' ? { borderColor: 'var(--accent)', boxShadow: '0 0 0 1px var(--accent)' } : undefined}
            >
              <span className="font-medium">{t('Při události', 'On an event')}</span>
              <span className="mt-1 block text-xs text-muted-foreground">
                {t('Zařadí člověka hned po sledované bankovní události.', 'Enrols a person as soon as the observed banking event happens.')}
              </span>
            </button>
          </div>
          {entryMode === 'SCHEDULE' && (
            <label className="block max-w-xl text-sm">
              <span className="font-medium">{t('Rytmus', 'Cadence')}</span>
              <select
                className="input mt-1 block w-full"
                value={cadence}
                onChange={e => setCadence(e.target.value)}
                data-cadence
              >
                {cadences.map(c => <option key={c.cadence} value={c.cadence}>{c.humanForm} ({c.zone})</option>)}
              </select>
            </label>
          )}
          {entryMode === 'TRIGGER' && (
            <label className="block max-w-xl text-sm">
              <span className="font-medium">{t('Událost', 'Event')}</span>
              <select
                className="input mt-1 block w-full"
                value={trigger}
                onChange={e => setTrigger(e.target.value)}
                data-trigger
              >
                {triggers.map(x => <option key={x.trigger} value={x.trigger}>{x.humanForm}</option>)}
              </select>
            </label>
          )}
          {entryUnavailable && (
            <p className="text-xs text-muted-foreground">
              {t(
                'Některý katalog vstupů teď není dostupný; nenabízíme jeho neověřené volby.',
                'One entry catalogue is unavailable; its unverified choices are not offered.',
              )}
            </p>
          )}
        </div>
      </section>

      {contentCatalogueState === 'ok' && <JourneyRecipePicker selected={journeyRecipe} onApply={applyRecipe} />}

      <section className="campaign-journey-workbench">
        <div className="campaign-workbench-heading">
          <div>
            <p className="campaign-composer-eyebrow"><Sparkles className="h-3.5 w-3.5" /> {t('Journey composer', 'Journey composer')}</p>
            <h2>{t('Cesta, kterou lidé skutečně zažijí', 'The journey people will actually experience')}</h2>
            <p>{t('Začněte mobilním momentem. Push otevře bezpečný deep link a obsah pokračuje uvnitř aplikace.', 'Start with a mobile moment. Push opens a secure deep link and the experience continues inside the app.')}</p>
          </div>
          <span className="campaign-workbench-status"><span /> {steps.length}/{MAX_STEPS} {t('kroků', 'steps')}</span>
        </div>
        {contentCatalogueState !== 'ok' && (
          <p className="text-xs text-muted-foreground" data-content-catalogue-state={contentCatalogueState}>
            {contentCatalogueState === 'loading'
              ? t('Načítáme ověřený katalog obsahu…', 'Loading the reviewed content catalogue…')
              : t('Katalog obsahu teď není dostupný; nové kroky ani recepty nenabízíme.', 'The content catalogue is unavailable; new steps and recipes are not offered.')}
          </p>
        )}
        {/* space-y-0 around the canvas+panel pair: any gap between them undoes the join. */}
        <div className="space-y-0">
        <JourneyEditor
          attachedBelow={selected !== null && steps[selected] !== undefined}
          steps={steps}
          // `savers@2` is how the API refers to a segment. The node says who they are; the tile above
          // already carries the version, which is the only place it is a decision.
          audience={segment ? segment.split('@')[0] : ''}
          audienceSize={reach}
          selected={selected}
          onSelect={setSelected}
          onAdd={addStep}
          onAddDecision={addDeliveryDecision}
          decisions={decisions}
          contentCatalogueReady={contentCatalogueState === 'ok'}
          onRemove={removeStep}
          templateLabels={templateLabels}
          stopAfter={stopAfter}
        />

        {/* No gap and no separate card: the panel is the selected node opened, so it continues the
            canvas surface rather than sitting under it as an unrelated block. */}
        {selected !== null && steps[selected] && (
          <StepEditor
            attached
            index={selected}
            step={steps[selected]}
            templates={templates}
            templateChannel={templateChannel}
            templateSurface={templateSurface}
            templateLabels={templateLabels}
            variableLabels={variableLabels}
            contentExperiment={contentExperiment}
            onChange={next => updateStep(selected, next)}
            onClose={() => setSelected(null)}
          />
        )}

        </div>

        <aside className="campaign-surface-map" aria-label={t('Přehled zákaznických ploch', 'Customer surface overview')}>
          <div>
            <p className="campaign-composer-eyebrow"><PanelsTopLeft className="h-3.5 w-3.5" /> {t('Zážitek napříč aplikací', 'Experience across the app')}</p>
            <h3>{t('Co lidé skutečně uvidí', 'What people will actually see')}</h3>
            <p>{t('Vyberte moment a náhled telefonu se přepne. Jen kroky z této cesty, nic nedoplňujeme domněnkou.', 'Choose a moment to switch the phone preview. Only steps in this journey; nothing is inferred.')}</p>
          </div>
          <div className="campaign-surface-map-items" data-testid="campaign-surface-map">
            {steps.length === 0 ? (
              <span className="campaign-surface-map-empty">{t('Přidejte první ověřený krok.', 'Add the first reviewed step.')}</span>
            ) : steps.map((step, index) => {
              const Icon = step.channel === 'PUSH' ? BellRing : step.channel === 'BANNER' ? PanelsTopLeft : Mail
              const channel = step.channel === 'PUSH' ? t('Push do aplikace', 'App push') : step.channel === 'BANNER' ? t('Banner v aplikaci', 'In-app banner') : t('E-mail', 'Email')
              const detail = step.channel === 'BANNER'
                ? surfaceLabel(step.inAppSurface ?? 'HOME_BANNER')
                : step.channel === 'PUSH'
                  ? t(`Otevře: ${destinationLabel(step.mobileDestination ?? 'HOME')}`, `Opens: ${destinationLabel(step.mobileDestination ?? 'HOME')}`)
                  : t('Schválená šablona', 'Approved template')
              return (
                <button
                  key={`${step.channel}-${index}`}
                  type="button"
                  data-surface={step.channel}
                  data-touchpoint={index}
                  data-selected={selected === index ? 'true' : 'false'}
                  aria-pressed={selected === index}
                  onClick={() => setSelected(index)}
                >
                  <span className="campaign-surface-map-icon"><Icon className="h-3.5 w-3.5" /></span>
                  <span className="campaign-surface-map-copy">
                    <strong>{t(`Krok ${index + 1}: ${channel}`, `Step ${index + 1}: ${channel}`)}</strong>
                    <small>{detail}</small>
                  </span>
                  <span className="campaign-surface-map-open">{t('Náhled', 'Preview')}</span>
                </button>
              )
            })}
          </div>
        </aside>

        <div className="campaign-studio-companion-grid">
          <CampaignExperiencePreview step={selected === null ? undefined : steps[selected]} campaignName={name} />
          <CampaignLaunchReadiness
            audienceChosen={segment !== ''}
            audienceSize={reach}
            entryConfigured={entryConfigured}
            incomplete={incomplete}
            conversionRule={conversionRule}
            contentExperiment={contentExperiment}
            steps={steps}
            stopAfter={stopAfter}
            guardrails={guardrails}
          />
        </div>

        {/* What "it worked" means, asked at authoring time because it cannot be answered later:
            attribution runs from the first send, so a rule added after the fact measures nothing
            retroactively (ADR-0245 D2). The options are a closed catalogue — a marketer picks what
            the bank already observes and cannot invent a metric (D1). */}
        <div className="campaign-measurement-grid">
        <div className="campaign-measurement-card">
          <span className="text-sm font-medium">{t('Co znamená úspěch', 'What counts as success')}</span>
          <div className="flex flex-wrap gap-2">
            {[null, 'ACCOUNT_OPENED', 'CARD_ISSUED'].map(r => (
              <button
                key={r ?? 'NONE'}
                type="button"
                data-conversion-pick={r ?? 'NONE'}
                data-selected={conversionRule === r ? 'true' : 'false'}
                onClick={() => {
                  setConversionRule(r)
                  if (r === null) {
                    setHoldoutPercent(0)
                    setContentExperiment(false)
                  }
                }}
                className="btn"
                style={
                  conversionRule === r
                    ? { borderColor: 'var(--accent)', boxShadow: '0 0 0 1px var(--accent)' }
                    : undefined
                }
              >
                {r === null
                  ? t('Neměřit', 'Do not measure')
                  : r === 'ACCOUNT_OPENED'
                    ? t('Založení účtu', 'Account opened')
                    : t('Vydání karty', 'Card issued')}
              </button>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">
            {t(
              'Počítá se skutečná událost v bance, ne otevření e-mailu ani proklik — ty se nesledují.',
              'Counted from a real banking event, never an email open or a click — those are not tracked.',
            )}
          </p>
        </div>

        <div className="campaign-measurement-card" data-content-experiment={contentExperiment ? 'true' : 'false'}>
          <label className="flex items-center gap-2 text-sm font-medium" htmlFor="c-content-experiment">
            <input
              id="c-content-experiment"
              type="checkbox"
              checked={contentExperiment}
              disabled={!conversionRule}
              onChange={e => setContentExperimentEnabled(e.target.checked)}
            />
            {t('Porovnat variantu A a B', 'Compare variant A and B')}
          </label>
          <p className="text-xs text-muted-foreground">
            {conversionRule
              ? t(
                  'Každý člověk dostane stabilně A nebo B; u každého kroku pak upravíte hodnoty varianty B. Výsledek vychází jen ze skutečné bankovní konverze.',
                  'Each person consistently receives A or B; edit B values in every step. The result comes only from a real banking conversion.',
                )
              : t(
                  'Nejdřív vyberte měřitelný cíl. Bez něj by dvě verze obsahu neměly důvěryhodný výsledek.',
                  'Choose a measurable success event first. Without it, two content versions have no credible outcome.',
                )}
          </p>
        </div>

        <div className="campaign-measurement-card">
          <label className="flex items-center gap-2 text-sm font-medium" htmlFor="c-holdout">
            {t('Kontrolní skupina', 'Control group')}
          </label>
          <div className="flex items-center gap-3">
            <input
              id="c-holdout"
              data-holdout-percent
              type="number"
              min="0"
              max="50"
              step="5"
              className="input"
              style={{ width: '5.5rem' }}
              value={holdoutPercent}
              disabled={!conversionRule}
              onChange={e => setHoldoutPercent(Math.min(50, Math.max(0, Number(e.target.value) || 0)))}
            />
            <span className="text-sm text-muted-foreground">%</span>
          </div>
          <p className="text-xs text-muted-foreground">
            {conversionRule
              ? t(
                  'Tito lidé dostanou trvale stejné zařazení, ale žádnou zprávu. Porovnáme jejich skutečnou konverzi s osloveným publikem.',
                  'These people keep a stable assignment but receive no message. Their real conversion rate is compared with the contacted audience.',
                )
              : t(
                  'Nejdřív vyberte měřitelný cíl. Bez něj by kontrolní skupina jen zadržela komunikaci bez možnosti zjistit výsledek.',
                  'Choose a measurable success event first. Without it, a control group would withhold communication without any way to learn from it.',
                )}
          </p>
        </div>

        {/* The one contact rule a campaign DOES own. The platform-wide ones below are read-only; this
            cap is per-campaign by design (ADR-0200 D1), so it is offered here rather than described. */}
        <div className="campaign-measurement-card">
          <label className="flex items-center gap-2 text-sm font-medium">
            <input
              type="checkbox"
              data-stop-enabled
              checked={stopAfter !== null}
              onChange={e => setStopAfter(e.target.checked ? 2 : null)}
            />
            {t('Ukončit cestu po několika zprávách', 'End the journey after a few messages')}
          </label>
          {stopAfter !== null && (
            <div className="flex items-center gap-2">
              <input
                type="number"
                min="1"
                data-stop-after
                className="input"
                style={{ width: '5.5rem' }}
                value={stopAfter}
                onChange={e => setStopAfter(Math.max(1, Number(e.target.value) || 1))}
              />
              <span className="text-sm text-muted-foreground">
                {t('zprávách na člověka — pak cesta skončí', 'messages per person — then the journey ends')}
              </span>
            </div>
          )}
          <p className="text-xs text-muted-foreground">
            {t(
              'Počítají se skutečně odeslané zprávy, ne kroky. Potlačený krok se nezapočítá.',
              'Counts messages actually sent, not steps. A suppressed step does not count.',
            )}
          </p>
        </div>
        </div>

        {/* Read-only on purpose: the contact policy is a single enforcement point, and a per-campaign
            override here would make that point decorative (ADR-0219 D4, ADR-0221 D1 step 4). */}
        <div className="campaign-contact-rules">
          <p className="font-medium text-foreground">{t('Pravidla kontaktu', 'Contact rules')}</p>
          <p>
            {t(
              'Tiché hodiny 21:00–8:00, frekvenční strop a potlačení platí pro všechny kampaně a odsud se měnit nedají.',
              'Quiet hours 21:00–08:00, the frequency cap and suppression apply to every campaign and cannot be changed here.',
            )}
          </p>
        </div>
      </section>

      {error && <p className="text-sm text-red-600">{error}</p>}

      <footer className="campaign-composer-footer">
        <div>
          <p>{draftId ? t('Upravujete koncept; zatím nikomu nic neposílá.', 'You are revising a draft; it still sends nothing.') : t('Koncept se zatím nikomu neposílá.', 'A draft does not send anything yet.')}</p>
          <span>{t('Po kontrole jej aktivuje jiný oprávněný člověk.', 'A different authorised person activates it after review.')}</span>
        </div>
        <div className="campaign-composer-footer-actions">
        <button
          type="button"
          onClick={submit}
          disabled={!ready || saving}
          aria-busy={saving}
          className="btn btn-primary disabled:opacity-40"
        >
          {saving
            ? (draftId ? t('Ukládám…', 'Saving…') : t('Zakládám…', 'Creating…'))
            : (draftId ? t('Uložit koncept', 'Save draft') : t('Založit koncept', 'Create draft'))}
        </button>
        {!ready && (
          <span className="text-xs text-muted-foreground">
            {incomplete
              ? t('Některý krok má nevyplněné hodnoty.', 'A step still has empty values.')
              : t('Vyplňte název, cíl a publikum.', 'Fill in the name, goal and audience.')}
          </span>
        )}
        {reach !== null && (
          // Qualified, because an unqualified reach number reads as "people who will get this" —
          // the single most expensive misreading on an authoring screen.
          <span className="text-xs text-muted-foreground">
            {t(
              `Dosah ${reach} — před ověřením souhlasu a potlačením; doručených bude méně.`,
              `Reach ${reach} — before consent checks and suppression; fewer will be delivered.`,
            )}
          </span>
        )}
        </div>
      </footer>
    </div>
  </AuthGuard>
}
