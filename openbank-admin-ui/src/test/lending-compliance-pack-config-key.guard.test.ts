// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

// lending-service's real @ConfigProperty (CompliancePackConfig.kt) and application.yaml both
// name this key with no "openbank." prefix. The operator-facing comment on this page named it
// WITH the prefix -- a config key that has never existed, so an operator acting on the comment
// sets a no-op property and believes pack enforcement is gated when it is not (#8407).
const REAL_KEY = 'lending.compliance.enforce-pack'
const GHOST_KEY = `openbank.${REAL_KEY}`

const page = readFileSync(
  path.resolve(__dirname, '../app/lending/compliance-packs/page.tsx'),
  'utf8',
)

describe('compliance-packs console names the real enforce-pack config key', () => {
  it('does not credit a key under the openbank. prefix, which resolves to nothing', () => {
    expect(page).not.toContain(GHOST_KEY)
  })

  it('names the key lending-service actually reads', () => {
    expect(page).toContain(REAL_KEY)
  })
})
