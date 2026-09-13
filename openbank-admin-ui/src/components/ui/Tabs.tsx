// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useRef, type CSSProperties, type KeyboardEvent, type ReactNode } from 'react'

export type TabItem<Id extends string> = {
  id: Id
  label: string
  icon?: ReactNode
  /** Preserve an existing tab/panel address when migrating a public or tested surface. */
  tabId?: string
  panelId?: string
}

type TabsProps<Id extends string> = {
  items: readonly TabItem<Id>[]
  value: Id
  onChange: (value: Id) => void
  label: string
  idPrefix: string
  orientation?: 'horizontal' | 'vertical'
  variant?: 'underline' | 'rail'
  className?: string
  style?: CSSProperties
}

/** WAI-ARIA automatic-activation tabs with wrapping arrow, Home and End navigation. */
export function Tabs<Id extends string>({
  items,
  value,
  onChange,
  label,
  idPrefix,
  orientation = 'horizontal',
  variant = 'underline',
  className,
  style,
}: TabsProps<Id>) {
  const refs = useRef<Array<HTMLButtonElement | null>>([])

  const moveFocus = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const next = event.key === 'ArrowRight' || event.key === 'ArrowDown'
      ? (index + 1) % items.length
      : event.key === 'ArrowLeft' || event.key === 'ArrowUp'
        ? (index - 1 + items.length) % items.length
        : event.key === 'Home' ? 0
          : event.key === 'End' ? items.length - 1 : -1
    if (next < 0) return
    event.preventDefault()
    onChange(items[next].id)
    refs.current[next]?.focus()
  }

  const rail = variant === 'rail'
  return (
    <div
      role="tablist"
      aria-label={label}
      aria-orientation={orientation}
      className={className}
      style={{
        display: 'flex',
        flexDirection: orientation === 'vertical' ? 'column' : 'row',
        gap: rail ? 2 : 4,
        ...style,
      }}
    >
      {items.map((item, index) => {
        const selected = value === item.id
        return (
          <button
            key={item.id}
            ref={element => { refs.current[index] = element }}
            id={item.tabId ?? `${idPrefix}-tab-${item.id}`}
            role="tab"
            type="button"
            tabIndex={selected ? 0 : -1}
            aria-selected={selected}
            aria-controls={item.panelId ?? `${idPrefix}-panel-${item.id}`}
            onKeyDown={event => moveFocus(event, index)}
            onClick={() => onChange(item.id)}
            style={{
              width: rail ? '100%' : undefined,
              display: 'flex',
              alignItems: 'center',
              gap: rail ? 9 : 6,
              padding: rail ? '8px 10px' : '8px 14px',
              borderRadius: rail ? 6 : 0,
              border: 'none',
              borderLeft: rail ? `2px solid ${selected ? 'var(--accent)' : 'transparent'}` : undefined,
              borderBottom: rail ? undefined : `2px solid ${selected ? 'var(--accent)' : 'transparent'}`,
              background: rail && selected ? 'var(--accent-light)' : 'transparent',
              color: selected ? 'var(--accent)' : 'var(--text-secondary)',
              cursor: 'pointer',
              fontFamily: 'inherit',
              fontSize: 13,
              fontWeight: selected ? 600 : rail ? 400 : 600,
              textAlign: 'left',
            }}
          >
            {item.icon && <span aria-hidden="true">{item.icon}</span>}
            {item.label}
          </button>
        )
      })}
    </div>
  )
}
