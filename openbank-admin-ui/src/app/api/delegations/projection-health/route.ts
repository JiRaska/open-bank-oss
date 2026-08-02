// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230 + ADR-0232: projection health for the delegation console.
//
// A grant is minted in delegation-service but ENFORCED by each product service's local
// projection, fed from openbank.delegation.events. So the console's grant list can say ACTIVE
// while account-service still refuses the delegate, or — the direction that matters — say
// REVOKED while a lagging consumer still honours the revoked rights. Consumer-group lag on that
// topic is the only in-repo signal for that window, so it belongs next to the grants, not on a
// separate infra page an operator investigating a grant would never open.
//
// The consumer set is DERIVED from the topic (Kafka UI's topic-scoped consumer-groups endpoint),
// never a hand-kept list of service names: a projection added later must show up here without
// anyone remembering to register it, and a list maintained beside the thing it describes reads
// as "healthy" exactly when it is incomplete.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { inCluster } from '@/lib/discovery'

export const dynamic = 'force-dynamic'
export const revalidate = 0

export const DELEGATION_TOPIC = 'openbank.delegation.events'
const TIMEOUT_MS = 4000

function kafkaUiBaseUrl(): string {
  if (inCluster()) {
    return process.env.KAFKA_UI_URL ?? 'http://kafka-ui.messaging.svc:8080'
  }
  const host = process.env.SERVICES_HOST === 'container' ? 'openbank-kafka-ui' : 'localhost'
  return `http://${host}:8090`
}

type KafkaUiCluster = { name: string }

type KafkaUiConsumerGroup = {
  groupId?: string
  state?: string
  consumerLag?: number | null
  members?: number
}

export type ProjectionConsumer = {
  groupId: string
  state: string | null
  lag: number | null
  members: number | null
}

async function fetchJson<T>(url: string): Promise<T | null> {
  const res = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS), cache: 'no-store' })
  if (!res.ok) return null
  return (await res.json()) as T
}

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }

  const base = kafkaUiBaseUrl()
  try {
    const clusters = await fetchJson<KafkaUiCluster[]>(`${base}/api/clusters`)
    if (!clusters?.length) {
      return NextResponse.json({ topic: DELEGATION_TOPIC, consumers: [], state: 'unavailable' })
    }
    const cluster = encodeURIComponent(clusters[0].name)

    const raw = await fetchJson<KafkaUiConsumerGroup[] | { consumerGroups?: KafkaUiConsumerGroup[] }>(
      `${base}/api/clusters/${cluster}/topics/${encodeURIComponent(DELEGATION_TOPIC)}/consumer-groups`,
    )
    if (raw === null) {
      // Topic absent, or this Kafka UI build does not serve the topic-scoped view. Either way we
      // do not know the lag — say so rather than rendering an empty, healthy-looking table.
      return NextResponse.json({ topic: DELEGATION_TOPIC, consumers: [], state: 'unavailable' })
    }

    const groups = Array.isArray(raw) ? raw : (raw.consumerGroups ?? [])
    const consumers: ProjectionConsumer[] = groups
      .filter(g => typeof g.groupId === 'string' && g.groupId.length > 0)
      .map(g => ({
        groupId: g.groupId as string,
        state: g.state ?? null,
        lag: typeof g.consumerLag === 'number' ? g.consumerLag : null,
        members: typeof g.members === 'number' ? g.members : null,
      }))
      .sort((a, b) => a.groupId.localeCompare(b.groupId))

    return NextResponse.json({ topic: DELEGATION_TOPIC, consumers, state: 'ok' })
  } catch {
    return NextResponse.json({ topic: DELEGATION_TOPIC, consumers: [], state: 'unavailable' })
  }
}
