// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
'use client'

import { useEffect, useState } from 'react'
import { AlertTriangle } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

// Self-maintaining drift detector (ADR-0071 phase 3). Compares the code-derived
// catalog (/api/catalog/services — generated from version.txt/openapi/rules) against
// the set of service short-names a page actually renders, and surfaces any the page
// omits. So a NEW service can never again silently disappear from a page: it shows up
// here until someone adds it. Read-only consumer; degrades to nothing if the catalog
// snapshot is absent (graceful-state rule #1).

interface CatalogService { short: string; kind: string }

const DEFAULT_KINDS = ['service']

export function CatalogDriftBanner({ present, kinds = DEFAULT_KINDS }: { present: string[]; kinds?: string[] }) {
  const { t } = useLanguage()
  const [missing, setMissing] = useState<string[]>([])

  // Depend on stable string keys, not the array identities — so a caller passing a
  // fresh `present={Object.values(...)}` every render cannot trigger a refetch loop.
  const presentKey = present.join('|')
  const kindsKey = kinds.join('|')
  useEffect(() => {
    const presentSet = new Set(presentKey.split('|'))
    const allowed = new Set(kindsKey.split('|'))
    fetch('/api/catalog/services', { cache: 'no-store' })
      .then(r => (r.ok ? r.json() : null))
      .then((data: { services?: CatalogService[] } | null) => {
        if (!Array.isArray(data?.services)) return
        setMissing(
          data.services
            .filter(s => allowed.has(s.kind) && !presentSet.has(s.short))
            .map(s => s.short)
            .sort(),
        )
      })
      .catch(() => { /* catalog snapshot absent — show nothing */ })
  }, [presentKey, kindsKey])

  if (missing.length === 0) return null

  return (
    <div
      role="status"
      style={{
        display: 'flex', alignItems: 'flex-start', gap: 8,
        padding: '10px 14px', margin: '12px 0', borderRadius: 8,
        background: '#fef3c7', border: '1px solid #f59e0b', color: '#92400e', fontSize: 13,
      }}
    >
      <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: 1 }} />
      <span>
        {t(
          `Katalog (ADR-0029) zná ${missing.length} službu/y, kterou tato stránka nezobrazuje: `,
          `The code-derived catalog (ADR-0029) knows ${missing.length} service(s) this page does not show: `,
        )}
        <strong style={{ fontFamily: 'monospace' }}>{missing.join(', ')}</strong>
      </span>
    </div>
  )
}
