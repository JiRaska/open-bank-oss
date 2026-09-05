// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'fs'
import path from 'path'
import { describe, expect, it } from 'vitest'

const config = () => readFileSync(path.resolve(process.cwd(), 'playwright.config.ts'), 'utf8')

describe('Playwright Test Intelligence evidence', () => {
  it('writes a JUnit report to the workflow-controlled E2E evidence path', () => {
    const source = config()
    expect(source).toContain("['junit', {")
    expect(source).toContain('outputFile: process.env.PLAYWRIGHT_JUNIT_OUTPUT_FILE')
    expect(source).toContain("'build/test-results/e2e/playwright.xml'")
    expect(source).toContain('includeRetries: true')
  })
})
