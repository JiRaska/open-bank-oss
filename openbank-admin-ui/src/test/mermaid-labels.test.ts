// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, beforeAll } from 'vitest'
import DOMPurify from 'dompurify'
import mermaid from 'mermaid'
import { MERMAID_CONFIG } from '@/lib/docs/mermaidConfig'

// Regression guard for the "empty grey boxes" docs bug.
//
// The diagram renderers sanitize mermaid's SVG with DOMPurify's `svg` profile
// before innerHTML. `foreignObject` is NOT in that profile's allowlist, so when
// mermaid emits node labels as html labels the sanitizer strips the whole label
// subtree: connectors survive, text vanishes.
//
// `htmlLabels: false` makes mermaid emit native <text>/<tspan>, which survives.
// It must be set at the TOP LEVEL — `flowchart.htmlLabels` is deprecated in
// mermaid 11 and does not control node labels, so setting only that is a no-op.
// The `flowchart.htmlLabels`-only case is pinned below precisely because it
// looks like a fix and isn't.

// The exact sanitize config both renderers use.
const SANITIZE_CONFIG = { USE_PROFILES: { svg: true, svgFilters: true } } as const

// jsdom has no SVG layout engine, so the text-measurement APIs mermaid calls on
// the SVG-label path (getBBox / getComputedTextLength) are missing. Stub them
// with plausible dimensions; every assertion here is about which elements and
// text survive sanitizing, never about geometry.
const CHAR_WIDTH_PX = 8
function stubSvgTextMetrics(): void {
  const proto = (globalThis as unknown as { SVGElement: { prototype: Record<string, unknown> } }).SVGElement.prototype
  if (typeof proto.getBBox !== 'function') {
    proto.getBBox = function (this: Element) {
      return { x: 0, y: 0, width: (this.textContent?.length ?? 0) * CHAR_WIDTH_PX, height: 16 }
    }
  }
  if (typeof proto.getComputedTextLength !== 'function') {
    proto.getComputedTextLength = function (this: Element) {
      return (this.textContent?.length ?? 0) * CHAR_WIDTH_PX
    }
  }
}

const FLOWCHART = `flowchart TD
  A[Customer Edge] --> B[Ledger Service]
  B --> C[Outbox Dispatcher]`

function sanitize(svg: string): string {
  return DOMPurify.sanitize(svg, SANITIZE_CONFIG)
}

/** Visible text of the diagram's node labels, <style> excluded. */
function nodeLabelText(svg: string): string {
  const host = document.createElement('div')
  host.innerHTML = svg
  host.querySelectorAll('style').forEach(el => el.remove())
  return [...host.querySelectorAll('.nodes .label')]
    .map(n => (n.textContent ?? '').replace(/\s+/g, ' ').trim())
    .join(' | ')
}

async function render(id: string, config: object): Promise<string> {
  mermaid.initialize(config)
  const { svg } = await mermaid.render(id, FLOWCHART)
  return svg
}

/** See the note on the render below — this is a correctness test, not a latency one. */
const RENDER_TIMEOUT_MS = 30_000

describe('mermaid diagram labels survive DOMPurify', () => {
  beforeAll(() => stubSvgTextMetrics())

  it('renders node label text that survives sanitizing, with the shipped config', async () => {
    const svg = await render('t-shipped', MERMAID_CONFIG)

    // Mermaid used native <text>, not <foreignObject>.
    expect(svg).not.toContain('foreignObject')
    expect(svg).toContain('<text')

    // The label text is still there after the sanitizer runs — the actual bug.
    const clean = sanitize(svg)
    expect(nodeLabelText(clean)).toContain('Customer Edge')
    expect(nodeLabelText(clean)).toContain('Ledger Service')
    expect(nodeLabelText(clean)).toContain('Outbox Dispatcher')
    // A real mermaid render, not a stub — the slowest case in this file, and it
    // shares the vitest pool with 38 other files. It flaked on the 5 s default
    // for the same reason render-smoke's page mounts did (#2235); nothing here
    // asserts latency, so the timeout is generous on purpose.
  }, RENDER_TIMEOUT_MS)

  it('pins htmlLabels at the top level, where mermaid actually reads it', () => {
    // flowchart.htmlLabels is deprecated in mermaid 11 and is NOT a substitute:
    // the next test proves it silently fails to prevent the bug.
    expect(MERMAID_CONFIG.htmlLabels).toBe(false)
    expect(MERMAID_CONFIG.flowchart).not.toHaveProperty('htmlLabels')
  })

  it('would still lose every label if htmlLabels were only set under flowchart', async () => {
    // The tempting one-line "fix". Mermaid's node renderer reads the top-level
    // key (default true), so this config still emits foreignObject labels and
    // the sanitizer still empties them. Guards against a well-meant revert.
    const svg = await render('t-flowchart-only', {
      ...MERMAID_CONFIG,
      htmlLabels: undefined,
      flowchart: { useMaxWidth: true, htmlLabels: false },
    })

    expect(svg).toContain('foreignObject')
    expect(nodeLabelText(svg)).toContain('Customer Edge') // present in the raw SVG...
    expect(nodeLabelText(sanitize(svg))).not.toContain('Customer Edge') // ...gone after sanitizing.
  })

  it('still strips a script smuggled through the diagram source', () => {
    // Defense in depth: htmlLabels:false removes the HTML-in-label vector at
    // source, but the sanitize call stays and must keep doing its job.
    const clean = sanitize('<svg><text>ok</text><script>alert(1)</script></svg>')
    expect(clean).not.toContain('<script')
    expect(clean).toContain('ok')
  })
})
