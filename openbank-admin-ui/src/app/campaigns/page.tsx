// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { Megaphone } from 'lucide-react'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'

// Read-only by design (#2895). Authoring is ADR-0221: `submit` → `activate`-by-a-different-approver
// is a two-people-at-a-screen flow, and exposing half of it as buttons would lose the point of the
// four-eyes gate while looking like it had one.

interface Campaign {
  id: string
  name: string
  goal: string
  segmentRef: { name: string; version: number }
  state: string
  createdBy: string
  approvedBy: string | null
  createdAt: string
}

export default function CampaignsPage() {
  const [items, setItems] = useState<Campaign[]>([])
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch('/api/campaigns')
      .then(r => r.json())
      .then((d: { items: Campaign[]; state: string }) => {
        if (d.state !== 'ok') {
          setUnavailable(d.state === 'unauthorized' ? 'unauthorized' : 'unreachable')
          return
        }
        setItems(d.items ?? [])
      })
      .catch(() => setUnavailable('unreachable'))
      .finally(() => setLoading(false))
  }, [])

  return (
    <div className="space-y-6">
      <PageHeader
        title="Campaigns"
        subtitle="What is running, who was enrolled, and what actually reached them"
        icon={<Megaphone className="h-6 w-6" />}
      />

      {loading && <p className="text-sm text-muted-foreground">Loading…</p>}

      {!loading && unavailable && <DataUnavailable kind={unavailable} service="Campaign-service" feature="Campaigns" />}

      {!loading && !unavailable && items.length === 0 && (
        <p className="text-sm text-muted-foreground">No campaigns yet.</p>
      )}

      {!loading && !unavailable && items.length > 0 && (
        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full text-sm">
            <thead className="bg-muted/50 text-left">
              <tr>
                <th className="px-4 py-2 font-medium">Name</th>
                <th className="px-4 py-2 font-medium">State</th>
                <th className="px-4 py-2 font-medium">Segment</th>
                <th className="px-4 py-2 font-medium">Created by</th>
                <th className="px-4 py-2 font-medium">Approved by</th>
              </tr>
            </thead>
            <tbody>
              {items.map(c => (
                <tr key={c.id} className="border-t">
                  <td className="px-4 py-2">
                    <Link href={`/campaigns/${c.id}`} className="font-medium hover:underline">
                      {c.name}
                    </Link>
                    <div className="text-xs text-muted-foreground">{c.goal}</div>
                  </td>
                  <td className="px-4 py-2">
                    <StatusBadge status={c.state} />
                  </td>
                  <td className="px-4 py-2 font-mono text-xs">
                    {c.segmentRef?.name}@{c.segmentRef?.version}
                  </td>
                  <td className="px-4 py-2 text-xs">{c.createdBy}</td>
                  {/* The checker, shown next to the maker on purpose: the maker/checker pair is
                      the audit-relevant fact about an ACTIVE campaign, not a detail. */}
                  <td className="px-4 py-2 text-xs">{c.approvedBy ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
