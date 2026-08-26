import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(path.resolve(__dirname, '../app/globals.css'), 'utf8')

function token(name: string): string {
  const match = css.match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{6})`))
  if (!match) throw new Error(`Missing hexadecimal value for ${name}`)
  return match[1]
}

function luminance(hex: string): number {
  const channels = hex.slice(1).match(/../g)!.map(channel => parseInt(channel, 16) / 255)
  const [red, green, blue] = channels.map(channel =>
    channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4,
  )
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

function contrast(foreground: string, background: string): number {
  const [lighter, darker] = [luminance(foreground), luminance(background)].sort((a, b) => b - a)
  return (lighter + 0.05) / (darker + 0.05)
}

describe('shared admin UI text token contrast', () => {
  it('keeps the high-volume muted text legible on operator surfaces', () => {
    const tertiary = token('--text-tertiary')
    expect(contrast(tertiary, token('--surface'))).toBeGreaterThanOrEqual(4.5)
    expect(contrast(tertiary, token('--surface-2'))).toBeGreaterThanOrEqual(4.5)
  })

  it('keeps muted navigation text legible on the dark sidebar', () => {
    expect(contrast(token('--sidebar-text-muted'), token('--sidebar-bg'))).toBeGreaterThanOrEqual(4.5)
  })
})
