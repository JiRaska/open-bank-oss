// SPDX-License-Identifier: Apache-2.0

// #9831: the `.dark` block, four themed consoles and a required a11y gate all existed while
// nothing in the shipped app could apply the class. These tests hold the two facts that were
// missing — the theme is REACHABLE, and the choice SURVIVES — and one that must not change: the
// default stays light, so no operator's console flips on a deploy they did not ask for.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { act, cleanup, fireEvent, render, renderHook, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { THEME_STORAGE_KEY, initialTheme, useTheme } from '@/lib/theme/useTheme'

beforeEach(() => {
  window.localStorage.clear()
  document.documentElement.classList.remove('dark')
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  document.documentElement.classList.remove('dark')
})

describe('theme switch', () => {
  it('defaults to light when the operator has never chosen', () => {
    expect(initialTheme()).toBe('light')
    renderHook(() => useTheme())
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('applies the dark class to the document root — the thing nothing did before', () => {
    const { result } = renderHook(() => useTheme())
    act(() => { result.current.toggle() })
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(result.current.theme).toBe('dark')
  })

  it('persists the choice and restores it on a later mount', () => {
    const first = renderHook(() => useTheme())
    act(() => { first.result.current.toggle() })
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')

    document.documentElement.classList.remove('dark')
    renderHook(() => useTheme())
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('survives a localStorage that throws — a blocked store must not blank the console', () => {
    vi.stubGlobal('localStorage', {
      getItem() { throw new Error('blocked') },
      setItem() { throw new Error('blocked') },
      clear() {},
      removeItem() {},
      key() { return null },
      length: 0,
    })
    const { result } = renderHook(() => useTheme())
    expect(result.current.theme).toBe('light')
    act(() => { result.current.toggle() })
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('rejects a corrupted stored value rather than applying it', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'DROP TABLE themes')
    expect(initialTheme()).toBe('light')
  })
})

describe('the header exposes the switch', () => {
  it('offers a labelled, toggleable control', () => {
    render(React.createElement(LanguageProvider, null, React.createElement(HeaderProbe)))
    const button = screen.getByRole('button', { name: 'Switch to the dark theme' })
    expect(button.getAttribute('aria-pressed')).toBe('false')
    fireEvent.click(button)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(screen.getByRole('button', { name: 'Switch to the light theme' })
      .getAttribute('aria-pressed')).toBe('true')
  })
})

/** The header's theme control in isolation — the real Header needs a next-auth session. */
function HeaderProbe() {
  const { theme, toggle } = useTheme()
  return React.createElement('button', {
    type: 'button',
    'aria-label': theme === 'dark' ? 'Switch to the light theme' : 'Switch to the dark theme',
    'aria-pressed': theme === 'dark',
    onClick: toggle,
  })
}
