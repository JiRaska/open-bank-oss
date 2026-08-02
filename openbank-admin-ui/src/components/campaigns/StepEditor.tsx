// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { EditorStep } from '@/components/campaigns/JourneyEditor'

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
  templateLabels,
  onChange,
  onClose,
}: {
  index: number
  step: EditorStep
  /** template id → the variables it declares. Mirrors the service's catalogue. */
  templates: Record<string, string[]>
  templateLabels: Record<string, string>
  onChange: (next: EditorStep) => void
  onClose: () => void
}) {
  const { t } = useLanguage()
  const declared = templates[step.template] ?? []
  const missing = declared.filter(v => !(step.variables[v] ?? '').trim())
  const field = 'w-full rounded-md border bg-transparent px-3 py-1.5 text-sm'

  const setDelayDays = (raw: string) => {
    const days = Math.max(0, Number(raw) || 0)
    onChange({ ...step, delaySeconds: days * 86400 })
  }

  return (
    <div className="rounded-xl border p-4 space-y-4" data-step-editor={index}>
      <div className="flex items-baseline justify-between">
        <h3 className="text-sm font-semibold">
          {t('Krok', 'Step')} {index + 1}
        </h3>
        <button onClick={onClose} className="text-xs text-muted-foreground hover:underline">
          {t('Hotovo', 'Done')}
        </button>
      </div>

      <div className="space-y-1">
        <label htmlFor={`tpl-${index}`} className="text-sm font-medium">
          {t('Co se pošle', 'What gets sent')}
        </label>
        <select
          id={`tpl-${index}`}
          className={field}
          value={step.template}
          onChange={e => onChange({ ...step, template: e.target.value, variables: {} })}
        >
          {Object.keys(templates).map(tpl => (
            <option key={tpl} value={tpl}>
              {templateLabels[tpl] ?? tpl}
            </option>
          ))}
        </select>
        {/* Said out loud so the absence of a rich-text box reads as a rule, not a missing feature. */}
        <p className="text-xs text-muted-foreground">
          {t(
            'Text e-mailu je v šabloně. Tady se vyplňují jen její pojmenované hodnoty.',
            'The email copy lives in the template. Only its named values are filled in here.',
          )}
        </p>
      </div>

      {declared.map(v => (
        <div key={v} className="space-y-1">
          <label htmlFor={`var-${index}-${v}`} className="text-sm font-medium">
            {v}
          </label>
          <input
            id={`var-${index}-${v}`}
            className={field}
            value={step.variables[v] ?? ''}
            onChange={e => onChange({ ...step, variables: { ...step.variables, [v]: e.target.value } })}
          />
        </div>
      ))}

      <div className="space-y-1">
        <label htmlFor={`delay-${index}`} className="text-sm font-medium">
          {t('Poslat po', 'Send after')}
        </label>
        <div className="flex items-center gap-2">
          <input
            id={`delay-${index}`}
            type="number"
            min="0"
            className={`${field} w-28`}
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
          {t('Ještě chybí', 'Still missing')}: {missing.join(', ')}
        </p>
      )}
    </div>
  )
}
