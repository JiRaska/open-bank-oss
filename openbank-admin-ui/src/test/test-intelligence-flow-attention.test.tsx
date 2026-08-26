// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { TestIntelligenceFlow } from '@/components/testing/TestIntelligenceFlow'
import type { TestIntelligenceReport } from '@/lib/types/test-intelligence'

vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))

describe('Test Intelligence flow attention', () => {
  it('never paints unresolved evidence healthy', () => {
    const report = {
      components: [], syntheticJourneys: [], clientExperiences: [],
      totals: {
        components: 0, componentsWithExecutionEvidence: 0, moneyPathComponents: 0,
        failingEvidence: 0, missingEvidence: 0, staleEvidence: 0,
        unknownEvidence: 1, unresolvedEvidence: 1,
      },
    } as TestIntelligenceReport

    render(<TestIntelligenceFlow report={report} />)

    expect(screen.getByText('NEEDS ATTENTION')).toBeVisible()
    expect(screen.getByText('signals to inspect')).toBeVisible()
  })
})
