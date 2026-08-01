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

/**
 * Campaign Studio — authoring (ADR-0221 D1).
 *
 * The form IS the domain model: goal, a picker over versioned segment artifacts, steps composed
 * from the template catalogue, the platform contact rules shown read-only, and a summary that
 * submits for approval. There is no free-text body field anywhere, because the engine rejects one
 * by construction (ADR-0176 D4) — a textarea here would be a control the service would refuse.
 *
 * What this screen deliberately does NOT do: activate. Submission puts the campaign in
 * PENDING_APPROVAL, and the checker is a different person acting from the campaign's own page.
 * Offering both buttons to one author would render the four-eyes gate as decoration.
 */

interface Segment {
  name: string
  version: number
  rules: string[]
}

/** Mirrors the service's catalogue; the service rejects anything not in its own copy. */
const TEMPLATES: Record<string, string[]> = {
  MARKETING_PRODUCT_OFFER: ['offerTitle', 'offerText', 'ctaText'],
}

export default function NewCampaignPage() {
  const { t, language } = useLanguage()
  const router = useRouter()

  const [name, setName] = useState('')
  const [goal, setGoal] = useState('')
  const [segment, setSegment] = useState('')
  const [segments, setSegments] = useState<Segment[]>([])
  const [template, setTemplate] = useState('MARKETING_PRODUCT_OFFER')
  const [variables, setVariables] = useState<Record<string, string>>({})
  const [delayHours, setDelayHours] = useState('0')
  const [reach, setReach] = useState<number | null>(null)
  const [reachState, setReachState] = useState<string>('')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    fetch('/api/segments')
      .then(r => r.json())
      .then((d: { items: Segment[]; state: string }) => {
        if (d.state === 'ok') setSegments(d.items ?? [])
      })
      .catch(() => undefined)
  }, [])

  // The reach is the segment's own preview, run by the service — the same evaluation enrolment
  // runs. A number computed here from a different query would agree with the send only by luck.
  const previewReach = (ref: string) => {
    setReach(null)
    setReachState('')
    const [segName, segVersion] = ref.split('@')
    if (!segName) return
    setReachState('loading')
    fetch(`/api/segments/${encodeURIComponent(segName)}/${encodeURIComponent(segVersion)}/preview`)
      .then(r => r.json())
      .then((d: { size?: number; state: string }) => {
        setReachState(d.state)
        if (d.state === 'ok') setReach(d.size ?? 0)
      })
      .catch(() => setReachState('unreachable'))
  }

  const declared = TEMPLATES[template] ?? []
  const missing = declared.filter(v => !(variables[v] ?? '').trim())
  const ready = name.trim() !== '' && goal.trim() !== '' && segment !== '' && missing.length === 0

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
        steps: [
          {
            order: 1,
            template,
            variables,
            delaySeconds: Math.max(0, Number(delayHours) || 0) * 3600,
          },
        ],
      }),
    })
      .then(r => r.json())
      .then((d: { state: string; campaign?: { id: string }; error?: string }) => {
        if (d.state === 'ok' && d.campaign) {
          router.push(`/campaigns/${d.campaign.id}`)
          return
        }
        // The service's own message names what to change — an unknown template, an undeclared
        // variable. Replacing it with "could not create" would remove the only actionable part.
        setError(
          d.error ??
            (d.state === 'forbidden'
              ? t('Nemáte oprávnění zakládat kampaně.', 'You are not permitted to create campaigns.')
              : t('Campaign-service neodpovídá.', 'Campaign-service is not responding.')),
        )
      })
      .catch(() => setError(t('Campaign-service neodpovídá.', 'Campaign-service is not responding.')))
      .finally(() => setSaving(false))
  }

  const field = 'w-full rounded-md border bg-transparent px-3 py-1.5 text-sm'

  return (
    <div className="space-y-6">
      <Link href="/campaigns" className="inline-flex items-center gap-1 text-sm hover:underline">
        <ArrowLeft className="h-4 w-4" /> {t('Kampaně', 'Campaigns')}
      </Link>

      <PageHeader
        title={t('Nová kampaň', 'New campaign')}
        subtitle={t(
          'Založí koncept. Spustit ji musí někdo jiný — to je ten smysl schvalování ve dvou.',
          'Creates a draft. Someone else activates it — that is the point of the four-eyes gate.',
        )}
        icon={<Megaphone className="h-6 w-6" />}
      />

      <section className="max-w-2xl space-y-4">
        <div className="space-y-1">
          <label htmlFor="c-name" className="text-sm font-medium">{t('Název', 'Name')}</label>
          <input id="c-name" className={field} value={name} onChange={e => setName(e.target.value)} />
        </div>

        <div className="space-y-1">
          <label htmlFor="c-goal" className="text-sm font-medium">{t('Cíl', 'Goal')}</label>
          <input id="c-goal" className={field} value={goal} onChange={e => setGoal(e.target.value)} />
        </div>

        <div className="space-y-1">
          <label htmlFor="c-segment" className="text-sm font-medium">{t('Publikum', 'Audience')}</label>
          <select
            id="c-segment"
            className={field}
            value={segment}
            onChange={e => {
              setSegment(e.target.value)
              previewReach(e.target.value)
            }}
          >
            <option value="">{t('Vyberte segment…', 'Choose a segment…')}</option>
            {segments.map(s => (
              <option key={`${s.name}@${s.version}`} value={`${s.name}@${s.version}`}>
                {s.name} v{s.version} — {s.rules.join('; ')}
              </option>
            ))}
          </select>
          {/* Segments are code, not something this screen can author (ADR-0201 D1). Saying so here
              is cheaper than letting someone hunt for an "add segment" button that will never exist. */}
          <p className="text-xs text-muted-foreground">
            {t(
              'Segmenty jsou definované v kódu a verzované. Nový segment je pull request, ne akce v UI.',
              'Segments are defined in code and versioned. A new segment is a pull request, not a UI action.',
            )}
          </p>
          {reachState === 'loading' && (
            <p className="text-xs text-muted-foreground">{t('Počítám dosah…', 'Counting reach…')}</p>
          )}
          {reachState === 'ok' && reach !== null && (
            <p className="text-xs">
              {t('Aktuální dosah', 'Current reach')}:{' '}
              <strong>{reach.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong>{' '}
              {/* Stated, because reach is a moving number and a stale one drives a wrong decision. */}
              <span className="text-muted-foreground">
                {t(
                  '— před ověřením souhlasu a potlačením; skutečný počet bude nižší.',
                  '— before consent checks and suppression; the sent-to count will be lower.',
                )}
              </span>
            </p>
          )}
          {reachState !== '' && reachState !== 'ok' && reachState !== 'loading' && (
            <p className="text-xs text-amber-600">
              {t('Dosah se nepodařilo spočítat.', 'Reach could not be computed.')}
            </p>
          )}
        </div>

        <div className="space-y-1">
          <label htmlFor="c-template" className="text-sm font-medium">{t('Šablona', 'Template')}</label>
          <select
            id="c-template"
            className={field}
            value={template}
            onChange={e => {
              setTemplate(e.target.value)
              setVariables({})
            }}
          >
            {Object.keys(TEMPLATES).map(tpl => (
              <option key={tpl} value={tpl}>{tpl}</option>
            ))}
          </select>
        </div>

        {declared.map(v => (
          <div key={v} className="space-y-1">
            <label htmlFor={`var-${v}`} className="text-sm font-medium">{v}</label>
            <input
              id={`var-${v}`}
              className={field}
              value={variables[v] ?? ''}
              onChange={e => setVariables(prev => ({ ...prev, [v]: e.target.value }))}
            />
          </div>
        ))}

        <div className="space-y-1">
          <label htmlFor="c-delay" className="text-sm font-medium">
            {t('Zpoždění kroku (hodiny)', 'Step delay (hours)')}
          </label>
          <input
            id="c-delay"
            type="number"
            min="0"
            className={field}
            value={delayHours}
            onChange={e => setDelayHours(e.target.value)}
          />
        </div>

        {/* Read-only on purpose: the contact policy is a single enforcement point, and a per-campaign
            override here would make that point decorative (ADR-0219 D4, ADR-0221 D1 step 4). */}
        <div className="rounded-lg border p-3 text-xs text-muted-foreground">
          <p className="font-medium text-foreground">{t('Pravidla kontaktu', 'Contact rules')}</p>
          <p>
            {t(
              'Tiché hodiny 21:00–8:00, frekvenční strop a potlačení platí pro všechny kampaně a odsud se měnit nedají.',
              'Quiet hours 21:00–08:00, the frequency cap and suppression apply to every campaign and cannot be changed here.',
            )}
          </p>
        </div>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <div className="flex items-center gap-3">
          <button
            onClick={submit}
            disabled={!ready || saving}
            className="rounded-md border px-3 py-1.5 text-sm disabled:opacity-40"
          >
            {saving ? t('Zakládám…', 'Creating…') : t('Založit koncept', 'Create draft')}
          </button>
          {!ready && missing.length > 0 && (
            <span className="text-xs text-muted-foreground">
              {t('Chybí', 'Missing')}: {missing.join(', ')}
            </span>
          )}
        </div>
      </section>
    </div>
  )
}
