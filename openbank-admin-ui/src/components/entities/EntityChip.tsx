// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0231 D3: the entity navigation standard's building block. Any cross-entity reference
// renders as a chip — icon, human label, deep-link — never as bare UUID text. The label is
// resolved from the owning service through the BFF (with a shortened-UUID fallback while
// loading or when the entity is gone), so a chip never blocks the page that hosts it.

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { CreditCard, User } from 'lucide-react'
import { svcUrl } from '@/lib/services/bff'
import { useSession } from 'next-auth/react'
import { hasPermission } from '@/lib/auth/roles'

export type EntityChipType = 'party' | 'account'

type Props = {
  type: EntityChipType
  id: string
  /** Pre-resolved label; when omitted the chip resolves it from the owning service. */
  label?: string
  sublabel?: string
}

const ROUTE: Record<EntityChipType, (id: string) => string> = {
  party: id => `/parties/${id}`,
  account: id => `/accounts/${id}`,
}

const RESOLVER: Record<EntityChipType, { url: (id: string) => string; pick: (d: Record<string, unknown>) => string | undefined }> = {
  party: {
    url: id => svcUrl('party-service', `/api/v1/parties/${id}`),
    pick: d => (typeof d.legalName === 'string' ? d.legalName : undefined),
  },
  account: {
    url: id => svcUrl('account-service', `/api/v1/accounts/${id}`),
    pick: d => (typeof d.accountNumber === 'string' ? d.accountNumber : undefined),
  },
}

function shortId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 8)}…` : id
}

export function EntityChip({ type, id, label, sublabel }: Props) {
  const { data: session } = useSession()
  const canOpenParty = type !== 'party' || hasPermission(session?.user?.roles ?? [], 'parties:view')
  const identity = `${type}:${id}`
  const [resolution, setResolution] = useState<{ identity: string; label: string } | null>(null)
  const Icon = type === 'party' ? User : CreditCard

  useEffect(() => {
    // Supplied labels are already authoritative and are rendered directly.
    // Do not mirror props into state: doing so leaves the previous entity's
    // human name visible for one render after an id change.
    if (label || !canOpenParty) return
    const ctrl = new AbortController()
    let active = true
    fetch(RESOLVER[type].url(id), { signal: ctrl.signal, cache: 'no-store' })
      .then(r => (r.ok ? r.json() : null))
      .then(d => {
        if (active && d) setResolution({ identity, label: RESOLVER[type].pick(d) ?? shortId(id) })
      })
      .catch(error => {
        if (!active || (error instanceof DOMException && error.name === 'AbortError')) return
        setResolution({ identity, label: shortId(id) })
      })
    return () => {
      active = false
      ctrl.abort()
    }
  }, [type, id, identity, label, canOpenParty])

  // A resolver label is party PII fetched under the permission that existed when the request
  // started. Mask it immediately if that permission is revoked; an explicit snapshot supplied
  // by the owning workflow follows its separate display policy and remains non-linking.
  const resolvedLabel = canOpenParty && resolution?.identity === identity ? resolution.label : undefined
  const visibleLabel = label ?? resolvedLabel ?? shortId(id)

  const content = (
    <>
      <Icon size={12} aria-hidden="true" style={{ flexShrink: 0 }} />
      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
        {visibleLabel}
      </span>
      {sublabel && <span style={{ color: 'var(--text-tertiary)', fontWeight: 400 }}>{sublabel}</span>}
    </>
  )
  const style = {
    display: 'inline-flex', alignItems: 'center', gap: '6px',
    padding: '3px 8px', borderRadius: '8px', textDecoration: 'none',
    background: 'var(--surface-3)', border: '1px solid var(--border)',
    color: 'var(--accent)', fontSize: '12px', fontWeight: 600,
    maxWidth: '280px',
  }
  return canOpenParty ? (
    <Link href={ROUTE[type](id)} title={`${type}: ${id}`} style={style}>{content}</Link>
  ) : (
    <span title={`${type}: ${id}`} style={{ ...style, color: 'var(--text-secondary)' }}>{content}</span>
  )
}
