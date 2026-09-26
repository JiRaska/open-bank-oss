// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

type EmptyStateProps = {
  title: ReactNode
  description?: ReactNode
  icon?: ReactNode
  action?: ReactNode
  className?: string
}

/**
 * Calm, explanatory zero-data state (ADR-0208 D1). Copy and recovery actions remain domain-owned;
 * this primitive owns the hierarchy, theme tokens and announcement semantics.
 */
export function EmptyState({ title, description, icon, action, className }: EmptyStateProps) {
  return (
    <div className={cn('ui-empty-state', className)}>
      {icon && <div className="ui-empty-state-icon" aria-hidden="true">{icon}</div>}
      <div role="status" aria-live="polite">
        <div className="ui-empty-state-title">{title}</div>
        {description && <div className="ui-empty-state-description">{description}</div>}
      </div>
      {action && <div className="ui-empty-state-action">{action}</div>}
    </div>
  )
}
