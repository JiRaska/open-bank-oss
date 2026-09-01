// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { EditorChannel, EditorCondition, EditorInAppSurface, EditorStep } from '@/components/campaigns/JourneyEditor'

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
  templateSurface,
  templateLabels,
  variableLabels,
  contentExperiment = false,
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
  /** Authenticated app surface → its reviewed banner template, served by campaign-service. */
  templateSurface: Partial<Record<EditorInAppSurface, string>>
  templateLabels: Record<string, string>
  /**
   * variable id → what to call it, and what a filled-in one looks like.
   *
   * `offerTitle` is the engine's name for the field. Printing it as the label made a marketer read
   * an API contract; the example matters as much as the name, because "what goes in here" is the
   * actual question and a bare box does not answer it.
   */
  variableLabels: Record<string, { label: string; example: string }>
  /** When enabled, every step has a B-arm copy to compare against its original A-arm values. */
  contentExperiment?: boolean
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
  const variantBTemplate = step.variantBTemplate ?? step.template
  const variantBChannel = step.variantBChannel ?? step.channel
  const variantBDeclared = templates[variantBTemplate] ?? []
  const firstSurface = Object.keys(templateSurface)[0] as EditorInAppSurface | undefined
  const selectedSurface = step.inAppSurface ?? firstSurface
  const templateForSurface = (surface: EditorInAppSurface | undefined) => surface ? templateSurface[surface] : undefined
  const surfaceLabel = (surface: EditorInAppSurface) => {
    switch (surface) {
      case 'HOME_BANNER': return t('Banner na domovské obrazovce', 'Home banner')
      case 'HOME_CAROUSEL': return t('Carousel na domovské obrazovce', 'Home carousel')
      case 'STORIES': return t('Příběhy v aplikaci', 'In-app stories')
      case 'PRODUCT_FEED': return t('Feed produktů', 'Product feed')
      case 'REWARDS_HUB': return t('Centrum odměn', 'Rewards hub')
    }
  }
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
        <button type="button" onClick={onClose} className="text-xs text-muted-foreground hover:underline">
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
          {(['EMAIL', 'PUSH', 'BANNER'] as EditorChannel[]).map(c => {
            const first = c === 'BANNER'
              ? templateForSurface(selectedSurface)
              : Object.keys(templates).find(tpl => templateChannel[tpl] === c)
            const active = step.channel === c
            return (
              <button
                key={c}
                type="button"
                data-channel-pick={c}
                data-selected={active ? 'true' : 'false'}
                disabled={!first}
                onClick={() => first && onChange({
                  ...step,
                  channel: c,
                  template: first,
                  variables: {},
                  ...(c === 'PUSH' || c === 'BANNER'
                    ? { fallbackToPush: false, mobileDestination: step.mobileDestination ?? 'HOME' }
                    : { mobileDestination: undefined }),
                  ...(c === 'BANNER' && selectedSurface ? { inAppSurface: selectedSurface } : { inAppSurface: undefined }),
                  ...(step.variantBVariables !== undefined ? { variantBVariables: {} } : {}),
                })}
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
                {c === 'EMAIL'
                  ? t('E-mail', 'Email')
                  : c === 'PUSH'
                    ? t('Push do aplikace', 'App push')
                    : t('Banner v aplikaci', 'In-app banner')}
              </button>
            )
          })}
        </div>
        {step.channel === 'PUSH' && (
          <>
            <p className="text-xs text-muted-foreground">
              {t(
                'Push nese jen titulek. Nabídku si člověk přečte v aplikaci po klepnutí — do notifikace se osobní obsah nedává.',
                'A push carries the headline only. The offer is read in the app after the tap — personal content never goes into a notification.',
              )}
            </p>
            <label className="mt-3 block space-y-1.5 text-sm" data-mobile-destination={index}>
              <span className="font-medium">{t('Po klepnutí otevřít', 'Open after tap')}</span>
              <select
                className={field}
                value={step.mobileDestination ?? 'HOME'}
                onChange={e => onChange({ ...step, mobileDestination: e.target.value as EditorStep['mobileDestination'] })}
              >
                <option value="HOME">{t('Domovská obrazovka', 'Home')}</option>
                <option value="SAVINGS">{t('Spoření', 'Savings')}</option>
                <option value="CARDS">{t('Karty', 'Cards')}</option>
                <option value="PAYMENTS">{t('Platby', 'Payments')}</option>
                <option value="PRODUCT_HUB">{t('Produkty', 'Products')}</option>
              </select>
              <span className="block text-xs text-muted-foreground">
                {t('Jde o pevný deep-link aplikace, ne URL zadanou do kampaně.', 'This is a fixed app deep link, not a campaign-entered URL.')}
              </span>
            </label>
          </>
        )}
        {step.channel === 'BANNER' && (
          <>
            <p className="text-xs text-muted-foreground">
              {t(
                'Zvolte přesnou plochu přihlášené aplikace. Není to push a nic se neobjeví na zamčeném telefonu.',
                'Choose the exact signed-in app surface. It is not a push and never appears on a locked phone.',
              )}
            </p>
            <label className="mt-3 block space-y-1.5 text-sm" data-in-app-surface={index}>
              <span className="font-medium">{t('Plocha v aplikaci', 'In-app surface')}</span>
              <select
                className={field}
                value={selectedSurface ?? ''}
                onChange={e => {
                  const inAppSurface = e.target.value as EditorInAppSurface
                  const template = templateForSurface(inAppSurface)
                  if (!template) return
                  onChange({
                    ...step,
                    inAppSurface,
                    template,
                    variables: {},
                    ...(step.variantBVariables !== undefined ? { variantBVariables: {} } : {}),
                  })
                }}
              >
                {Object.keys(templateSurface).map(surface => (
                  <option key={surface} value={surface}>{surfaceLabel(surface as EditorInAppSurface)}</option>
                ))}
              </select>
              <span className="block text-xs text-muted-foreground">
                {t('Každá plocha má schválený tvar karty; šablona se přepne spolu s ní.', 'Each surface has an approved card shape; its template changes with the surface.')}
              </span>
            </label>
            <label className="mt-3 block space-y-1.5 text-sm" data-mobile-destination={index}>
              <span className="font-medium">{t('Po klepnutí otevřít', 'Open after tap')}</span>
              <select
                className={field}
                value={step.mobileDestination ?? 'HOME'}
                onChange={e => onChange({ ...step, mobileDestination: e.target.value as EditorStep['mobileDestination'] })}
              >
                <option value="HOME">{t('Domovská obrazovka', 'Home')}</option>
                <option value="SAVINGS">{t('Spoření', 'Savings')}</option>
                <option value="CARDS">{t('Karty', 'Cards')}</option>
                <option value="PAYMENTS">{t('Platby', 'Payments')}</option>
                <option value="PRODUCT_HUB">{t('Produkty', 'Products')}</option>
              </select>
              <span className="block text-xs text-muted-foreground">
                {t('Banner vede jen na pevný deep-link aplikace, nikdy na URL vložené do kampaně.', 'A banner can use only a fixed app deep link, never a campaign-entered URL.')}
              </span>
            </label>
          </>
        )}
        {step.channel === 'EMAIL' && (
          <label className="mt-3 flex cursor-pointer items-start gap-2 text-sm" data-push-fallback={index}>
            <input
              type="checkbox"
              checked={step.fallbackToPush === true}
              onChange={e => onChange({ ...step, fallbackToPush: e.target.checked })}
            />
            <span>
              <span className="font-medium">{t('Když chybí e-mailový souhlas, zkusit push', 'When email consent is absent, try push')}</span>
              <span className="mt-0.5 block text-xs text-muted-foreground">
                {t(
                  'Push projde vlastním souhlasem i stejnými limity. Není to druhý pokus po nedoručení e-mailu.',
                  'Push goes through its own consent and the same limits. It is not a second attempt after an email delivery failure.',
                )}
              </span>
            </span>
          </label>
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
          onChange={e => onChange({
            ...step,
            template: e.target.value,
            variables: {},
            ...(step.variantBVariables !== undefined ? { variantBVariables: {} } : {}),
          })}
        >
          {Object.keys(templates).filter(tpl => templateChannel[tpl] === step.channel && (
            step.channel !== 'BANNER' || tpl === templateForSurface(selectedSurface)
          )).map(tpl => (
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
            : step.channel === 'BANNER'
              ? t(
                  'Plocha používá schválenou kartu aplikace. Tady se vyplňují jen její pojmenované hodnoty.',
                  'The surface uses an approved in-app card. Only its named values are filled in here.',
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

      {contentExperiment && (
        <div className="rounded-md border border-dashed p-3 space-y-3" data-variant-b-editor={index}>
          <div>
            <p className="text-sm font-medium">{t('Varianta B', 'Variant B')}</p>
            <p className="text-xs text-muted-foreground">
              {t(
                'Každý člověk zůstane po celou cestu ve stejné variantě. B může porovnávat copy, kanál i časování; stejný člověk se mezi nimi nikdy nepřepíná.',
                'Each person stays in the same variant throughout the journey. B can compare copy, channel and timing; one person is never switched between them.',
              )}
            </p>
          </div>
          <div className="grid gap-3 sm:grid-cols-3" data-variant-b-path={index}>
            <label className="space-y-1.5 text-sm">
              <span className="font-medium">{t('Kanál B', 'B channel')}</span>
              <select
                className={field}
                value={variantBChannel}
                onChange={e => {
                  const channel = e.target.value as EditorChannel
                  const first = Object.keys(templates).find(tpl => templateChannel[tpl] === channel && (
                    channel !== 'BANNER' || tpl === templateForSurface('HOME_BANNER')
                  ))
                  if (!first) return
                  onChange({ ...step, variantBChannel: channel, variantBTemplate: first, variantBVariables: {} })
                }}
              >
                {(['EMAIL', 'PUSH', 'BANNER'] as EditorChannel[]).map(channel => (
                  <option key={channel} value={channel} disabled={step.fallbackToPush && channel !== 'EMAIL'}>
                    {channel === 'EMAIL' ? t('E-mail', 'Email') : channel === 'PUSH' ? t('Push do aplikace', 'App push') : t('Banner v aplikaci', 'In-app banner')}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1.5 text-sm">
              <span className="font-medium">{t('Šablona B', 'B template')}</span>
              <select
                className={field}
                value={variantBTemplate}
                onChange={e => onChange({ ...step, variantBTemplate: e.target.value, variantBVariables: {} })}
              >
                {Object.keys(templates).filter(tpl => templateChannel[tpl] === variantBChannel && (
                  variantBChannel !== 'BANNER' || tpl === templateForSurface('HOME_BANNER')
                )).map(tpl => <option key={tpl} value={tpl}>{templateLabels[tpl] ?? tpl}</option>)}
              </select>
            </label>
            <label className="space-y-1.5 text-sm">
              <span className="font-medium">{t('Čekání B (dny)', 'B wait (days)')}</span>
              <input
                type="number"
                min="0"
                className={field}
                value={(step.variantBDelaySeconds ?? step.delaySeconds) / 86400}
                onChange={e => onChange({ ...step, variantBDelaySeconds: Math.max(0, Number(e.target.value) || 0) * 86400 })}
              />
            </label>
          </div>
          {variantBDeclared.map(v => (
            <div key={v} className="space-y-1.5">
              <label htmlFor={`var-b-${index}-${v}`} className="text-sm font-medium">
                {variableLabels[v]?.label ?? v}
              </label>
              <input
                id={`var-b-${index}-${v}`}
                className={field}
                placeholder={variableLabels[v]?.example ?? ''}
                value={step.variantBVariables?.[v] ?? ''}
                onChange={e => onChange({
                  ...step,
                  variantBVariables: { ...step.variantBVariables, [v]: e.target.value },
                })}
              />
            </div>
          ))}
        </div>
      )}

      {/* The gate, next to the delay, because the two together answer "when does this go out, and to
          whom". `CONFIRMED` is delivery as notification-service reports it (ADR-0239 D3) — never
          opened and never converted, because no such signal exists in the platform. Saying so here
          is cheaper than letting someone build a follow-up they believe fires on a click. */}
      <div className="space-y-1.5">
        <span className="text-sm font-medium">{t('Kdy krok proběhne', 'When this step runs')}</span>
        <div className="flex flex-wrap gap-2">
          {([undefined, 'IF_PREVIOUS_CONFIRMED', 'IF_PREVIOUS_NOT_CONFIRMED'] as (EditorCondition | undefined)[])
            .map(c => (
              <button
                key={c ?? 'ALWAYS'}
                type="button"
                data-condition-pick={c ?? 'ALWAYS'}
                data-selected={(step.condition ?? undefined) === c ? 'true' : 'false'}
                onClick={() => onChange({ ...step, condition: c })}
                className="btn"
                style={
                  (step.condition ?? undefined) === c
                    ? { borderColor: 'var(--accent)', boxShadow: '0 0 0 1px var(--accent)' }
                    : undefined
                }
              >
                {c === undefined
                  ? t('Vždy', 'Always')
                  : c === 'IF_PREVIOUS_CONFIRMED'
                    ? t('Jen když předchozí dorazil', 'Only if the previous arrived')
                    : t('Jen když předchozí nedorazil', 'Only if the previous did not arrive')}
              </button>
            ))}
        </div>
        {index === 0 && step.condition && (
          <p className="text-xs text-amber-600">
            {t(
              'První krok nemá co předcházet — „dorazil" tu nikdy neplatí, „nedorazil" vždy.',
              'The first step has no predecessor — "arrived" never holds here, "did not" always does.',
            )}
          </p>
        )}
        {step.conditionSourceOrder !== undefined && step.condition && (
          <p className="text-xs text-muted-foreground">
            {t(
              `Tato větev vždy čte výsledek kroku ${step.conditionSourceOrder + 1}.`,
              `This path always reads the result of step ${step.conditionSourceOrder + 1}.`,
            )}
          </p>
        )}
        {step.condition === 'IF_PREVIOUS_NOT_CONFIRMED' && (
          <p className="text-xs text-muted-foreground">
            {t(
              'Nedoručeno zahrnuje i „zatím nevíme". Dejte kroku dost dlouhou prodlevu, ať výsledek stihne dorazit.',
              'Not delivered includes "we do not know yet". Give the step a delay long enough for the outcome to arrive.',
            )}
          </p>
        )}
      </div>

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
