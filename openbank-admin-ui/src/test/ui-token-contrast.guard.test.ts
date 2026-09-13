// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const css = fs.readFileSync(path.join(process.cwd(), 'src/app/globals.css'), 'utf8')

function declarations(selector: string) {
  const match = css.match(new RegExp(`${selector}\\s*\\{([\\s\\S]*?)\\n\\}`, 'm'))
  if (!match) throw new Error(`Missing ${selector} token block`)
  return Object.fromEntries([...match[1].matchAll(/(--[\w-]+):\s*([^;]+);/g)].map(([, name, value]) => [name, value.trim()]))
}

function resolve(tokens: Record<string, string>, token: string): string {
  const value = tokens[token]
  if (!value) throw new Error(`Missing ${token}`)
  const alias = value.match(/^var\((--[\w-]+)\)$/)?.[1]
  return alias ? resolve(tokens, alias) : value
}

function luminance(hex: string) {
  const channels = hex.slice(1).match(/.{2}/g)!.map(channel => Number.parseInt(channel, 16) / 255)
  const linear = channels.map(channel => channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

function contrast(foreground: string, background: string) {
  const [light, dark] = [luminance(foreground), luminance(background)].sort((a, b) => b - a)
  return (light + 0.05) / (dark + 0.05)
}

const TEXT_TOKENS = [
  '--text-primary', '--text-secondary', '--text-tertiary', '--text-muted',
  '--success', '--success-text', '--warning', '--warning-text',
  '--danger', '--danger-text', '--info', '--info-text',
  '--accent', '--accent-text', '--sidebar-text', '--sidebar-text-muted', '--sidebar-active-text',
] as const

const SURFACE_TOKENS = [
  '--bg', '--surface', '--surface-1', '--surface-2', '--surface-3', '--surface-4',
  '--accent-bg', '--success-bg', '--warning-bg', '--danger-bg', '--info-bg', '--sidebar-bg',
] as const

// Every pair below is BELOW AA today. The list is a ratchet, not a permission slip: a pair that is
// not listed must pass, and a listed pair that starts passing fails too, so an entry cannot quietly
// become permanent. It replaces a hand-written coverage set that checked text tokens against
// `--surface` and `--surface-2` ONLY — which is why `--text-tertiary` on `--surface-3` (4.34:1, live
// on /cards, /devops, /security/excellence) was invisible while this file stayed green (#9749).
//
// OBSERVED_* are pairs an axe `color-contrast` sweep of all 103 routes actually found rendering in
// the app. They are real debt and belong in #9749, not here, long-term.
const OBSERVED_LIGHT: ReadonlyArray<readonly [string, string]> = [
  ['--warning', '--surface-2'], // 2.05:1
  ['--warning', '--warning-bg'], // 2.07:1
  ['--success', '--success-bg'], // 2.41:1
  ['--success', '--surface-2'], // 2.42:1
  ['--success', '--surface'], // 2.54:1
  ['--success', '--surface-1'], // 2.54:1
  ['--info', '--info-bg'], // 3.38:1
  ['--danger', '--danger-bg'], // 3.44:1
  ['--danger', '--surface'], // 3.76:1
  ['--danger', '--surface-1'], // 3.76:1
  ['--accent', '--accent-bg'], // 3.99:1
]

// Pairs that fail arithmetically but which that sweep did not observe — mostly combinations nobody
// writes (amber body text on a red tint, sidebar tokens on content surfaces). They are listed so the
// cross-product stays honest rather than filtered by taste; shrinking this list by DECIDING which
// pairs are legitimate is the follow-up, and every removal makes the guard stricter.
const UNOBSERVED_LIGHT: ReadonlyArray<readonly [string, string]> = [
  ['--text-primary', '--sidebar-bg'], // 1.08:1
  ['--sidebar-active-text', '--surface-4'], // 1.21:1
  ['--sidebar-active-text', '--accent-bg'], // 1.33:1
  ['--sidebar-active-text', '--surface-3'], // 1.36:1
  ['--sidebar-active-text', '--danger-bg'], // 1.36:1
  ['--sidebar-active-text', '--info-bg'], // 1.37:1
  ['--sidebar-active-text', '--success-bg'], // 1.42:1
  ['--sidebar-active-text', '--bg'], // 1.43:1
  ['--sidebar-active-text', '--surface-2'], // 1.43:1
  ['--sidebar-active-text', '--warning-bg'], // 1.44:1
  ['--sidebar-active-text', '--surface'], // 1.49:1
  ['--sidebar-active-text', '--surface-1'], // 1.49:1
  ['--warning', '--surface-4'], // 1.74:1
  ['--warning', '--accent-bg'], // 1.92:1
  ['--warning', '--surface-3'], // 1.96:1
  ['--warning', '--danger-bg'], // 1.96:1
  ['--warning', '--info-bg'], // 1.97:1
  ['--warning', '--success-bg'], // 2.04:1
  ['--success', '--surface-4'], // 2.06:1
  ['--warning', '--bg'], // 2.06:1
  ['--sidebar-text-muted', '--surface-4'], // 2.08:1
  ['--warning', '--surface'], // 2.15:1
  ['--warning', '--surface-1'], // 2.15:1
  ['--sidebar-text', '--surface-4'], // 2.26:1
  ['--success', '--accent-bg'], // 2.27:1
  ['--sidebar-text-muted', '--accent-bg'], // 2.29:1
  ['--success', '--surface-3'], // 2.32:1
  ['--success', '--danger-bg'], // 2.32:1
  ['--success', '--info-bg'], // 2.33:1
  ['--sidebar-text-muted', '--surface-3'], // 2.34:1
  ['--sidebar-text-muted', '--danger-bg'], // 2.34:1
  ['--sidebar-text-muted', '--info-bg'], // 2.36:1
  ['--success', '--bg'], // 2.43:1
  ['--sidebar-text-muted', '--success-bg'], // 2.43:1
  ['--accent-text', '--sidebar-bg'], // 2.44:1
  ['--success', '--warning-bg'], // 2.45:1
  ['--sidebar-text-muted', '--surface-2'], // 2.45:1
  ['--sidebar-text-muted', '--bg'], // 2.46:1
  ['--sidebar-text-muted', '--warning-bg'], // 2.47:1
  ['--sidebar-text', '--accent-bg'], // 2.49:1
  ['--text-secondary', '--sidebar-bg'], // 2.55:1
  ['--sidebar-text', '--surface-3'], // 2.55:1
  ['--sidebar-text', '--danger-bg'], // 2.55:1
  ['--sidebar-text', '--info-bg'], // 2.56:1
  ['--sidebar-text-muted', '--surface'], // 2.56:1
  ['--sidebar-text-muted', '--surface-1'], // 2.56:1
  ['--sidebar-text', '--success-bg'], // 2.65:1
  ['--sidebar-text', '--bg'], // 2.67:1
  ['--sidebar-text', '--surface-2'], // 2.67:1
  ['--sidebar-text', '--warning-bg'], // 2.69:1
  ['--sidebar-text', '--surface'], // 2.79:1
  ['--sidebar-text', '--surface-1'], // 2.79:1
  ['--info-text', '--sidebar-bg'], // 2.88:1
  ['--info', '--surface-4'], // 2.98:1
  ['--danger-text', '--sidebar-bg'], // 2.99:1
  ['--danger', '--surface-4'], // 3.05:1
  ['--info', '--accent-bg'], // 3.29:1
  ['--text-tertiary', '--sidebar-bg'], // 3.34:1
  ['--text-muted', '--sidebar-bg'], // 3.34:1
  ['--info', '--surface-3'], // 3.36:1
  ['--info', '--danger-bg'], // 3.36:1
  ['--danger', '--accent-bg'], // 3.37:1
  ['--danger', '--surface-3'], // 3.44:1
  ['--danger', '--info-bg'], // 3.46:1
  ['--info', '--success-bg'], // 3.49:1
  ['--success-text', '--sidebar-bg'], // 3.52:1
  ['--info', '--bg'], // 3.52:1
  ['--info', '--surface-2'], // 3.52:1
  ['--info', '--warning-bg'], // 3.55:1
  ['--danger', '--success-bg'], // 3.57:1
  ['--danger', '--surface-2'], // 3.6:1
  ['--danger', '--bg'], // 3.61:1
  ['--accent', '--surface-4'], // 3.62:1
  ['--danger', '--warning-bg'], // 3.63:1
  ['--info', '--surface'], // 3.68:1
  ['--info', '--surface-1'], // 3.68:1
  ['--warning-text', '--sidebar-bg'], // 3.85:1
  ['--accent', '--surface-3'], // 4.08:1
  ['--accent', '--danger-bg'], // 4.08:1
  ['--accent', '--info-bg'], // 4.1:1
  ['--accent', '--success-bg'], // 4.24:1
  ['--accent', '--surface-2'], // 4.27:1
  ['--accent', '--bg'], // 4.28:1
  ['--accent', '--warning-bg'], // 4.31:1
  ['--accent', '--sidebar-bg'], // 4.33:1
  ['--accent', '--surface'], // 4.47:1
  ['--accent', '--surface-1'], // 4.47:1
]

const UNOBSERVED_DARK: ReadonlyArray<readonly [string, string]> = [
  ['--danger', '--success-bg'], // 3.51:1
  ['--danger', '--surface-4'], // 3.74:1
  ['--text-tertiary', '--success-bg'], // 3.79:1
  ['--text-muted', '--success-bg'], // 3.79:1
  ['--sidebar-text-muted', '--success-bg'], // 3.79:1
  ['--info', '--success-bg'], // 3.82:1
  ['--text-tertiary', '--surface-4'], // 4.04:1
  ['--text-muted', '--surface-4'], // 4.04:1
  ['--sidebar-text-muted', '--surface-4'], // 4.04:1
  ['--info', '--surface-4'], // 4.07:1
]

const BELOW_AA = {
  light: [...OBSERVED_LIGHT, ...UNOBSERVED_LIGHT],
  dark: [...UNOBSERVED_DARK],
} as const

const AA = 4.5

describe('admin UI token contrast', () => {
  const themes = [
    ['light', declarations(':root')],
    ['dark', declarations('\\.dark')],
  ] as const

  it.each(themes)('%s: every text token over every surface is AA, or declared below it', (name, tokens) => {
    const declared = new Set(BELOW_AA[name].map(([text, surface]) => `${text} on ${surface}`))
    const unexpected: string[] = []
    const stale: string[] = []
    const missing: string[] = []
    for (const text of TEXT_TOKENS) {
      for (const surface of SURFACE_TOKENS) {
        // Never `continue` past a missing token: that is how a renamed or deleted token turns this
        // loop into a no-op that still passes. Listed tokens must exist — asserted below.
        if (!tokens[text] || !tokens[surface]) { missing.push(!tokens[text] ? text : surface); continue }
        const [fg, bg] = [resolve(tokens, text), resolve(tokens, surface)]
        if (!fg.startsWith('#') || !bg.startsWith('#')) continue
        const key = `${text} on ${surface}`
        const ratio = contrast(fg, bg)
        if (ratio < AA && !declared.has(key)) unexpected.push(`${key} = ${ratio.toFixed(2)}:1`)
        if (ratio >= AA && declared.has(key)) stale.push(`${key} = ${ratio.toFixed(2)}:1 now passes — drop it from BELOW_AA`)
      }
    }
    expect({ unexpected, stale, missing: [...new Set(missing)] })
      .toEqual({ unexpected: [], stale: [], missing: [] })
  })

  // Sabotage that motivated this test: removing '--surface-3' from SURFACE_TOKENS left the suite
  // GREEN while silently shrinking the cross-product — the very failure mode this rewrite exists to
  // prevent. The lists are therefore derived from globals.css and checked, not trusted.
  it('the cross-product covers every text and surface token globals.css declares', () => {
    const root = declarations(':root')
    const names = Object.keys(root)
    const surfaces = names.filter(n => /^--(bg|surface(-\d)?|[a-z]+-bg)$/.test(n) && /^#[0-9a-f]{6}$/i.test(resolve(root, n)))
    // Filter by VALUE, not by name: --text-xs .. --text-xl are font SIZES, and --ob-accent-text is
    // an alias of --accent-text. A name-only rule reported all six as uncovered colours.
    const isColour = (n: string) => /^#[0-9a-f]{6}$/i.test(resolve(root, n))
    const texts = names.filter(n => /^--text-[a-z]+$|-text$|-text-muted$/.test(n) && isColour(n))
    // Tone tokens are not named "text" and the axe sweep found all four rendering AS text
    // (e.g. --success #10b981 on --success-bg = 2.41:1 on /day-end and /system/health).
    const tones = ['--success', '--warning', '--danger', '--info', '--accent']
    const missingSurfaces = surfaces.filter(n => !SURFACE_TOKENS.includes(n as never))
    // --text-inverse is excluded by design: it exists to sit on a solid accent/tone fill, not on any
    // token in SURFACE_TOKENS, so including it would assert 12 pairs nobody writes.
    const missingTexts = [...texts, ...tones]
      .filter(n => n !== '--text-inverse' && !n.startsWith('--ob-') && !TEXT_TOKENS.includes(n as never))
    expect({ missingSurfaces, missingTexts }).toEqual({ missingSurfaces: [], missingTexts: [] })
  })

  // Ported from the light-theme guard #9788 added, which this file subsumes: if the arithmetic is
  // wrong, every comparison above is wrong in the same direction and the suite agrees with itself.
  it('reproduces two known ratios, so the arithmetic is not self-confirming', () => {
    expect(contrast('#64748b', '#e2e8f0')).toBeCloseTo(3.86, 1)  // the pre-#9788 tertiary failure
    expect(contrast('#ffffff', '#000000')).toBeCloseTo(21, 0)    // the absolute bound
  })

  it('the alias --text-muted is covered, not skipped', () => {
    // --text-muted is `var(--text-tertiary)`, so it inherits every one of its ratios. A guard that
    // resolved aliases but did not LIST the alias would silently cover half of what it claims.
    expect(TEXT_TOKENS).toContain('--text-muted')
    const light = declarations(':root')
    expect(resolve(light, '--text-muted')).toBe(resolve(light, '--text-tertiary'))
  })

  it.each(themes)('%s status text meets AA on its semantic background', (_name, tokens) => {
    for (const tone of ['success', 'warning', 'danger', 'info']) {
      expect(contrast(resolve(tokens, `--${tone}-text`), resolve(tokens, `--${tone}-bg`))).toBeGreaterThanOrEqual(AA)
    }
  })

  it.each(themes)('%s sidebar navigation text remains legible', (_name, tokens) => {
    expect(contrast(resolve(tokens, '--sidebar-text-muted'), resolve(tokens, '--sidebar-bg'))).toBeGreaterThanOrEqual(AA)
  })

  it('declares a complete dark token surface and the shared foundation scales', () => {
    const dark = declarations('\\.dark')
    for (const token of ['--bg', '--surface', '--border', '--text-primary', '--accent', '--success', '--warning', '--danger', '--info']) {
      expect(dark[token]).toBeTruthy()
    }
    const root = declarations(':root')
    for (const token of ['--space-1', '--text-sm', '--font-semibold', '--motion-normal', '--z-modal']) {
      expect(root[token]).toBeTruthy()
    }
  })
})
