// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import type { CSSProperties, ReactNode } from 'react'
import styles from './CodeViewport.module.css'

export function CodeViewport({ id, label, style, children }: {
  id?: string
  label: string
  style?: CSSProperties
  children: ReactNode
}) {
  return (
    <pre id={id} className={styles.viewport} style={style} role="region" aria-label={label} tabIndex={0}>
      {children}
    </pre>
  )
}
