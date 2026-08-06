// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { EditorChannel, EditorStep } from '@/components/campaigns/JourneyEditor'

/**
 * Edits the step selected on the canvas.
 *
 * It only ever shows the fields the chosen template DECLARES. That is not a convenience: ADR-0176 D4
 * and the service's TemplateCatalog make a campaign supply values and never body text, so a
 * free-text box here would be a control the service refuses — the worst kind, because it looks like
 * it works until you submit.
 *
 * The delay is entered in the unit a person thinks in. `delaySeconds` is the engine's field, and
 * asking a marketer for 172800 was one of the clearest symptoms of a screen built for the API.
 */

export function StepEditor({
  index,
  step,
  templates,
  templateChannel,
  templateLabels,
  variableLabels,
  onChange,
  onClose,
  attached = false,
}: {
  index: number
  step: EditorStep
  /** template id → the variables it declares. Mirrors the service's catalogue. */
  templates: Record<string, string[]>
  /** template id → the channel it renders on. The service refuses a mismatch. */
  templateChannel: Record<string, EditorChannel>
  templateLabels: Record<string, string>
  /**
   * variable id → what to call it, and what a filled-in one looks like.
   *
   * `offerTitle` is the engine's name for the field. Printing it as the label made a marketer read
   * an API contract; the example matters as much as the name, because "what goes in here" is the
   * actual question and a bare box does not answer it.
   */
  variableLabels: Record<string, { label: string; example: string }>
  onChange: (next: EditorStep) => void
  onClose: () => void
  /**
   * Rendered as a continuation of the canvas rather than a card of its own.
   *
   * Separated, it read as a block that had fallen off the page: a narrower box under a full-width
   * canvas, with nothing tying it to the node it edits. The relationship is structural — this panel
   * IS the selected node, opened — so it is expressed with layout rather than with a caption saying
   * so.
   */
  attached?: boolean
}) {
  const { t } = useLanguage()
  const declared = templates[step.template] ?? []
  const missing = declared.filter(v => !(step.variables[v] ?? '').trim())
  // `.input` is the console's own field style — hover, focus ring, sizing — and I had hand-rolled a
  // worse copy of it. Reinventing a house primitive is the same failure ADR-0208 D2 names for colour.
  const field = 'input w-full'

  const setDelayDays = (raw: string) => {
    const days = Math.max(0, Number(raw) || 0)
    onChange({ ...step, delaySeconds: days * 86400 })
  }

  return (
    <div
      className={
        attached
          ? 'border-x border-b rounded-b-xl p-5 space-y-5'
          : 'rounded-xl border p-4 space-y-4'
      }
      data-step-editor={index}
    >
      <div className="flex items-baseline justify-between">
        <h3 className="text-sm font-semibold">
          {t('Krok', 'Step')} {index + 1}
        </h3>
        <button onClick={onClose} className="text-xs text-muted-foreground hover:underline">
          {t('Hotovo', 'Done')}
        </button>
      </div>

      {/* Channel first, because it changes what the rest of the panel can offer. A push carries a
          title and nothing else — notification-service renders a fixed generic body so customer
          content never reaches an APNs payload (#1182) — so offering an email's body fields on a
          push step would promise something the platform refuses to deliver. */}
      <div className="space-y-1.5">
        <span className="text-sm font-medium">{t('Kanál', 'Channel')}</span>
        <div className="flex gap-2">
          {(['EMAIL', 'PUSH'] as EditorChannel[]).map(c => {
            const first = Object.keys(templates).find(tpl => templateChannel[tpl] === c)
            const active = step.channel === c
            return (
              <button
                key={c}
                type="button"
                data-channel-pick={c}
                data-selected={active ? 'true' : 'false'}
                disabled={!first}
                onClick={() => first && onChange({ ...step, channel: c, template: first, variables: {} })}
                // `.btn` again rather than a hand-rolled box — the third time tonight that a
                // house primitive existed and a worse copy was written next to it. `py-1.5` is not
                // even generated in this build, so the copy rendered cramped.
                className="btn disabled:opacity-40"
                style={
                  active
                    ? { borderColor: 'var(--accent)', boxShadow: '0 0 0 1px var(--accent)' }
                    : undefined
                }
              >
                {c === 'EMAIL' ? t('E-mail', 'Email') : t('Push do aplikace', 'App push')}
              </button>
            )
          })}
        </div>
        {step.channel === 'PUSH' && (
          <p className="text-xs text-muted-foreground">
            {t(
              'Push nese jen titulek. Nabídku si člověk přečte v aplikaci po klepnutí — do notifikace se osobní obsah nedává.',
              'A push carries the headline only. The offer is read in the app after the tap — personal content never goes into a notification.',
            )}
          </p>
        )}
      </div>

      <div className="space-y-1.5">
        <label htmlFor={`tpl-${index}`} className="text-sm font-medium">
          {t('Co se pošle', 'What gets sent')}
        </label>
        <select
          id={`tpl-${index}`}
          className={field}
          value={step.template}
          onChange={e => onChange({ ...step, template: e.target.value, variables: {} })}
        >
          {Object.keys(templates).filter(tpl => templateChannel[tpl] === step.channel).map(tpl => (
            <option key={tpl} value={tpl}>
              {templateLabels[tpl] ?? tpl}
            </option>
          ))}
        </select>
        {/* Said out loud so the absence of a rich-text box reads as a rule, not a missing feature. */}
        <p className="text-xs text-muted-foreground">
          {step.channel === 'PUSH'
            ? t(
                'Šablona notifikace je pevná. Tady se vyplňuje jen její titulek.',
                'The notification template is fixed. Only its headline is filled in here.',
              )
            : t(
                'Text e-mailu je v šabloně. Tady se vyplňují jen její pojmenované hodnoty.',
                'The email copy lives in the template. Only its named values are filled in here.',
              )}
        </p>
      </div>

      {declared.map(v => (
        <div key={v} className="space-y-1.5">
          <label htmlFor={`var-${index}-${v}`} className="text-sm font-medium">
            {variableLabels[v]?.label ?? v}
          </label>
          <input
            id={`var-${index}-${v}`}
            className={field}
            placeholder={variableLabels[v]?.example ?? ''}
            value={step.variables[v] ?? ''}
            onChange={e => onChange({ ...step, variables: { ...step.variables, [v]: e.target.value } })}
          />
        </div>
      ))}

      <div className="space-y-1.5">
        <label htmlFor={`delay-${index}`} className="text-sm font-medium">
          {t('Poslat po', 'Send after')}
        </label>
        <div className="flex items-center gap-2">
          <input
            id={`delay-${index}`}
            type="number"
            min="0"
            className={field}
            style={{ width: '5.5rem' }}
            value={Math.round(step.delaySeconds / 86400)}
            onChange={e => setDelayDays(e.target.value)}
          />
          <span className="text-sm text-muted-foreground">
            {t('dnech od předchozího kroku (0 = ihned)', 'days from the previous step (0 = immediately)')}
          </span>
        </div>
      </div>

      {missing.length > 0 && (
        <p className="text-xs text-amber-600">
          {t('Ještě chybí', 'Still missing')}:{' '}
          {missing.map(v => variableLabels[v]?.label ?? v).join(', ')}
        </p>
      )}
    </div>
  )
}
