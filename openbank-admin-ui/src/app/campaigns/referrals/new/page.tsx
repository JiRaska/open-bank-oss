// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useMemo, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, Gift } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/**
 * MGM program authoring — marketing self-service for the DRAFT concept (ADR-0310).
 *
 * Deliberately stops at "create draft". The publish button lives on the catalogue page and a
 * different person must press it: the service refuses maker == checker with a 409, and offering
 * both actions to one author would render that four-eyes gate decorative — the same reasoning
 * campaign Studio documents on its own create page.
 *
 * The client-side regexes mirror the service's own validation (ReferralService.createProgram)
 * so an author is told what to fix before a round trip, not after.
 */

// Verified programme keys: QualificationRule.ACCOUNT_OPENED is the only key the referral-service
// translates a wire event into today (WIRE_EVENT_TO_KEY maps "AccountCreated" to it); anything
// else typed here must already be a key the service's fact consumers record.
const QUALIFYING_EVENT_PRESETS = ['account.opened'] as const

const NAME_RE = /^[a-z0-9-]{3,64}$/
const EVENT_RE = /^[a-z0-9_.-]{3,128}$/
const CURRENCIES = ['CZK', 'EUR', 'USD'] as const

function defaultAttributionDate(): string {
  const date = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000)
  return date.toISOString().slice(0, 10)
}

type SubmitState =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'created' }
  | { kind: 'failed'; tone: 'rejected' | 'unauthorized' | 'unreachable'; message?: string }

