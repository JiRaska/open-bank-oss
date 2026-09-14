// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'fs'
import path from 'path'
import { describe, expect, it } from 'vitest'

const config = () => readFileSync(path.resolve(process.cwd(), 'playwright.config.ts'), 'utf8')

describe('Playwright Test Intelligence evidence', () => {
  it('uses the production Next.js server in CI', () => {
    const source = config()

    expect(source).toContain('process.env.CI')
    expect(source).toContain('`npx next start -p ${e2ePort}`')
    expect(source).toContain('`npx next dev -p ${e2ePort}`')
    expect(source).toContain("KEYCLOAK_PUBLIC_URL: 'https://keycloak.e2e.invalid'")
    expect(source).toContain("ALLOW_INSECURE_STUDIO_URLS: 'true'")
  })

  it('writes a JUnit report to the workflow-controlled E2E evidence path', () => {
    const source = config()
    expect(source).toContain("['junit', {")
    expect(source).toContain('outputFile: process.env.PLAYWRIGHT_JUNIT_OUTPUT_FILE')
    expect(source).toContain("'build/test-results/e2e/playwright.xml'")
    expect(source).toContain('includeRetries: true')
  })
})
