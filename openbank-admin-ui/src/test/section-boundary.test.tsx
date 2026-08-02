// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SectionBoundary } from '@/components/feedback/SectionBoundary'

function Boom(): React.ReactElement {
  throw new Error('journey blew up')
}

describe('section boundary', () => {
  /**
   * The point: `app/error.tsx` catches per ROUTE, so one bad section takes the whole screen — a
   * campaign's state, approvals and send log all disappear because the journey threw. That is the
   * blank error page a user reported, and the reason it says nothing about which part failed.
   */
  it('contains the failure and keeps its siblings on screen', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})

    render(
      React.createElement(LanguageProvider, null,
        React.createElement('div', null,
          React.createElement(SectionBoundary, { name: 'Journey' }, React.createElement(Boom)),
          React.createElement('p', null, 'send log still here'))),
    )

    expect(screen.getByText('send log still here')).toBeTruthy()
    // The section is named, because "which part failed" is the first question anyone is asked…
    expect(screen.getByText(/Journey/)).toBeTruthy()
    // …and the message is printed, because a boundary that swallows what it caught turns a
    // five-minute diagnosis into an afternoon of guessing at reproductions.
    expect(screen.getByText(/journey blew up/)).toBeTruthy()
    expect(spy).toHaveBeenCalled()
    spy.mockRestore()
  })

  it('renders children untouched when nothing throws', () => {
    render(React.createElement(LanguageProvider, null,
      React.createElement(SectionBoundary, { name: 'Journey' }, React.createElement('p', null, 'fine'))))
    expect(screen.getByText('fine')).toBeTruthy()
  })
})
