// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Drawer } from '@/components/ui'

describe('Drawer', () => {
  it('exposes a named modal dialog and closes on Escape', () => {
    const onClose = vi.fn()
    render(<Drawer title="Product details for Current Account" description="Rates and lifecycle"
      onClose={onClose}><button type="button">Close</button></Drawer>)

    const dialog = screen.getByRole('dialog', { name: 'Product details for Current Account' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleDescription('Rates and lifecycle')
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('contains its content in a body-level portal', () => {
    render(<Drawer title="Onboarding details" onClose={vi.fn()}><p>Verified identity</p></Drawer>)

    expect(document.body).toContainElement(screen.getByRole('dialog', { name: 'Onboarding details' }))
    expect(screen.getByText('Verified identity')).toBeVisible()
  })

  it('restores focus to the external control that opened it', async () => {
    function Harness() {
      const [open, setOpen] = useState(false)
      return <>
        <button type="button" onClick={() => setOpen(true)}>Open details</button>
        {open && <Drawer title="Details" onClose={() => setOpen(false)}><button type="button">Close</button></Drawer>}
      </>
    }

    render(<Harness />)
    const trigger = screen.getByRole('button', { name: 'Open details' })
    trigger.focus()
    fireEvent.click(trigger)
    fireEvent.keyDown(document, { key: 'Escape' })

    await waitFor(() => expect(trigger).toHaveFocus())
  })
})
