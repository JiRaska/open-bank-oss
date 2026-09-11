// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { LoadingState } from '@/components/ui'

describe('LoadingState', () => {
  it('announces meaningful progress without exposing its decorative spinner', () => {
    const { container } = render(
      <LoadingState label="Loading service readiness…" description="Assembling current evidence." />,
    )

    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-live', 'polite')
    expect(status).toHaveAttribute('aria-busy', 'true')
    expect(status).toHaveTextContent('Loading service readiness…')
    expect(status).toHaveTextContent('Assembling current evidence.')
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })

  it('keeps the description optional', () => {
    render(<LoadingState label="Loading…" />)

    expect(screen.getByRole('status')).toHaveTextContent('Loading…')
  })
})
