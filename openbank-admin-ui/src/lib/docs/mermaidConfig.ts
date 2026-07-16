// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * The single mermaid config shared by every diagram renderer in the console
 * (`MermaidEnhancer` for docs pages, `Mermaid` for ProcessView).
 *
 * `htmlLabels: false` is load-bearing, not cosmetic:
 *
 * - With html labels on, mermaid emits node labels inside a `<foreignObject>`
 *   (XHTML). `foreignObject` is not in DOMPurify's `svg` profile allowlist, so
 *   sanitizing the rendered SVG strips the whole label subtree — diagrams render
 *   as empty boxes with correct connectors but no text.
 * - With it off, mermaid emits native `<text>`/`<tspan>`, which survives the
 *   profile. `<br/>` in a label still works (mermaid splits it into tspans).
 * - It also removes the HTML-in-label injection vector at source: diagram text
 *   can no longer become live markup.
 *
 * It must be set at the TOP LEVEL. `flowchart.htmlLabels` is deprecated as of
 * mermaid 11 and does NOT control node labels — the renderer reads the
 * top-level key (defaulting to `true`), so a `flowchart.htmlLabels: false` alone
 * is silently a no-op.
 */
export const MERMAID_CONFIG = {
  startOnLoad: false,
  theme: 'neutral',
  fontFamily: 'inherit',
  htmlLabels: false,
  flowchart: { useMaxWidth: true },
  sequence: { useMaxWidth: true },
} as const
