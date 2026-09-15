// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CodeViewport } from '@/components/ui'

describe('CodeViewport', () => {
  it('exposes long technical evidence as a named keyboard region', () => {
    render(<CodeViewport id="raw-evidence" label="Raw payment evidence">{'{"status":"BOOKED"}'}</CodeViewport>)

    const region = screen.getByRole('region', { name: 'Raw payment evidence' })
    expect(region).toHaveAttribute('id', 'raw-evidence')
    expect(region).toHaveAttribute('tabindex', '0')
    expect(region).toHaveTextContent('BOOKED')
  })
})
