// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect } from 'react'
import * as Sentry from '@sentry/nextjs'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AlertTriangle, RotateCw } from 'lucide-react'
import Link from 'next/link'

/**
 * App-level error boundary (Next.js App Router convention). Catches an UNHANDLED render
 * exception thrown by any page below the root layout and renders a calm, bilingual fallback
 * instead of Next's bare built-in global-error ("This page couldn't load") which blanks the
 * whole document.
 *
 * Why this exists: the graceful-state rule (CLAUDE.md #1) covers *fetch* failures via the typed
 * <DataUnavailable> panel, but a *render* exception had no boundary — so a single bad field
 * (e.g. reading `ch.type` when the API returns `checkType`) took the entire console down to the
 * scary default error. This is the safety net for that class of bug.
 *
 * Note: this sits below the root layout (which mounts only providers, not the app shell), so the
 * Sidebar/Header are not shown here. Shell-preserving per-route boundaries are a documented
 * phase-2 follow-up (see the issue) — this universal net is the high-value first step.
 */
export default function Error({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const { t } = useLanguage()

  useEffect(() => {
    // The operator console never swallows errors silently — surface to the browser console
    // AND report to GlitchTip (ADR-0075) so render regressions are caught in the field.
    console.error('[admin-ui] unhandled render error:', error)
    Sentry.captureException(error)
  }, [error])

  return (
    <div style={{ minHeight: '60vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px' }}>
      <div className="card" style={{ maxWidth: '440px', textAlign: 'center', padding: '32px' }}>
        <AlertTriangle size={32} style={{ color: 'var(--warning)', margin: '0 auto 16px' }} />
        <h2 style={{ fontSize: '16px', marginBottom: '8px' }}>
          {t('Tuto obrazovku se nepodařilo zobrazit', 'This screen failed to render')}
        </h2>
        <p style={{ color: 'var(--text-muted)', fontSize: '13px', marginBottom: '20px', lineHeight: 1.5 }}>
          {t(
            'Došlo k neočekávané chybě při vykreslování. Ostatní části konzole fungují dál — zkuste obrazovku načíst znovu.',
            'An unexpected error occurred while rendering. The rest of the console keeps working — try reloading this screen.',
          )}
        </p>
        <div style={{ display: 'flex', gap: '8px', justifyContent: 'center' }}>
          <button className="btn btn-primary" onClick={() => reset()}>
            <RotateCw size={13} /> {t('Zkusit znovu', 'Try again')}
          </button>
          <Link href="/dashboard" className="btn btn-secondary">{t('Na přehled', 'Dashboard')}</Link>
        </div>
        {error?.digest && (
          <p style={{ color: 'var(--text-tertiary)', fontSize: '11px', marginTop: '16px', fontFamily: 'var(--font-mono)' }}>
            ref: {error.digest}
          </p>
        )}
      </div>
    </div>
  )
}
