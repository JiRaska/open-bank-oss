// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'

/**
 * App Router route-transition loading boundary (Next.js special file, https://nextjs.org/docs
 * "loading.js"). Next wraps the segment below this file in a Suspense boundary and renders this
 * as the fallback while the target route streams in, so a slow navigation gets a consistent
 * progress state instead of a blank/frozen shell.
 *
 * Sits below the root layout, which mounts only providers (Session/Language), not the app shell
 * (Sidebar/Header) — the same constraint `error.tsx` already documents, since every route's shell
 * comes from that route's own `layout.tsx`. A page-shaped skeleton (page header + card grid) keeps
 * the layout stable instead of a bare spinner, and every block is `aria-hidden` — it is decorative
 * only and never stands in for real data.
 */
export default function Loading() {
  const { t } = useLanguage()

  return (
    <div className="ob-app-content">
      <div className="page-header">
        <div style={{ width: '100%' }}>
          <div className="skeleton" aria-hidden="true" style={{ width: 180, height: 13, borderRadius: 6, marginBottom: 14 }} />
          <div className="skeleton" aria-hidden="true" style={{ width: 260, height: 26, borderRadius: 8, marginBottom: 10 }} />
          <div className="skeleton" aria-hidden="true" style={{ width: 380, height: 13, borderRadius: 6 }} />
        </div>
      </div>
      <div role="status" aria-live="polite" className="sr-only">
        {t('Načítání stránky…', 'Loading page…')}
      </div>
      <div className="grid-3" aria-hidden="true" style={{ marginTop: 20 }}>
        <div className="card"><div className="skeleton" style={{ height: 92 }} /></div>
        <div className="card"><div className="skeleton" style={{ height: 92 }} /></div>
        <div className="card"><div className="skeleton" style={{ height: 92 }} /></div>
      </div>
      <div className="card" aria-hidden="true" style={{ marginTop: 20 }}>
        <div className="skeleton" style={{ height: 260 }} />
      </div>
    </div>
  )
}
