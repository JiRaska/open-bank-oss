// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { HorizontalScrollRegion } from '@/components/ui'

describe('HorizontalScrollRegion', () => {
  it('makes a labelled wide visual discoverable from touch and keyboard', () => {
    render(
      <HorizontalScrollRegion label="Loan lifecycle" hint="Scroll for every stage">
        <svg aria-label="Application stages" />
      </HorizontalScrollRegion>,
    )

    const region = screen.getByRole('region', { name: 'Loan lifecycle' })
    expect(region).toHaveAttribute('tabindex', '0')
    expect(region).toContainElement(screen.getByLabelText('Application stages'))
    expect(screen.getByText('Scroll for every stage')).toBeInTheDocument()
  })
})
