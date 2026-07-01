// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useRef } from 'react'
import DOMPurify from 'dompurify'

// Tiny client-side enhancer. Wraps server-rendered markdown content and,
// after mount, scans for the `<pre data-mermaid>` placeholders the
// server-side MarkdownView emits, and replaces each with a rendered SVG.
//
// Mermaid is the only piece of the docs pipeline that genuinely needs the
// browser (it manipulates DOM/SVG). Keeping it isolated here means a bug
// in mermaid loading can never crash the page — at worst the diagram
// source stays visible.
type MermaidApi = {
  initialize: (cfg: object) => void
  render: (id: string, src: string) => Promise<{ svg: string }>
}

let mermaidInstance: MermaidApi | null = null
async function getMermaid(): Promise<MermaidApi> {
  if (mermaidInstance) return mermaidInstance
  const mod = await import('mermaid')
  const m = (mod.default ?? mod) as unknown as MermaidApi
  m.initialize({
    startOnLoad: false,
    theme: 'neutral',
    fontFamily: 'inherit',
    flowchart: { useMaxWidth: true, htmlLabels: true },
    sequence: { useMaxWidth: true },
  })
  mermaidInstance = m
  return m
}

function escapeHtml(s: string): string {
  return s.replace(/[<>&]/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c] ?? c))
}

export function MermaidEnhancer({ children, contentKey }: { children: React.ReactNode; contentKey: string }) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!ref.current) return
    const blocks = ref.current.querySelectorAll('pre[data-mermaid]')
    if (blocks.length === 0) return

    let cancelled = false
    void (async () => {
      try {
        const m = await getMermaid()
        if (cancelled) return
        for (let i = 0; i < blocks.length; i++) {
          const block = blocks[i] as HTMLPreElement
          const src = block.getAttribute('data-mermaid-src') ?? ''
          const id = `mermaid-${contentKey}-${i}-${Math.floor(Math.random() * 1e6)}`
          try {
            const { svg } = await m.render(id, src)
            const wrap = document.createElement('div')
            wrap.className = 'mermaid-svg'
            // Mermaid's flowchart htmlLabels:true lets diagram source embed raw
            // HTML in node labels, which ends up in the rendered `svg` string —
            // sanitize before innerHTML so a crafted diagram source can't smuggle
            // a <script>/event-handler payload into the page.
            wrap.innerHTML = DOMPurify.sanitize(svg, { USE_PROFILES: { svg: true, svgFilters: true } })
            block.replaceWith(wrap)
          } catch (err) {
            const msg = err instanceof Error ? err.message : String(err)
            const wrap = document.createElement('div')
            wrap.className = 'mermaid-error'
            wrap.setAttribute('style',
              'padding:10px;border:1px solid #ef4444;background:#fef2f2;color:#991b1b;border-radius:6px;font-family:JetBrains Mono,monospace;font-size:12px;white-space:pre-wrap;')
            wrap.innerHTML = `Mermaid render failed: ${escapeHtml(msg)}<br/><br/>${escapeHtml(src)}`
            block.replaceWith(wrap)
          }
        }
      } catch {
        // mermaid load failed — leave the source visible in <pre data-mermaid>
      }
    })()
    return () => { cancelled = true }
  }, [contentKey])

  return <div ref={ref}>{children}</div>
}
