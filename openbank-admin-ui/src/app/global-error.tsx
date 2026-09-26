// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect } from 'react'
import * as Sentry from '@sentry/nextjs'

/**
 * Root error boundary (Next.js App Router). Catches a crash in the ROOT layout itself —
 * the one case the per-route [error.tsx] can't, because it replaces the whole document.
 * Its only jobs: report to GlitchTip (ADR-0075) and render a calm, bilingual-neutral
 * fallback (never Next's bare default, per CLAUDE.md rule #1). The richer [error.tsx]
 * handles everything below the root layout.
 */
export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => {
    console.error('[admin-ui] root layout error:', error)
    Sentry.captureException(error)
  }, [error])

  return (
    <html lang="en">
      <body style={{ fontFamily: 'system-ui, sans-serif', margin: 0 }}>
        <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24 }}>
          <div role="alert" aria-labelledby="global-error-title" style={{ maxWidth: 420, textAlign: 'center' }}>
            <h2 id="global-error-title" style={{ fontSize: 16, marginBottom: 8 }}>
              The console failed to load · Konzoli se nepodařilo načíst
            </h2>
            <p style={{ color: '#6b7280', fontSize: 13, lineHeight: 1.5, marginBottom: 20 }}>
              An unexpected error occurred. It is safe to try loading the console again.<br />
              Došlo k neočekávané chybě. Konzoli můžete bezpečně zkusit načíst znovu.
            </p>
            <button type="button" aria-label="Try loading the admin console again / Zkusit znovu načíst konzoli"
              onClick={reset}
              style={{ padding: '8px 16px', borderRadius: 6, border: '1px solid #d1d5db', background: '#fff', cursor: 'pointer' }}
            >
              Try again · Zkusit znovu
            </button>
            {error?.digest && (
              <p style={{ color: '#9ca3af', fontSize: 11, marginTop: 16, fontFamily: 'monospace' }}>ref: {error.digest}</p>
            )}
          </div>
        </div>
      </body>
    </html>
  )
}
