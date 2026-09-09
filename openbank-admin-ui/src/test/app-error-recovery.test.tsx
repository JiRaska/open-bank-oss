// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import Error from '@/app/error'

const { captureException } = vi.hoisted(() => ({ captureException: vi.fn() }))

vi.mock('@sentry/nextjs', () => ({ captureException }))

describe('app error recovery', () => {
  beforeEach(() => {
    captureException.mockClear()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => vi.restoreAllMocks())

  it('announces the failure, reports it, and offers explicit recovery', async () => {
    const reset = vi.fn()
    const error = Object.assign(new globalThis.Error('render failed'), { digest: 'safe-ref-42' })

    render(
      <LanguageProvider initialLanguage="en">
        <Error error={error} reset={reset} />
      </LanguageProvider>,
    )

    const alert = screen.getByRole('alert', { name: 'This screen failed to render' })
    expect(alert).toHaveTextContent('safe-ref-42')
    expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/dashboard')
    const retry = screen.getByRole('button', { name: 'Try again' })
    expect(retry).toHaveAttribute('type', 'button')
    fireEvent.click(retry)
    expect(reset).toHaveBeenCalledOnce()
    await waitFor(() => expect(captureException).toHaveBeenCalledWith(error))
  })
})
