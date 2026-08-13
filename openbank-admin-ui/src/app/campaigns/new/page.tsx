// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, Clock3, Megaphone, Send, Sparkles, Users } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader } from '@/components/ui'
import {
  JourneyEditor,
  MAX_STEPS,
  type EditorChannel,
  type EditorStep,
} from '@/components/campaigns/JourneyEditor'
import { StepEditor } from '@/components/campaigns/StepEditor'
import { CampaignExperiencePreview } from '@/components/campaigns/CampaignExperiencePreview'
import { CampaignLaunchReadiness } from '@/components/campaigns/CampaignLaunchReadiness'

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

type EntryMode = 'MANUAL' | 'SCHEDULE' | 'TRIGGER'

/** Mirrors the service's catalogue; the service rejects anything not in its own copy. */
const TEMPLATES: Record<string, string[]> = {
  MARKETING_PRODUCT_OFFER: ['offerTitle', 'offerText', 'ctaText'],
  // One variable, and that is the channel's rule rather than a simplification: a push renders its
  // title plus a fixed generic body, so there is nowhere for offer copy to go (#1182).
  MARKETING_PRODUCT_OFFER_PUSH: ['offerTitle'],
  MARKETING_PRODUCT_OFFER_BANNER: ['offerTitle', 'offerText', 'ctaText'],
  MARKETING_PRODUCT_OFFER_CAROUSEL: ['offerTitle', 'offerText', 'ctaText'],
  MARKETING_PRODUCT_OFFER_PRODUCT_FEED: ['offerTitle', 'offerText', 'ctaText'],
  MARKETING_PRODUCT_OFFER_REWARDS_HUB: ['offerTitle', 'offerText', 'ctaText'],
}

/** Which channel each template renders on. The service refuses a step whose two disagree. */
const TEMPLATE_CHANNEL: Record<string, EditorChannel> = {
  MARKETING_PRODUCT_OFFER: 'EMAIL',
  MARKETING_PRODUCT_OFFER_PUSH: 'PUSH',
  MARKETING_PRODUCT_OFFER_BANNER: 'BANNER',
  MARKETING_PRODUCT_OFFER_CAROUSEL: 'BANNER',
  MARKETING_PRODUCT_OFFER_PRODUCT_FEED: 'BANNER',
  MARKETING_PRODUCT_OFFER_REWARDS_HUB: 'BANNER',
}

const newStep = (): EditorStep => ({
  // The studio starts with the primary owned surface: the bank app. E-mail remains a supported
  // channel, but leading an app-first campaign with it made the canvas teach the wrong product.
  template: 'MARKETING_PRODUCT_OFFER_PUSH',
  channel: 'PUSH',
  variables: {},
  delaySeconds: 0,
  mobileDestination: 'HOME',
})

