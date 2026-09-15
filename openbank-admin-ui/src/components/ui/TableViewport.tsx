// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { CSSProperties, ReactNode } from 'react'
import styles from './TableViewport.module.css'

export function TableViewport({ id, label, hint, style, children }: {
  id?: string
  label: string
  hint: string
  style?: CSSProperties
  children: ReactNode
}) {
  return (
    <div className={styles.frame} data-table-viewport="true">
      <p className={styles.hint}><span aria-hidden="true">↔</span> {hint}</p>
      <div id={id} className={styles.viewport} style={style} role="region" aria-label={label} tabIndex={0}>
        {children}
      </div>
    </div>
  )
}
