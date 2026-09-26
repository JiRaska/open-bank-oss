// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('KYC cockpit runtime evidence integration', () => {
  const source = readFileSync(path.resolve(__dirname, '../app/kyc/page.tsx'), 'utf8')

  it('parses both KYC response paths before rendering', () => {
    expect(source).toContain('parseKycCaseEvidence(data, requestedPartyId)')
    expect(source).toContain('parseKycCasePageEvidence(data, requestedPage, PAGE_SIZE)')
    expect(source).not.toContain('data as KycCase')
  })

  it('prevents superseded loads from committing regulatory evidence', () => {
    expect(source).toContain('activeLoad.current?.abort()')
    expect(source).toContain('if (controller.signal.aborted) return')
    expect(source).toContain('activeLoad.current = null')
  })
})