export default function NewCampaignPage() {
  const { t } = useLanguage()
  const router = useRouter()

  const [name, setName] = useState('')
  const [goal, setGoal] = useState('')
  const [segment, setSegment] = useState('')
  const [segments, setSegments] = useState<Segment[]>([])
  const [cadences, setCadences] = useState<Cadence[]>([])
  const [triggers, setTriggers] = useState<CampaignTrigger[]>([])
  const [entryMode, setEntryMode] = useState<EntryMode>('MANUAL')
  const [cadence, setCadence] = useState('')
  const [trigger, setTrigger] = useState('')
  const [entryUnavailable, setEntryUnavailable] = useState(false)
  const [steps, setSteps] = useState<EditorStep[]>([newStep()])
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

  const templateLabels: Record<string, string> = {
    MARKETING_PRODUCT_OFFER: t('Nabídka produktu', 'Product offer'),
    MARKETING_PRODUCT_OFFER_PUSH: t('Nabídka produktu', 'Product offer'),
    MARKETING_PRODUCT_OFFER_BANNER: t('Nabídka v banneru', 'Banner offer'),
    MARKETING_PRODUCT_OFFER_CAROUSEL: t('Nabídka v carouselu', 'Carousel offer'),
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
    fetch('/api/segments')
      .then(r => r.json())
      .then((d: { items: Segment[]; state: string }) => {
        if (d.state === 'ok') setSegments(d.items ?? [])
      })
      .catch(() => undefined)
  }, [])

  // Entry catalogues come from campaign-service rather than a second hard-coded list: an event
  // whose consumer was removed must disappear from Studio, and a cadence may never become a raw
  // cron field that looks valid while doing something different in Temporal.
  useEffect(() => {
    Promise.all([
      fetch('/api/campaigns/cadences').then(r => r.json()),
      fetch('/api/campaigns/triggers').then(r => r.json()),
    ])
      .then(([cadenceResponse, triggerResponse]: [
        { items?: Cadence[]; state?: string },
        { items?: CampaignTrigger[]; state?: string },
      ]) => {
        if (cadenceResponse.state === 'ok') setCadences(cadenceResponse.items ?? [])
        if (triggerResponse.state === 'ok') setTriggers(triggerResponse.items ?? [])
        if (cadenceResponse.state !== 'ok' || triggerResponse.state !== 'ok') setEntryUnavailable(true)
      })
      .catch(() => setEntryUnavailable(true))
  }, [])

  // The reach is the segment's own preview, run by the service — the same evaluation enrolment runs.
  // A number computed here from a different query would agree with the send only by luck.
  const previewReach = (ref: string) => {
    setReach(null)
    const [segName, segVersion] = ref.split('@')
    if (!segName) return
    fetch(`/api/segments/${encodeURIComponent(segName)}/${encodeURIComponent(segVersion)}/preview`)
      .then(r => r.json())
      .then((d: { size?: number; state: string }) => {
        if (d.state === 'ok') setReach(d.size ?? 0)
      })
      .catch(() => undefined)
  }

  const updateStep = (i: number, next: EditorStep) =>
    setSteps(prev => prev.map((s, k) => (k === i ? next : s)))

  const addStep = () =>
    setSteps(prev => {
      if (prev.length >= MAX_STEPS) return prev
      setSelected(prev.length)
      return [...prev, {
        ...newStep(),
        ...(contentExperiment ? { variantBVariables: {} } : {}),
      }]
    })

  const removeStep = (i: number) =>
    setSteps(prev => {
      const next = prev.filter((_, k) => k !== i)
      setSelected(null)
      return next
    })

  const incomplete = steps.some(s =>
    (TEMPLATES[s.template] ?? []).some(v => !(s.variables[v] ?? '').trim()) ||
    (contentExperiment && (TEMPLATES[s.variantBTemplate ?? s.template] ?? [])
      .some(v => !(s.variantBVariables?.[v] ?? '').trim())),
  )
  const entryConfigured =
    entryMode === 'MANUAL' ||
    (entryMode === 'SCHEDULE' && cadence !== '') ||
    (entryMode === 'TRIGGER' && trigger !== '')
  const ready = name.trim() !== '' && goal.trim() !== '' && segment !== '' && steps.length > 0 &&
    !incomplete && entryConfigured && (!contentExperiment || conversionRule !== null)

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
    setSaving(true)
    setError(null)
    const [segName, segVersion] = segment.split('@')
    fetch('/api/campaigns', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        name: name.trim(),
        goal: goal.trim(),
        segmentName: segName,
        segmentVersion: Number(segVersion),
        ...(stopAfter !== null ? { stopCondition: { maxSendsPerParty: stopAfter } } : {}),
        ...(conversionRule ? { conversionRule } : {}),
        ...(holdoutPercent > 0 ? { holdoutPercent } : {}),
        ...(entryMode === 'SCHEDULE' && cadence ? { schedule: { cadence } } : {}),
        ...(entryMode === 'TRIGGER' && trigger ? { trigger } : {}),
        steps: steps.map((s, i) => ({
          order: i + 1,
          template: s.template,
          channel: s.channel,
          ...(s.condition ? { condition: s.condition } : {}),
          variables: s.variables,
          ...(contentExperiment ? { variantBVariables: s.variantBVariables ?? {} } : {}),
          ...(contentExperiment && s.variantBTemplate ? { variantBTemplate: s.variantBTemplate } : {}),
          ...(contentExperiment && s.variantBChannel ? { variantBChannel: s.variantBChannel } : {}),
          ...(contentExperiment && s.variantBDelaySeconds !== undefined ? { variantBDelaySeconds: s.variantBDelaySeconds } : {}),
          ...(s.fallbackToPush ? { fallbackToPush: true } : {}),
          ...(s.mobileDestination ? { mobileDestination: s.mobileDestination } : {}),
          ...(s.inAppSurface ? { inAppSurface: s.inAppSurface } : {}),
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
              ? t('Nemáte oprávnění zakládat kampaně.', 'You are not permitted to create campaigns.')
              : t('Campaign-service neodpovídá.', 'Campaign-service is not responding.')),
        )
      })
      .catch(() => setError(t('Campaign-service neodpovídá.', 'Campaign-service is not responding.')))
      .finally(() => setSaving(false))
  }

  return (
    <div className="campaign-composer">
      <header className="campaign-composer-hero">
        <Link href="/campaigns" className="campaign-composer-back">
          <ArrowLeft className="h-4 w-4" /> {t('Kampaně', 'Campaigns')}
        </Link>
        <div className="campaign-composer-hero-main">
          <div>
            <p className="campaign-composer-eyebrow"><Sparkles className="h-3.5 w-3.5" /> {t('Campaign studio', 'Campaign studio')}</p>
            <PageHeader
              title={t('Nová kampaň', 'New campaign')}
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
            className="input w-full"
            style={{ fontSize: '1.5rem', fontWeight: 600, padding: '0.7rem 0.9rem' }}
            placeholder={t('Pojmenujte kampaň', 'Name this campaign')}
            value={name}
            onChange={e => setName(e.target.value)}
          />
          <input
            id="c-goal"
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
          <div className="grid gap-2 sm:grid-cols-3">
            <button
              type="button"
              data-entry-pick="MANUAL"
              data-selected={entryMode === 'MANUAL' ? 'true' : 'false'}
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

      <section className="campaign-journey-workbench">
        <div className="campaign-workbench-heading">
          <div>
            <p className="campaign-composer-eyebrow"><Sparkles className="h-3.5 w-3.5" /> {t('Journey composer', 'Journey composer')}</p>
            <h2>{t('Cesta, kterou lidé skutečně zažijí', 'The journey people will actually experience')}</h2>
            <p>{t('Začněte mobilním momentem. Push otevře bezpečný deep link a obsah pokračuje uvnitř aplikace.', 'Start with a mobile moment. Push opens a secure deep link and the experience continues inside the app.')}</p>
          </div>
          <span className="campaign-workbench-status"><span /> {steps.length}/{MAX_STEPS} {t('kroků', 'steps')}</span>
        </div>
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
            templates={TEMPLATES}
            templateChannel={TEMPLATE_CHANNEL}
            templateLabels={templateLabels}
            variableLabels={variableLabels}
            contentExperiment={contentExperiment}
            onChange={next => updateStep(selected, next)}
            onClose={() => setSelected(null)}
          />
        )}

        </div>

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
          <p>{t('Koncept se zatím nikomu neposílá.', 'A draft does not send anything yet.')}</p>
          <span>{t('Po kontrole jej aktivuje jiný oprávněný člověk.', 'A different authorised person activates it after review.')}</span>
        </div>
        <div className="campaign-composer-footer-actions">
        <button
          onClick={submit}
          disabled={!ready || saving}
          className="btn btn-primary disabled:opacity-40"
        >
          {saving ? t('Zakládám…', 'Creating…') : t('Založit koncept', 'Create draft')}
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
  )
}
