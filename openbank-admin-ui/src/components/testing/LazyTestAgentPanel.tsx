// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import dynamic from 'next/dynamic'
import { useEffect, useRef, useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const TestAgentPanel = dynamic(
  () => import('./TestAgentPanel').then(module => module.TestAgentPanel),
  { loading: () => <div className="skeleton" style={{ height: 150 }} aria-hidden="true" /> },
)

/** Defers advisory AI code and its API request until the below-fold panel approaches view. */
export function LazyTestAgentPanel() {
  const { t } = useLanguage()
  const boundary = useRef<HTMLElement>(null)
  const [nearViewport, setNearViewport] = useState(false)

  useEffect(() => {
    const element = boundary.current
    if (!element) return
    const supportsIntersectionObserver = typeof window.IntersectionObserver === 'function'
    if (!supportsIntersectionObserver) {
      const timer = setTimeout(() => setNearViewport(true), 0)
      return () => clearTimeout(timer)
    }
    const observer = new IntersectionObserver(([entry]) => {
      if (!entry.isIntersecting) return
      setNearViewport(true)
      observer.disconnect()
    }, { rootMargin: '600px 0px' })
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  return (
    <section ref={boundary} aria-label={t('Poradní agent testů', 'Advisory test agent')} style={{ minHeight: 150 }}>
      {nearViewport
        ? <TestAgentPanel />
        : <p style={{ margin: '20px 0 0', padding: 18, color: 'var(--text-secondary)', fontSize: 12, border: '1px dashed var(--border-strong)', borderRadius: 12 }}>
            {t('Analýza poradního agenta se načte při přiblížení této sekce.', 'Advisory agent analysis loads when this section approaches the viewport.')}
          </p>}
    </section>
  )
}
