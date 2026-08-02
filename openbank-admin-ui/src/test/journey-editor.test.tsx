// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { JourneyEditor, MAX_STEPS, type EditorStep } from '@/components/campaigns/JourneyEditor'
import { StepEditor } from '@/components/campaigns/StepEditor'

const TEMPLATES = { MARKETING_PRODUCT_OFFER: ['offerTitle', 'offerText', 'ctaText'] }
const LABELS = { MARKETING_PRODUCT_OFFER: 'Product offer' }
const step = (delay = 0, vars: Record<string, string> = {}): EditorStep => ({
  template: 'MARKETING_PRODUCT_OFFER',
  variables: vars,
  delaySeconds: delay,
})

function canvas(steps: EditorStep[], handlers: Partial<Record<'onAdd' | 'onRemove' | 'onSelect', ReturnType<typeof vi.fn>>> = {}) {
  const props = {
    steps,
    audience: 'actives@1',
    audienceSize: 1240,
    selected: null,
    onSelect: handlers.onSelect ?? vi.fn(),
    onAdd: handlers.onAdd ?? vi.fn(),
    onRemove: handlers.onRemove ?? vi.fn(),
    templateLabels: LABELS,
  }
  return { ...render(React.createElement(LanguageProvider, null, React.createElement(JourneyEditor, props))), props }
}

describe('campaign builder canvas', () => {
  it('draws the audience and one node per step, in words', () => {
    canvas([step(), step(172800)])

    expect(screen.getByText('actives@1')).toBeTruthy()
    expect(screen.getAllByText(/Product offer/).length).toBe(2)
    expect(screen.getByText(/after 2 d/)).toBeTruthy()
  })

  /**
   * ADR-0221 D5 rejects a drag-and-drop canvas because a free-form graph is unbounded. The cap is
   * what makes this a different thing: at MAX_STEPS the add affordance is gone AND the reason is on
   * screen, so a marketer who cannot find it is told why rather than left to assume a bug.
   */
  it('stops offering a step at the domain cap, and says why', () => {
    const { container } = canvas(Array.from({ length: MAX_STEPS }, () => step()))

    expect(container.querySelector('[data-add-step]')).toBeNull()
    expect(screen.getByText(new RegExp(`${MAX_STEPS} steps is the maximum`))).toBeTruthy()
  })

  it('offers a step below the cap and reports the click', () => {
    const onAdd = vi.fn()
    const { container } = canvas([step()], { onAdd })

    const add = container.querySelector('[data-add-step]')
    expect(add).toBeTruthy()
    fireEvent.click(add!)
    expect(onAdd).toHaveBeenCalled()
  })

  it('removes the step whose node was clicked, not the last one', () => {
    const onRemove = vi.fn()
    const { container } = canvas([step(), step(), step()], { onRemove })

    fireEvent.click(container.querySelector('[data-remove-step="1"]')!)
    expect(onRemove).toHaveBeenCalledWith(1)
  })
})

describe('step editor', () => {
  /**
   * ADR-0176 D4 and the service's TemplateCatalog make a campaign supply values, never body text.
   * A free-text box here would be a control the service refuses — the worst kind, because it looks
   * like it works until submit.
   */
  it('offers only the template’s declared variables and no free-text body', () => {
    const { container } = render(
      React.createElement(LanguageProvider, null,
        React.createElement(StepEditor, {
          index: 0, step: step(), templates: TEMPLATES, templateLabels: LABELS,
          onChange: vi.fn(), onClose: vi.fn(),
        })))

    expect(screen.getByLabelText('offerTitle')).toBeTruthy()
    expect(screen.getByLabelText('ctaText')).toBeTruthy()
    expect(container.querySelector('textarea')).toBeNull()
  })

  /** `delaySeconds` is the engine's field. Asking a marketer for 172800 was the symptom. */
  it('takes the delay in days and converts it', () => {
    const onChange = vi.fn()
    render(
      React.createElement(LanguageProvider, null,
        React.createElement(StepEditor, {
          index: 0, step: step(), templates: TEMPLATES, templateLabels: LABELS,
          onChange, onClose: vi.fn(),
        })))

    fireEvent.change(screen.getByLabelText(/Send after/), { target: { value: '2' } })
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ delaySeconds: 172800 }))
  })

  it('names what is still missing rather than just refusing later', () => {
    render(
      React.createElement(LanguageProvider, null,
        React.createElement(StepEditor, {
          index: 0, step: step(0, { offerTitle: 'T' }), templates: TEMPLATES, templateLabels: LABELS,
          onChange: vi.fn(), onClose: vi.fn(),
        })))

    expect(screen.getByText(/offerText, ctaText/)).toBeTruthy()
  })
})
