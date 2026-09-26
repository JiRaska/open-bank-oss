// SPDX-License-Identifier: Apache-2.0
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { ServerlessLegend } from '@/components/finops/ServerlessLegend'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

afterEach(cleanup)

describe('serverless tier explainer disclosure', () => {
  it('reveals and hides the educational content from the keyboard', async () => {
    const user = userEvent.setup()
    render(<LanguageProvider><ServerlessLegend /></LanguageProvider>)

    const disclosure = screen.getByRole('button', { name: 'Serverless tiers & plan (scale-to-zero)' })
    expect(disclosure).toHaveAttribute('type', 'button')
    expect(disclosure).toHaveAttribute('aria-expanded', 'false')
    expect(disclosure).toHaveAttribute('aria-controls', 'serverless-tier-explainer')
    expect(screen.queryByText(/Every service falls into one tier/)).not.toBeInTheDocument()

    disclosure.focus()
    await user.keyboard('{Enter}')
    expect(disclosure).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText(/Every service falls into one tier/)).toBeVisible()

    await user.keyboard(' ')
    expect(disclosure).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText(/Every service falls into one tier/)).not.toBeInTheDocument()
  })
})
