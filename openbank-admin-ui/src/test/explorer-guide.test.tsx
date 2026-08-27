// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ExplorerGuide } from '@/components/brand/ExplorerGuide'

describe('ExplorerGuide', () => {
  it('can use the Prague lioness without changing its education-only semantics', () => {
    const { container } = render(
      <ExplorerGuide title="Start with a person" mascot="lioness">Search naturally.</ExplorerGuide>,
    )

    expect(container.querySelector('aside')).toHaveAttribute('aria-label', 'Start with a person')
    expect(container.querySelector('img')).toHaveAttribute('src', expect.stringContaining('explorer-prague-lioness.webp'))
  })
})
