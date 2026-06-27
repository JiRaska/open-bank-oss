// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { inCluster } from '@/lib/discovery'

export interface KafkaTopicResult {
  name: string
  partitions: number
  replicas: number
  messageCount: number
  segmentSize: number
  replicationFactor: number
}

export interface KafkaTopicsResponse {
  topics: KafkaTopicResult[]
  clusterName: string
}

function kafkaUiBaseUrl(): string {
  if (inCluster()) {
    return process.env.KAFKA_UI_URL ?? 'http://kafka-ui.messaging.svc:8080'
  }
  const host = process.env.SERVICES_HOST === 'container' ? 'openbank-kafka-ui' : 'localhost'
  return `http://${host}:8090`
}

interface KafkaUiCluster {
  name: string
  status: string
}

interface KafkaUiTopic {
  name: string
  partitions?: Array<{ partition: number; leader: number; replicas: number[] }>
  replicationFactor?: number
  replicas?: number
  partitionCount?: number
  segmentSize?: number
  segmentCount?: number
}

async function fetchJson<T>(url: string): Promise<T> {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), 4000)
  try {
    const res = await fetch(url, { signal: ctrl.signal, cache: 'no-store' })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return await res.json() as T
  } finally {
    clearTimeout(timer)
  }
}

export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET() {
  const base = kafkaUiBaseUrl()
  try {
    const clusters = await fetchJson<KafkaUiCluster[]>(`${base}/api/clusters`)
    if (!clusters.length) {
      return NextResponse.json({ topics: [], clusterName: '' })
    }
    const clusterName = clusters[0].name

    const topicsData = await fetchJson<{ topics?: KafkaUiTopic[]; pageCount?: number } | KafkaUiTopic[]>(
      `${base}/api/clusters/${encodeURIComponent(clusterName)}/topics?page=0&perPage=500&sortBy=NAME&sortOrder=ASC`,
    )

    const rawTopics: KafkaUiTopic[] = Array.isArray(topicsData)
      ? topicsData
      : (topicsData.topics ?? [])

    const topics: KafkaTopicResult[] = rawTopics
      .filter((t) => !t.name.startsWith('__'))
      .map((t) => ({
        name: t.name,
        partitions: t.partitionCount ?? t.partitions?.length ?? 1,
        replicas: t.replicationFactor ?? t.replicas ?? 1,
        messageCount: 0,
        segmentSize: t.segmentSize ?? 0,
        replicationFactor: t.replicationFactor ?? t.replicas ?? 1,
      }))
      .sort((a, b) => a.name.localeCompare(b.name))

    return NextResponse.json({ topics, clusterName }, { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json({ error: 'unavailable' }, { status: 503 })
  }
}
