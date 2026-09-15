// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ExplorerGuide } from '@/components/brand/ExplorerGuide'

const css = readFileSync(path.resolve(__dirname, '../app/globals.css'), 'utf8')

function token(name: string): string {
  const value = css.match(new RegExp(`${name}:\\s*(#[\\da-f]{6})`, 'i'))?.[1]
  if (!value) throw new Error(`Missing hex token ${name}`)
  return value
}

function luminance(hex: string) {
  const channels = hex.slice(1).match(/.{2}/g)!.map(channel => Number.parseInt(channel, 16) / 255)
  const linear = channels.map(channel => channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

function contrast(a: string, b: string) {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (light + 0.05) / (dark + 0.05)
}

describe('ExplorerGuide', () => {
  it('can use the Prague lioness without changing its education-only semantics', () => {
    const { container } = render(
      <ExplorerGuide title="Start with a person" mascot="lioness">Search naturally.</ExplorerGuide>,
    )

    expect(container.querySelector('aside')).toHaveAttribute('aria-label', 'Start with a person')
    expect(container.querySelector('img')).toHaveAttribute('src', expect.stringContaining('explorer-prague-lioness.webp'))
  })

  it.each(['--explorer-bg-start', '--explorer-bg-end'])('keeps its smallest label at AA against %s', background => {
    expect(contrast(token('--explorer-eyebrow'), token(background))).toBeGreaterThanOrEqual(4.5)
  })
})
