// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import React, { Suspense, use } from 'react'
import { render } from '@testing-library/react'

// Falsification harness for the unawaited-act gate in src/test/setup.ts (#3550).
//
// The gate fails any test whose bare render() makes React log
//   "A component suspended inside an `act` scope, but the `act` call was not awaited."
// A gate that has only ever passed is unfalsified, so this file emits that warning
// DELIBERATELY and asserts the gate captured it. take() clears the capture, which is
// why this proof is the one test an unawaited render does NOT fail. If the gate ever
// stops detecting the warning, take() returns null here and this file goes red —
// the suite then distrusts the gate instead of silently trusting it.

declare global {
  var __takeUnawaitedActWarning: (() => string | null) | undefined
}

const params = Promise.resolve({ id: 'gate-proof' })

function SuspendingLeaf() {
  use(params)
  return React.createElement('div', null, 'resumed')
}

describe('unawaited-act gate (#3550)', () => {
  it('captures the warning a bare render() of a use()-suspending tree emits', async () => {
    render(
      React.createElement(
        Suspense,
        { fallback: null },
        React.createElement(SuspendingLeaf),
      ),
    )
    // The warning is queued against the act queue, not thrown synchronously — flush it.
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(globalThis.__takeUnawaitedActWarning?.()).toContain('not awaited')
  })

  it('reports nothing to take() when no test emitted the warning', () => {
    expect(globalThis.__takeUnawaitedActWarning?.() ?? null).toBeNull()
  })
})
