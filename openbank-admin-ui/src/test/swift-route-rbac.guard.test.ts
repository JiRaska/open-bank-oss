import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const read = (relative: string) => fs.readFileSync(path.join(process.cwd(), relative), 'utf8')

describe('SWIFT read-route RBAC', () => {
  it('keeps list and detail routes aligned with the payments navigation boundary', () => {
    expect(read('src/app/swift/page.tsx')).toContain('<AuthGuard permission="payment-rails:view">')
    expect(read('src/app/swift/[id]/page.tsx')).toContain('<AuthGuard permission="payment-rails:view">')
  })
})