export default function NewReferralProgramPage() {
  const { t } = useLanguage()
  const [name, setName] = useState('')
  const [version, setVersion] = useState('1')
  const [rewardAmount, setRewardAmount] = useState('')
  const [currency, setCurrency] = useState<string>('CZK')
  const [eventChoice, setEventChoice] = useState<string>(QUALIFYING_EVENT_PRESETS[0])
  const [customEvent, setCustomEvent] = useState('')
  const [attributionDate, setAttributionDate] = useState(defaultAttributionDate)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [submit, setSubmit] = useState<SubmitState>({ kind: 'idle' })

  const qualifyingEvent = eventChoice === 'custom' ? customEvent.trim() : eventChoice

  const validate = (): boolean => {
    const next: Record<string, string> = {}
    if (!NAME_RE.test(name)) {
      next.name = t('3–64 znaků: malá písmena, číslice a pomlčky (stabilní identifikátor do kontraktu).', '3–64 characters: lowercase letters, digits and hyphens (a stable identifier baked into the contract).')
    }
    const parsedVersion = Number(version)
    if (!Number.isInteger(parsedVersion) || parsedVersion <= 0) {
      next.version = t('Verze musí být celé číslo větší než 0.', 'Version must be an integer greater than 0.')
    }
    const parsedReward = Number(rewardAmount)
    if (!Number.isFinite(parsedReward) || parsedReward <= 0) {
      next.rewardAmount = t('Odměna musí být kladné číslo.', 'Reward must be a positive amount.')
    }
    if (!EVENT_RE.test(qualifyingEvent)) {
      next.qualifyingEvent = t('3–128 znaků: malá písmena, číslice, tečky, podtržítka a pomlčky.', '3–128 characters: lowercase letters, digits, dots, underscores and hyphens.')
    }
    if (attributionDate && Number.isNaN(Date.parse(attributionDate))) {
      next.attributionWindowEndsAt = t('Datum není platné.', 'The date is not valid.')
    }
    setErrors(next)
    return Object.keys(next).length === 0
  }

  const payload = useMemo(() => ({
    name: name.trim(),
    version: Number(version),
    rewardAmount: Number(rewardAmount),
    currency,
    qualifyingEvent,
    // ISO datetime; omitted entirely when the field is left empty so the service's own
    // now-plus-30-days default applies, not a value this page guessed.
    ...(attributionDate ? { attributionWindowEndsAt: new Date(`${attributionDate}T23:59:59Z`).toISOString() } : {}),
  }), [name, version, rewardAmount, currency, qualifyingEvent, attributionDate])

  const onSubmit = () => {
    if (submit.kind === 'submitting') return
    if (!validate()) return
    setSubmit({ kind: 'submitting' })
    fetch('/api/referral-programs', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    })
      .then(response => response.json())
      .then((body: { state?: string; message?: string; error?: string }) => {
        if (body.state === 'ok') {
          setSubmit({ kind: 'created' })
          return
        }
        if (body.state === 'rejected') {
          setSubmit({ kind: 'failed', tone: 'rejected', message: body.message })
        } else if (body.state === 'unauthorized') {
          setSubmit({ kind: 'failed', tone: 'unauthorized' })
        } else {
          setSubmit({ kind: 'failed', tone: 'unreachable' })
        }
      })
      .catch(() => setSubmit({ kind: 'failed', tone: 'unreachable' }))
  }

  const field = { display: 'grid', gap: 6 } as const
  const errorText = (key: string) => errors[key]

  return <AuthGuard permission="referral:program:create">
    <div className="space-y-6">
      <PageHeader
        title={t('Nový MGM program', 'New MGM program')}
        subtitle={t('Vytvoří koncept programu doporučení. Publikovat ho musí jiný člověk — operátor než administrátor.', 'Creates a referral-program draft. A different person — an operator other than you — must publish it.')}
        icon={<Gift className="h-6 w-6" />}
      />

      {submit.kind === 'created' ? (
        <section className="rounded-2xl border border-emerald-200 bg-emerald-50 p-6 dark:border-emerald-800 dark:bg-emerald-950" data-testid="referral-draft-created">
          <p className="font-semibold text-emerald-800 dark:text-emerald-200">
            {t('Koncept vytvořen — čeká na publikování operátorem.', 'Draft created — waiting for an operator to publish.')}
          </p>
          <p className="mt-2 text-sm text-emerald-700 dark:text-emerald-300">
            {t('Publikovat ho nemůžete vy, protože jste autorem konceptu; backend to odmítne (maker ≠ checker).', 'You cannot publish it yourself because you authored the draft; the backend refuses that (maker ≠ checker).')}
          </p>
          <Link href="/campaigns/referrals" className="mt-4 inline-flex items-center gap-2 text-sm font-semibold text-emerald-800 dark:text-emerald-200">
            <ArrowLeft className="h-4 w-4" /> {t('Zpět na seznam MGM programů', 'Back to the MGM program list')}
          </Link>
        </section>
      ) : (
        <form
          className="max-w-2xl space-y-5 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-900"
          onSubmit={event => { event.preventDefault(); onSubmit() }}
          noValidate
        >
          <label style={field}>
            <span className="text-sm font-semibold">{t('Název programu (slug)', 'Program name (slug)')}</span>
            <input className="input" value={name} onChange={e => setName(e.target.value)} aria-label={t('Název programu (slug)', 'Program name (slug)')} placeholder="friends-autumn-2026" data-testid="field-name" />
            <span className="text-xs text-muted-foreground">{t('Stabilní identifikátor do kontraktu — po publikaci nelze změnit. 3–64 znaků: malá písmena, číslice, pomlčky.', 'A stable identifier baked into the contract — immutable once published. 3–64 characters: lowercase, digits, hyphens.')}</span>
            {errorText('name') && <span role="alert" className="text-xs text-amber-700 dark:text-amber-300">{errorText('name')}</span>}
          </label>

          <label style={field}>
            <span className="text-sm font-semibold">{t('Verze', 'Version')}</span>
            <input className="input" type="number" min="1" step="1" inputMode="numeric" value={version} onChange={e => setVersion(e.target.value)} aria-label={t('Verze', 'Version')} data-testid="field-version" />
            {errorText('version') && <span role="alert" className="text-xs text-amber-700 dark:text-amber-300">{errorText('version')}</span>}
          </label>

          <div className="grid gap-4 sm:grid-cols-2">
            <label style={field}>
              <span className="text-sm font-semibold">{t('Odměna', 'Reward amount')}</span>
              <input className="input" type="number" min="0" step="0.01" inputMode="decimal" value={rewardAmount} onChange={e => setRewardAmount(e.target.value)} aria-label={t('Odměna', 'Reward amount')} data-testid="field-reward" />
              {errorText('rewardAmount') && <span role="alert" className="text-xs text-amber-700 dark:text-amber-300">{errorText('rewardAmount')}</span>}
            </label>
            <label style={field}>
              <span className="text-sm font-semibold">{t('Měna', 'Currency')}</span>
              <select className="input" value={currency} onChange={e => setCurrency(e.target.value)} aria-label={t('Měna', 'Currency')} data-testid="field-currency">
                {CURRENCIES.map(c => <option key={c} value={c}>{c}</option>)}
              </select>
            </label>
          </div>

          <label style={field}>
            <span className="text-sm font-semibold">{t('Kvalifikační událost', 'Qualifying event')}</span>
            <select className="input" value={eventChoice} onChange={e => setEventChoice(e.target.value)} aria-label={t('Kvalifikační událost', 'Qualifying event')} data-testid="field-event">
              {QUALIFYING_EVENT_PRESETS.map(key => <option key={key} value={key}>{key}</option>)}
              <option value="custom">{t('vlastní klíč…', 'custom key…')}</option>
            </select>
            {eventChoice === 'custom' && (
              <input className="input" value={customEvent} onChange={e => setCustomEvent(e.target.value)} aria-label={t('Vlastní klíč události', 'Custom event key')} placeholder="my.event-key" data-testid="field-event-custom" />
            )}
            <span className="text-xs text-muted-foreground">{t('Klíč musí odpovídat události, kterou referral-service skutečně zaznamenává — neznámý klíč nikdy nekvalifikuje.', 'The key must match an event referral-service actually records — an unknown key never qualifies.')}</span>
            {errorText('qualifyingEvent') && <span role="alert" className="text-xs text-amber-700 dark:text-amber-300">{errorText('qualifyingEvent')}</span>}
          </label>

          <label style={field}>
            <span className="text-sm font-semibold">{t('Platnost atribuce do', 'Attribution window ends')}</span>
            <input className="input" type="date" value={attributionDate} onChange={e => setAttributionDate(e.target.value)} aria-label={t('Platnost atribuce do', 'Attribution window ends')} data-testid="field-attribution" />
            <span className="text-xs text-muted-foreground">{t('Volitelné — když necháte prázdné, použije se výchozích 30 dní od vytvoření.', 'Optional — when left empty, the default of 30 days from creation applies.')}</span>
            {errorText('attributionWindowEndsAt') && <span role="alert" className="text-xs text-amber-700 dark:text-amber-300">{errorText('attributionWindowEndsAt')}</span>}
          </label>

          {submit.kind === 'failed' && (
            <p role="alert" className="rounded-xl bg-amber-50 p-4 text-sm text-amber-800 dark:bg-amber-950 dark:text-amber-200" data-testid="referral-submit-error">
              {submit.tone === 'rejected'
                ? `${t('Koncept byl odmítnut', 'The draft was rejected')}${submit.message ? `: ${submit.message}` : '.'}`
                : submit.tone === 'unauthorized'
                  ? t('Nemáte oprávnění vytvářet MGM programy.', 'You are not allowed to create MGM programs.')
                  : t('Referral-service není dostupný. Zkuste to znovu.', 'Referral-service is unavailable. Try again.')}
            </p>
          )}

          <div className="flex items-center gap-3 pt-2">
            <button type="submit" className="btn btn-primary" style={{ fontSize: 12 }} disabled={submit.kind === 'submitting'} data-testid="referral-submit">
              {submit.kind === 'submitting' ? t('Vytvářím…', 'Creating…') : t('Vytvořit koncept', 'Create draft')}
            </button>
            <Link href="/campaigns/referrals" className="text-sm font-semibold text-muted-foreground">
              {t('Zrušit', 'Cancel')}
            </Link>
          </div>
        </form>
      )}
    </div>
  </AuthGuard>
}
