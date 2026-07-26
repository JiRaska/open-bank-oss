// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

export type Column<T> = {
  /** Column heading. Pass a translated string — this component does no i18n of its own. */
  header: string
  /** Cell renderer. Returning a node (not a string) is normal — e.g. a `StatusBadge`. */
  cell: (row: T) => ReactNode
  /** Right-align, for money and counts. */
  numeric?: boolean
  className?: string
}

type DataTableProps<T> = {
  columns: Column<T>[]
  rows: T[]
  /** Stable React key per row. Required: index keys reorder wrongly when a filter changes. */
  rowKey: (row: T) => string
  /** Rendered instead of the table body when `rows` is empty. */
  empty?: ReactNode
  className?: string
}

/**
 * Tabular data (ADR-0208 D1). Every page previously hand-rolled `<table>` markup — `fx/page.tsx`
 * alone had five separate tables — so column alignment, hover behaviour and empty-state handling
 * differed per page.
 *
 * Uses the existing `.table` classes from `globals.css`. Deliberately minimal: no sorting, no
 * pagination, no selection. Those are real needs but they are *behaviour*, and adding them
 * speculatively here would produce a component nobody's actual page fits. They get added when a
 * migrating page needs them, driven by that page.
 */
export function DataTable<T>({ columns, rows, rowKey, empty, className }: DataTableProps<T>) {
  if (rows.length === 0 && empty) return <>{empty}</>

  return (
    <table className={cn('table', className)}>
      <thead>
        <tr>
          {columns.map((col, i) => (
            <th key={i} className={cn(col.numeric && 'text-right', col.className)}>
              {col.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={rowKey(row)}>
            {columns.map((col, i) => (
              <td key={i} className={cn(col.numeric && 'text-right', col.className)}>
                {col.cell(row)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}
