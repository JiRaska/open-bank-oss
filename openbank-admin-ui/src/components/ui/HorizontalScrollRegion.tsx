// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import type { CSSProperties, ReactNode } from 'react'
import styles from './HorizontalScrollRegion.module.css'

export function HorizontalScrollRegion({ label, hint, className, style, children }: {
  label: string
  hint: string
  className?: string
  style?: CSSProperties
  children: ReactNode
}) {
  return (
    <div className={styles.frame}>
      <p className={styles.hint}><span aria-hidden="true">↔</span> {hint}</p>
      <div
        className={`${styles.viewport}${className ? ` ${className}` : ''}`}
        style={style}
        role="region"
        aria-label={label}
        tabIndex={0}
      >
        {children}
      </div>
    </div>
  )
}
