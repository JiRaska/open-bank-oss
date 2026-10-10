// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import katex from 'katex'

// Mermaid invokes this public renderer API for mathematical diagram labels.
// GHSA-238p-pmpm-9mq7: an inherited trust flag must not authorize rendering links.
describe('KaTeX diagram rendering trust', () => {
  it('does not inherit trust from Object.prototype', () => {
    const prior = Object.getOwnPropertyDescriptor(Object.prototype, 'trust')
    try {
      Object.defineProperty(Object.prototype, 'trust', {
        configurable: true, writable: true, value: true,
      })
      const html = katex.renderToString(String.raw`\href{https://example.com}{bank}`, {
        throwOnError: false, output: 'html',
      })
      expect(html).not.toContain('href="https://example.com"')
    } finally {
      if (prior) Object.defineProperty(Object.prototype, 'trust', prior)
      else Reflect.deleteProperty(Object.prototype, 'trust')
    }
  })

  it('retains MathML fractions used by Mermaid without HTML class selectors', () => {
    const math = katex.renderToString(String.raw`\frac{x}{2}`, {
      throwOnError: true, displayMode: true, output: 'mathml',
    })
    expect(math).toContain('<math')
    expect(math).toContain('<mfrac>')
    expect(math).toContain('<mi>x</mi>')
  })
})
