// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useRef } from 'react'
import * as Sentry from '@sentry/nextjs'

/**
 * Root error boundary (Next.js App Router). Catches a crash in the ROOT layout itself —
 * the one case the per-route [error.tsx] can't, because it replaces the whole document.
 * Its only jobs: report to GlitchTip (ADR-0075) and render a calm, bilingual-neutral
 * fallback (never Next's bare default, per CLAUDE.md rule #1). The richer [error.tsx]
 * handles everything below the root layout.
 */
export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const titleRef = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    console.error('[admin-ui] root layout error:', error)
    Sentry.captureException(error)
    titleRef.current?.focus()
  }, [error])

  return (
    <html lang="en">
      <head>
        <style>{`
          :root { color-scheme: light dark; --error-bg: #f8fafc; --error-surface: #ffffff; --error-text: #0f172a; --error-muted: #475569; --error-subtle: #64748b; --error-border: #cbd5e1; --error-action: #4338ca; --error-action-text: #ffffff; }
          @media (prefers-color-scheme: dark) { :root { --error-bg: #08111f; --error-surface: #111c2e; --error-text: #f1f5f9; --error-muted: #cbd5e1; --error-subtle: #94a3b8; --error-border: #475569; --error-action: #818cf8; --error-action-text: #08111f; } }
          .global-error-body { margin: 0; color: var(--error-text); background: var(--error-bg); font-family: system-ui, sans-serif; }
          .global-error-card { width: min(100%, 420px); padding: 32px; border: 1px solid var(--error-border); border-radius: 16px; background: var(--error-surface); box-shadow: 0 18px 48px rgb(15 23 42 / 14%); text-align: center; }
          .global-error-action { padding: 10px 18px; border: 1px solid var(--error-action); border-radius: 8px; color: var(--error-action-text); background: var(--error-action); font: inherit; font-weight: 650; cursor: pointer; }
          .global-error-action:focus-visible { outline: 3px solid var(--error-action); outline-offset: 3px; }
        `}</style>
      </head>
      <body className="global-error-body">
        <main style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', boxSizing: 'border-box', padding: 24 }}>
          <div className="global-error-card" role="alert" aria-labelledby="global-error-title">
            <h1 ref={titleRef} tabIndex={-1} id="global-error-title" style={{ fontSize: 20, lineHeight: 1.25, margin: '0 0 10px' }}>
              The console failed to load · Konzoli se nepodařilo načíst
            </h1>
            <p style={{ color: 'var(--error-muted)', fontSize: 13, lineHeight: 1.6, margin: '0 0 22px' }}>
              An unexpected error occurred. It is safe to try loading the console again.<br />
              Došlo k neočekávané chybě. Konzoli můžete bezpečně zkusit načíst znovu.
            </p>
            <button type="button" aria-label="Try loading the admin console again / Zkusit znovu načíst konzoli"
              onClick={reset}
              className="global-error-action"
            >
              Try again · Zkusit znovu
            </button>
            {error?.digest && (
              <p style={{ color: 'var(--error-subtle)', fontSize: 11, margin: '16px 0 0', fontFamily: 'monospace' }}>ref: {error.digest}</p>
            )}
          </div>
        </main>
      </body>
    </html>
  )
}
