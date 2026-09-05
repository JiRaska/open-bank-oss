// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React from 'react'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { usePathname } from 'next/navigation'
import { AppProviders } from '@/components/layout/AppProviders'
import { isPublicSurface } from '@/lib/auth/publicSurface'

const trackerLifecycle = vi.hoisted(() => ({ mounts: 0, unmounts: 0 }))

vi.mock('next/navigation', () => ({
  usePathname: vi.fn(),
  useRouter: vi.fn(() => ({ refresh: vi.fn() })),
}))
vi.mock('@/components/auth/SessionProvider', () => ({
  SessionProvider: ({ children }: { children: React.ReactNode }) => <div data-testid="session-provider">{children}</div>,
}))
vi.mock('@/components/agent/AgentDock', () => ({ AgentDock: () => <div data-testid="agent-dock" /> }))
vi.mock('@/components/telemetry/RumScreenTracker', async () => {
  const React = await import('react')
  return {
    RumScreenTracker: ({ enabled }: { enabled?: boolean }) => {
      React.useEffect(() => {
        trackerLifecycle.mounts += 1
        return () => { trackerLifecycle.unmounts += 1 }
      }, [])
      return <div data-enabled={String(enabled)} data-testid="rum-tracker" />
    },
  }
})
vi.mock('@/lib/i18n/LanguageContext', () => ({
  LanguageProvider: ({ children }: { children: React.ReactNode }) => <div data-testid="language-provider">{children}</div>,
}))
vi.mock('sonner', () => ({ Toaster: () => <div data-testid="toaster" /> }))

describe('AppProviders', () => {
  beforeEach(() => {
    vi.mocked(usePathname).mockReturnValue('/dashboard')
    trackerLifecycle.mounts = 0
    trackerLifecycle.unmounts = 0
  })

  it.each(['/auth/login', '/auth/error', '/auth/forbidden', '/privacy'])('keeps %s session-free', pathname => {
    vi.mocked(usePathname).mockReturnValue(pathname)
    render(<AppProviders><main>Public content</main></AppProviders>)

    expect(screen.getByText('Public content')).toBeVisible()
    expect(screen.getByTestId('language-provider')).toBeVisible()
    expect(screen.getByTestId('toaster')).toBeVisible()
    expect(screen.queryByTestId('session-provider')).not.toBeInTheDocument()
    expect(screen.queryByTestId('agent-dock')).not.toBeInTheDocument()
  })

  it('retains authenticated infrastructure on protected operator routes', () => {
    render(<AppProviders><main>Operator content</main></AppProviders>)

    expect(screen.getByTestId('session-provider')).toBeVisible()
    expect(screen.getByTestId('agent-dock')).toBeVisible()
    expect(screen.getByText('Operator content')).toBeVisible()
  })

  it('keeps one RUM observer owner mounted across protected and public surfaces', () => {
    const view = render(<AppProviders><main>Content</main></AppProviders>)
    expect(screen.getByTestId('rum-tracker')).toHaveAttribute('data-enabled', 'true')

    vi.mocked(usePathname).mockReturnValue('/auth/login')
    view.rerender(<AppProviders><main>Content</main></AppProviders>)

    expect(screen.getByTestId('rum-tracker')).toHaveAttribute('data-enabled', 'false')
    expect(trackerLifecycle).toEqual({ mounts: 1, unmounts: 0 })
  })

  it('does not classify similarly named protected routes as public', () => {
    expect(isPublicSurface('/privacy-settings')).toBe(false)
    expect(isPublicSurface('/authentication-policy')).toBe(false)
  })
})
