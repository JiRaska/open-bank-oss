// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

import { describe, expect, it } from 'vitest'
import { canReviewPrivateCatalogDraft } from '@/lib/catalog-review-capability'

describe('catalog review capability', () => {
  it('does not treat a hosted model as eligible for a private draft', () => {
    expect(canReviewPrivateCatalogDraft([{ id: 'hosted', sensitivity: 'HOSTED' }])).toBe(false)
  })

  it('enables private review only for an explicitly self-hosted model', () => {
    expect(canReviewPrivateCatalogDraft([
      { id: 'hosted', sensitivity: 'HOSTED' },
      { id: 'private', sensitivity: 'SELF_HOSTED' },
    ])).toBe(true)
  })
})
