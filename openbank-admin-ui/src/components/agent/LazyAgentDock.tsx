// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import dynamic from 'next/dynamic'
import { Bot, X } from 'lucide-react'
import { useRef, useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const AgentDock = dynamic(
  () => import('./AgentDock').then(module => module.AgentDock),
  { ssr: false },
)

function warmAgentDock() {
  void import('./AgentDock')
}

/** Keeps the universal assistant affordance immediate while deferring its full workspace. */
export function LazyAgentDock() {
  const { t } = useLanguage()
  const [activated, setActivated] = useState(false)
  const [open, setOpen] = useState(false)
  const triggerRef = useRef<HTMLButtonElement>(null)

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        aria-label={open ? t('Zavřít asistenta', 'Close assistant') : t('Otevřít asistenta', 'Open assistant')}
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-controls={open ? 'agent-dock-panel' : undefined}
        onMouseEnter={warmAgentDock}
        onFocus={warmAgentDock}
        onClick={() => {
          setActivated(true)
          setOpen(value => !value)
        }}
        style={{
          position: 'fixed', bottom: 24, right: 24, zIndex: 1000,
          width: 52, height: 52, borderRadius: '50%', border: 'none', cursor: 'pointer',
          background: 'var(--accent-strong)', color: 'var(--text-inverse)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: 'var(--floating-action-shadow)',
        }}
      >
        {open ? <X size={22} aria-hidden="true" /> : <Bot size={22} aria-hidden="true" />}
      </button>

      {activated && (
        <AgentDock open={open} onOpenChange={setOpen} triggerRef={triggerRef} />
      )}
    </>
  )
}
