// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { Tabs } from '@/components/ui'

function Harness({ variant = 'underline' }: { variant?: 'underline' | 'rail' }) {
  const [value, setValue] = useState<'queue' | 'history'>('queue')
  return <Tabs items={[{ id: 'queue', label: 'Queue', tabId: 'legacy-queue-tab', panelId: 'legacy-queue-panel' }, { id: 'history', label: 'History' }]}
    value={value} onChange={setValue} label="Cases" idPrefix="cases" variant={variant} />
}

describe('Tabs', () => {
  it('uses the high-contrast accent text for selected tabs in both layouts', () => {
    const { rerender } = render(<Harness />)
    const queue = screen.getByRole('tab', { name: 'Queue' })
    expect(queue).toHaveStyle({ color: 'var(--accent-text)' })

    rerender(<Harness variant="rail" />)
    expect(queue).toHaveStyle({ color: 'var(--accent-text)', background: 'var(--accent-light)' })
    expect(screen.getByRole('tab', { name: 'History' })).toHaveStyle({ color: 'var(--text-secondary)' })
  })

  it('links tabs to panels and exposes exactly one roving tab stop', () => {
    render(<Harness />)

    const [queue, history] = screen.getAllByRole('tab')
    expect(screen.getByRole('tablist', { name: 'Cases' })).toHaveAttribute('aria-orientation', 'horizontal')
    expect(queue).toHaveAttribute('id', 'legacy-queue-tab')
    expect(queue).toHaveAttribute('aria-controls', 'legacy-queue-panel')
    expect(history).toHaveAttribute('id', 'cases-tab-history')
    expect(history).toHaveAttribute('aria-controls', 'cases-panel-history')
    expect(queue).toHaveAttribute('aria-selected', 'true')
    expect(queue).toHaveAttribute('tabindex', '0')
    expect(history).toHaveAttribute('tabindex', '-1')
  })

  it('wraps arrows, activates the focused tab, and supports Home and End', () => {
    render(<Harness />)
    const [queue, history] = screen.getAllByRole('tab')

    queue.focus()
    fireEvent.keyDown(queue, { key: 'ArrowLeft' })
    expect(history).toHaveFocus()
    expect(history).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(history, { key: 'Home' })
    expect(queue).toHaveFocus()
    fireEvent.keyDown(queue, { key: 'End' })
    expect(history).toHaveFocus()
  })
})
