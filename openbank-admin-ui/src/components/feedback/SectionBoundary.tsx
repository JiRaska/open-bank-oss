// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import React from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/**
 * Contains a render error to one section of a screen.
 *
 * `app/error.tsx` catches per ROUTE, so any throw anywhere on a page replaces the whole thing with
 * "this screen could not be displayed" — a campaign's state, its approvals, its send log and its
 * journey all disappear because one of them broke. That is the failure the graceful-state rule
 * (CLAUDE.md #1) exists to prevent, and the route boundary is too coarse to deliver it.
 *
 * It also makes the error legible. A route-level boundary tells an operator only that "something"
 * failed; this names the section, which is the first thing anyone reporting it would be asked.
 *
 * Deliberately NOT a replacement for handling failure properly: a section that can fail for a known
 * reason should say so via `DataUnavailable`. This is for the throw nobody predicted — and it prints
 * the message, because a boundary that swallows what it caught turns a five-minute diagnosis into an
 * afternoon of guessing at reproductions.
 */
interface BoundaryProps {
  name: string
  children: React.ReactNode
  /**
   * Copy is passed in rather than translated here: an error boundary must be a class (React exposes
   * `getDerivedStateFromError` nowhere else) and a class cannot call `useLanguage`. The hook runs in
   * the wrapper below — outside the subtree that throws, so a broken child cannot break the
   * translation of its own failure message.
   */
  title: string
  footer: string
}

class Boundary extends React.Component<BoundaryProps, { error: Error | null }> {
  constructor(props: BoundaryProps) {
    super(props)
    this.state = { error: null }
  }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    // The browser console is the only place this is currently readable — admin-ui ships no Sentry
    // DSN, so `Sentry.captureException` in the route boundary reports to nothing.
    console.error(`[admin-ui] section "${this.props.name}" failed to render:`, error, info)
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div className="rounded-lg border border-dashed p-4 text-sm">
        <p className="font-medium">{this.props.title}</p>
        <p className="mt-1 text-xs text-muted-foreground">
          {this.props.name} — {this.state.error.message}
        </p>
        <p className="mt-1 text-xs text-muted-foreground">{this.props.footer}</p>
      </div>
    )
  }
}

export function SectionBoundary({ name, children }: { name: string; children: React.ReactNode }) {
  const { t } = useLanguage()
  return (
    <Boundary
      name={name}
      title={t('Tuto část se nepodařilo zobrazit', 'This section could not be rendered')}
      footer={t('Zbytek obrazovky funguje dál.', 'The rest of the screen still works.')}
    >
      {children}
    </Boundary>
  )
}
