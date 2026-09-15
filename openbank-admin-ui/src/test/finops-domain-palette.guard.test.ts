// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const globals = readFileSync(path.resolve(__dirname, '../app/globals.css'), 'utf8')

describe('FinOps domain palette definitions', () => {
  it('defines a light and dark value for every labelled business domain', () => {
    for (const domain of ['platform', 'governance', 'security', 'observability', 'finops', 'tax']) {
      expect(globals.match(new RegExp(`--finops-domain-${domain}:`, 'gu'))).toHaveLength(2)
    }
  })
})
