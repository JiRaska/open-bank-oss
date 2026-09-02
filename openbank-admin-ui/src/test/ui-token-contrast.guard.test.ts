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

describe('admin UI token contrast', () => {
  const themes = [
    ['light', declarations(':root')],
    ['dark', declarations('\\.dark')],
  ] as const

  it.each(themes)('%s text tokens meet AA on the primary and secondary surfaces', (_name, tokens) => {
    for (const foreground of ['--text-primary', '--text-secondary', '--text-tertiary']) {
      for (const background of ['--surface', '--surface-2']) {
        expect(contrast(resolve(tokens, foreground), resolve(tokens, background))).toBeGreaterThanOrEqual(4.5)
      }
    }
  })

  it.each(themes)('%s status text meets AA on its semantic background', (_name, tokens) => {
    for (const tone of ['success', 'warning', 'danger', 'info']) {
      expect(contrast(resolve(tokens, `--${tone}-text`), resolve(tokens, `--${tone}-bg`))).toBeGreaterThanOrEqual(4.5)
    }
  })

  it.each(themes)('%s sidebar navigation text remains legible', (_name, tokens) => {
    expect(contrast(resolve(tokens, '--sidebar-text-muted'), resolve(tokens, '--sidebar-bg'))).toBeGreaterThanOrEqual(4.5)
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
