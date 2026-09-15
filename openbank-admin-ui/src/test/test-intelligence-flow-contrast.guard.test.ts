// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/testing/TestIntelligenceFlow.tsx'), 'utf8')

function luminance(hex: string) {
  const channels = hex.slice(1).match(/.{2}/g)!.map(channel => Number.parseInt(channel, 16) / 255)
  const linear = channels.map(channel => channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

function contrast(a: string, b: string) {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (light + 0.05) / (dark + 0.05)
}

describe('Test Intelligence fixed-canvas contrast', () => {
  it('keeps small supporting copy legible on the active-stage panel', () => {
    const muted = source.match(/--muted:(#[\da-f]{6})/i)?.[1]
    const activePanel = source.match(/color-mix\(in srgb,var\(--stage\) 14%,(#[\da-f]{6})\)/i)?.[1]
    expect(muted).toBeDefined()
    expect(activePanel).toBeDefined()
    expect(contrast(muted!, activePanel!)).toBeGreaterThanOrEqual(4.5)
  })

  it('routes every small explanatory text role through the measured token', () => {
    for (const selector of ['.ti-health small', '.ti-stage-copy em', '.ti-proof span,.ti-boundary span', '.ti-signals span']) {
      expect(source).toContain(`${selector}{`)
    }
    expect(source.match(/color:var\(--muted\)/g)).toHaveLength(5)
    expect(source).not.toContain('#71849a')
  })

  it('does not shrink operational copy below a readable compact size', () => {
    expect(source).toContain('.ti-stage-copy small{font-size:10px')
    expect(source).toContain('.ti-stage-copy em{font-size:10px')
    expect(source).toContain('.ti-proof span,.ti-boundary span{font-size:11px')
    expect(source).toContain('.ti-signals span{font-size:10px')
    expect(source).not.toMatch(/font-size:[78]px/)
  })
})
