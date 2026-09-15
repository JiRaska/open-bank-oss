// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

type PageHeaderProps = {
  title: ReactNode
  subtitle?: ReactNode
  /** Leading icon beside the title. */
  icon?: ReactNode
  /** Optional hierarchy label rendered above the title. */
  breadcrumb?: ReactNode
  /** Right-aligned actions (buttons, filters). */
  actions?: ReactNode
  className?: string
}

/**
 * Page title block (ADR-0208 D1). Uses only classes that already exist in `globals.css`
 * (`.page-header`, `.page-title`, `.page-subtitle`) — several pages hand-rolled their own heading
 * markup instead, which is why heading size and spacing drifted between domains.
 *
 * `.page-header` is already `display: flex; justify-content: space-between`, so `actions` needs no
 * wrapper class of its own — a sibling element is enough. Adding one would mean adding CSS, and this
 * layer exists to consume the design vocabulary, not to grow it.
 */
export function PageHeader({ title, subtitle, icon, breadcrumb, actions, className }: PageHeaderProps) {
  return (
    <div className={cn('page-header', className)}>
      <div className="page-header__copy">
        {breadcrumb}
        <div className="page-header__title-row">
          {icon}
          <h1 className="page-title">{title}</h1>
        </div>
        {subtitle && <p className="page-subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="page-header__actions">{actions}</div>}
    </div>
  )
}
