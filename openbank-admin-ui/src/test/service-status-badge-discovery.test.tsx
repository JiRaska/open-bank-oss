// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'

describe('ServiceStatusBadge discovery uncertainty', () => {
  it('uses the unknown label and warning treatment instead of a down claim', () => {
    render(
      <ServiceStatusBadge
        label="ledger-service :8102"
        unavailable={{ kind: 'discovery_unavailable', status: 503 }}
        copy={{ up: 'up', idle: 'idle', down: 'down', checking: 'checking', unknown: 'Deployment status is unknown' }}
      />,
    )

    const badge = screen.getByText('ledger-service :8102')
    expect(badge).toHaveAttribute('title', 'Deployment status is unknown')
    expect(badge).not.toHaveAttribute('title', 'down')
    expect(badge).toHaveStyle({ color: 'var(--warning-text)' })
  })
})
