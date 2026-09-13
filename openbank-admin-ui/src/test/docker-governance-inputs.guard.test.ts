// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const dockerfile = readFileSync(resolve(process.cwd(), 'Dockerfile'), 'utf8')
  .split('\n')
  .filter(line => !/^\s*#/.test(line))
  .join('\n')

describe('admin image governance inputs', () => {
  it('stages the complete governance registry tree for prebuild generators', () => {
    expect(dockerfile).toContain(
      'cp -R /repo/openbank-libs/governance /governance-src/openbank-libs/governance',
    )
    expect(dockerfile).toContain(
      'COPY --from=governance-collector /governance-src/openbank-libs/governance /openbank-libs/governance',
    )
    expect(dockerfile).toContain(
      'test -s /governance-src/openbank-libs/governance/rules.yaml',
    )
    expect(dockerfile).toContain(
      'test -s /governance-src/openbank-libs/governance/card-capabilities.yaml',
    )
    expect(dockerfile).not.toContain(
      'COPY --from=governance-collector /governance-src/openbank-libs/governance/rules.yaml',
    )
  })
})
