// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, Megaphone } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader } from '@/components/ui'
import {
  JourneyEditor,
  MAX_STEPS,
  type EditorChannel,
  type EditorStep,
} from '@/components/campaigns/JourneyEditor'
import { StepEditor } from '@/components/campaigns/StepEditor'

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

/** Mirrors the service's catalogue; the service rejects anything not in its own copy. */
const TEMPLATES: Record<string, string[]> = {
  MARKETING_PRODUCT_OFFER: ['offerTitle', 'offerText', 'ctaText'],
  // One variable, and that is the channel's rule rather than a simplification: a push renders its
  // title plus a fixed generic body, so there is nowhere for offer copy to go (#1182).
  MARKETING_PRODUCT_OFFER_PUSH: ['offerTitle'],
}

/** Which channel each template renders on. The service refuses a step whose two disagree. */
const TEMPLATE_CHANNEL: Record<string, EditorChannel> = {
  MARKETING_PRODUCT_OFFER: 'EMAIL',
  MARKETING_PRODUCT_OFFER_PUSH: 'PUSH',
}

const newStep = (): EditorStep => ({
  template: 'MARKETING_PRODUCT_OFFER',
  channel: 'EMAIL',
  variables: {},
  delaySeconds: 0,
})

export default function NewCampaignPage() {
  const { t } = useLanguage()
  const router = useRouter()

  const [name, setName] = useState('')
  const [goal, setGoal] = useState('')
  const [segment, setSegment] = useState('')
  const [segments, setSegments] = useState<Segment[]>([])
  const [steps, setSteps] = useState<EditorStep[]>([newStep()])
  const [selected, setSelected] = useState<number | null>(0)
  const [reach, setReach] = useState<number | null>(null)
  // Null = no cap, which is the service's own default (absent stopCondition runs every step).
  const [stopAfter, setStopAfter] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const templateLabels: Record<string, string> = {
    MARKETING_PRODUCT_OFFER: t('Nabídka produktu', 'Product offer'),
    MARKETING_PRODUCT_OFFER_PUSH: t('Nabídka produktu', 'Product offer'),
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
      return [...prev, newStep()]
    })

  const removeStep = (i: number) =>
    setSteps(prev => {
      const next = prev.filter((_, k) => k !== i)
      setSelected(null)
      return next
    })

  const incomplete = steps.some(s =>
    (TEMPLATES[s.template] ?? []).some(v => !(s.variables[v] ?? '').trim()),
  )
  const ready = name.trim() !== '' && goal.trim() !== '' && segment !== '' && steps.length > 0 && !incomplete

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
        steps: steps.map((s, i) => ({
          order: i + 1,
          template: s.template,
          channel: s.channel,
          ...(s.condition ? { condition: s.condition } : {}),
          variables: s.variables,
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
    <div className="space-y-6">
      <Link href="/campaigns" className="inline-flex items-center gap-1 text-sm hover:underline">
        <ArrowLeft className="h-4 w-4" /> {t('Kampaně', 'Campaigns')}
      </Link>

      <PageHeader
        title={t('Nová kampaň', 'New campaign')}
        subtitle={t(
          'Sestavte cestu, kterou lidé projdou. Spustit ji musí někdo jiný — to je smysl schvalování ve dvou.',
          'Assemble the journey people will take. Someone else activates it — that is the point of the four-eyes gate.',
        )}
        icon={<Megaphone className="h-6 w-6" />}
      />

      {/* A marketer names a campaign and picks who gets it. Both were `<label>` + bare box, which is
          how a database table looks, not how a campaign brief does. The name behaves like a document
          title; the audience is a set of tiles carrying its plain-language rule and its reach, which
          is the choice being made — a dropdown hides exactly the number the choice turns on. */}
      <section className="max-w-3xl space-y-8">
        <div>
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

        <div>
          <h2 className="text-sm font-semibold" style={{ marginBottom: '0.75rem' }}>
            {t('Komu to půjde', 'Who gets it')}
          </h2>
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
      </section>

      <section className="space-y-3">
        <h2 className="text-sm font-semibold">{t('Cesta', 'The journey')}</h2>
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
            onChange={next => updateStep(selected, next)}
            onClose={() => setSelected(null)}
          />
        )}

        </div>

        {/* The one contact rule a campaign DOES own. The platform-wide ones below are read-only; this
            cap is per-campaign by design (ADR-0200 D1), so it is offered here rather than described. */}
        <div className="max-w-2xl rounded-lg border p-3 space-y-2">
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

        {/* Read-only on purpose: the contact policy is a single enforcement point, and a per-campaign
            override here would make that point decorative (ADR-0219 D4, ADR-0221 D1 step 4). */}
        <div className="max-w-2xl rounded-lg border p-3 text-xs text-muted-foreground">
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

      <div className="flex items-center gap-3">
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
    </div>
  )
}
