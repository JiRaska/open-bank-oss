// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useRef, useState } from 'react'
import mermaid from 'mermaid'
import DOMPurify from 'dompurify'
import { MERMAID_CONFIG } from '@/lib/docs/mermaidConfig'

mermaid.initialize(MERMAID_CONFIG)

let seq = 0

/**
 * Renders a single Mermaid diagram client-side. Re-renders whenever `chart`
 * changes (e.g. when a page toggles between a "reality" and "target" diagram).
 */
export function Mermaid({ chart }: { chart: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const id = `mermaid-${++seq}`
    mermaid
      .render(id, chart)
      .then(({ svg }) => {
        if (!cancelled && ref.current) {
          // Same sanitize contract as MermaidEnhancer — never innerHTML a
          // rendered diagram straight into the page.
          ref.current.innerHTML = DOMPurify.sanitize(svg, { USE_PROFILES: { svg: true, svgFilters: true } })
          setError(null)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err))
      })
    return () => {
      cancelled = true
    }
  }, [chart])

  if (error) {
    return (
      <div style={{
        padding: '10px', border: '1px solid var(--danger-border, #fecaca)',
        background: 'var(--danger-bg, #fef2f2)', color: 'var(--danger, #dc2626)',
        borderRadius: '6px', fontFamily: 'JetBrains Mono, monospace', fontSize: '12px',
        whiteSpace: 'pre-wrap',
      }}>
        Mermaid render failed:{'\n'}{error}
      </div>
    )
  }

  return <div ref={ref} style={{ width: '100%' }} />
}
