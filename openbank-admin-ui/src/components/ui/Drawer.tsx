// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import * as Dialog from '@radix-ui/react-dialog'
import { useEffect, useRef, type ReactNode } from 'react'

type DrawerProps = {
  title: string
  description?: string
  onClose: () => void
  children: ReactNode
  width?: number
}

/** Modal side panel with focus containment, Escape/outside dismissal and focus restoration. */
export function Drawer({ title, description, onClose, children, width = 480 }: DrawerProps) {
  const returnFocusRef = useRef<HTMLElement | null>(
    typeof document !== 'undefined' && document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null,
  )

  useEffect(() => {
    const returnFocus = returnFocusRef.current
    return () => {
      queueMicrotask(() => {
        if (returnFocus?.isConnected) returnFocus.focus()
      })
    }
  }, [])

  return (
    <Dialog.Root open onOpenChange={open => { if (!open) onClose() }}>
      <Dialog.Portal>
        <Dialog.Overlay
          style={{ position: 'fixed', inset: 0, zIndex: 899, background: 'rgba(2, 6, 23, 0.56)' }}
        />
        <Dialog.Content
          aria-modal="true"
          style={{
            position: 'fixed',
            insetBlock: 0,
            right: 0,
            zIndex: 900,
            width,
            maxWidth: '100vw',
            overflowY: 'auto',
            background: 'var(--surface-1)',
            borderLeft: '1px solid var(--border)',
            boxShadow: '-8px 0 32px rgba(0,0,0,0.18)',
            animation: 'slideInRight 0.2s ease-out',
          }}
        >
          <Dialog.Title className="sr-only">{title}</Dialog.Title>
          {description && <Dialog.Description className="sr-only">{description}</Dialog.Description>}
          {children}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
