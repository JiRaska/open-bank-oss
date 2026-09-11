// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { LoaderCircle } from 'lucide-react'

type LoadingStateProps = {
  label: string
  description?: string
  className?: string
}

/**
 * An announced, page-section loading state for operator workflows.
 *
 * The visible explanation tells an operator what is being assembled; `role=status` and
 * `aria-live=polite` expose the same progress without stealing focus. The spinner is decorative
 * because the copy already carries the meaning.
 */
export function LoadingState({ label, description, className }: LoadingStateProps) {
  return (
    <div
      className={['card', className].filter(Boolean).join(' ')}
      role="status"
      aria-live="polite"
      aria-busy="true"
      style={{ padding: 28, display: 'flex', gap: 12, alignItems: 'center' }}
    >
      <LoaderCircle size={18} aria-hidden="true" className="animate-spin" style={{ flexShrink: 0 }} />
      <div>
        <div style={{ fontWeight: 650, color: 'var(--text-primary)' }}>{label}</div>
        {description && (
          <div style={{ marginTop: 3, fontSize: 12, color: 'var(--text-secondary)' }}>
            {description}
          </div>
        )}
      </div>
    </div>
  )
}
