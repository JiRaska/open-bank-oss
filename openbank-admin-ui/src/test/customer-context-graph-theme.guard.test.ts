// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const root = path.resolve(__dirname, '..')
const component = readFileSync(path.join(root, 'components/party/CustomerContextGraph.tsx'), 'utf8')
const globals = readFileSync(path.join(root, 'app/globals.css'), 'utf8')
const kinds = ['domain', 'account', 'product', 'card', 'notification', 'consent', 'application', 'case', 'device', 'document']

describe('customer context graph theme semantics', () => {
  it('defines and consumes an adaptive token for every labelled node kind', () => {
    expect(component).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    for (const kind of kinds) {
      expect(component).toContain(`var(--graph-${kind})`)
      expect(globals.match(new RegExp(`--graph-${kind}:`, 'gu'))).toHaveLength(2)
    }
  })
})
