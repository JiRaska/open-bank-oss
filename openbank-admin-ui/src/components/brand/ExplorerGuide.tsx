// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import Image from 'next/image'
import type { ReactNode } from 'react'
import styles from './ExplorerGuide.module.css'

interface ExplorerGuideProps {
  eyebrow?: string
  title: string
  children: ReactNode
  action?: ReactNode
  compact?: boolean
}

/**
 * The corporate Explorer mascot is a navigation and education aid. It deliberately
 * does not represent AI agents, system health, risk severity, or approval authority.
 */
export function ExplorerGuide({ eyebrow = 'OpenBank Explorer', title, children, action, compact = false }: ExplorerGuideProps) {
  return (
    <aside className={`${styles.guide} ${compact ? styles.compact : ''}`} aria-label={title}>
      <div className={styles.copy}>
        <span className={styles.eyebrow}>{eyebrow}</span>
        <h2 className={styles.title}>{title}</h2>
        <div className={styles.body}>{children}</div>
        {action && <div className={styles.action}>{action}</div>}
      </div>
      <div className={styles.portrait} aria-hidden="true">
        <Image
          src="/brand/openbank-explorer.webp"
          alt=""
          fill
          sizes={compact ? '160px' : '(max-width: 720px) 180px, 260px'}
          className={styles.image}
        />
      </div>
    </aside>
  )
}
