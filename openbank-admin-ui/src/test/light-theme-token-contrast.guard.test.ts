import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

/**
 * The light-theme text tokens must clear WCAG AA (4.5:1 for normal text) on EVERY light-theme
 * background token — not just on white.
 *
 * `--text-tertiary` was #64748b, justified in a comment that measured it against white (4.76:1)
 * and surface-2 (4.55:1): the two lightest backgrounds in the palette, and the only two anyone
 * checked. It failed on five others, worst surface-4 at 3.86:1 (#9749). Picking the easiest
 * comparison is how a token passes review and fails in use, so this guard fixes the comparison
 * set rather than the one value.
 */
const CSS = readFileSync('src/app/globals.css', 'utf8')

/** Read a token's value from the FIRST (light) `:root` block, before any dark override. */
function token(name: string): string {
  const dark = CSS.indexOf('.dark')
  const light = dark === -1 ? CSS : CSS.slice(0, dark)
  const m = light.match(new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`))
  if (!m) throw new Error(`token --${name} not found in the light block`)
  return m[1]
}

const channel = (c: number) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255)
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}
function ratio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

const BACKGROUNDS = ['surface-1', 'surface-2', 'surface-3', 'surface-4',
  'accent-bg', 'success-bg', 'warning-bg', 'danger-bg', 'info-bg']
const TEXTS = ['text-primary', 'text-secondary', 'text-tertiary']

describe('light-theme token contrast', () => {
  it('resolves the tokens it compares (a failed lookup must not pass vacuously)', () => {
    expect(BACKGROUNDS.map(token).every(v => /^#[0-9a-f]{6}$/i.test(v))).toBe(true)
    expect(TEXTS.map(token).every(v => /^#[0-9a-f]{6}$/i.test(v))).toBe(true)
  })

  it('reproduces the known failure, so the arithmetic is not self-confirming', () => {
    expect(ratio('#64748b', '#e2e8f0')).toBeCloseTo(3.86, 1)   // the value this replaced
    expect(ratio('#ffffff', '#000000')).toBeCloseTo(21, 0)     // the absolute bound
  })

  for (const text of TEXTS) {
    for (const bg of BACKGROUNDS) {
      it(`--${text} on --${bg} clears AA 4.5:1`, () => {
        expect(ratio(token(text), token(bg))).toBeGreaterThanOrEqual(4.5)
      })
    }
  }
})
