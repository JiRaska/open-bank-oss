import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const read = (rel: string) => readFileSync(path.resolve(__dirname, `../${rel}`), 'utf8')

describe('back-office screens pick records instead of asking for ids', () => {
  it.each(['cards/tokens', 'cards/disputes'])('%s picks the card via CardPicker, no typed card id', name => {
    const src = read(`app/${name}/page.tsx`)
    expect(src).toContain('<CardPicker')
    expect(src).not.toMatch(/placeholder="0{8}-/)
  })

  it('sanctions decides an approval picked from the pending queue, with no free-text id input', () => {
    const src = read('app/sanctions/page.tsx')
    expect(src).not.toMatch(/<input[^>]*sanctions-approval-id/)
    expect(src).toContain('setDecideId(a.id)')
  })

  it('treasury nostro offers recently reconciled statements named by IBAN + date', () => {
    const src = read('app/treasury/nostro/page.tsx')
    expect(src).toContain('Recently reconciled statements')
    expect(src).toContain('<details')
  })

  it.each(['settlements', 'consents', 'identity-cases', 'business-onboarding', 'pid'])('%s copy never says UUID or asks for an id', name => {
    const src = read(`app/${name}/page.tsx`)
    expect(src).not.toMatch(/UUID(?!\w)/)
    expect(src).not.toMatch(/placeholder=\{t\('[^']*(ID grantee|ID případu|ID osoby|case ID|person ID)/)
  })
})
