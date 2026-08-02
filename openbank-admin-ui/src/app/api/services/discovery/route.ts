// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// BFF endpoint: exposes the ADR-0051 Kubernetes discovery feed as JSON.
//
// In-cluster: queries the K8s API for Deployments in the OpenBank domain
// namespaces and returns readiness from .status.readyReplicas. The ServiceAccount
// must have the admin-ui-discovery ClusterRole bound via per-namespace
// RoleBindings (see openbank-infra/gitops/components/admin-ui/admin-ui.yaml).
//
// Off-cluster (local dev, no SA token): returns { source: "static", services: [] }
// so callers degrade gracefully without crashing.

import { NextResponse } from 'next/server'
import { discoverServices } from '@/lib/discovery'

export interface DiscoveryService {
  name: string
  namespace: string
  ready: number
  desired: number
  healthy: boolean
}

export interface DiscoveryResponse {
  source: 'k8s' | 'static'
  services: DiscoveryService[]
}

export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET(): Promise<NextResponse<DiscoveryResponse>> {
  const discovered = await discoverServices()

  if (!discovered) {
    return NextResponse.json(
      { source: 'static', services: [] },
      { headers: { 'Cache-Control': 'no-store' } },
    )
  }

  const services: DiscoveryService[] = discovered.map((d) => ({
    name: d.name,
    namespace: d.namespace,
    ready: d.readyReplicas,
    desired: d.replicas,
    healthy: d.readyReplicas >= d.replicas && d.replicas > 0,
  }))

  return NextResponse.json(
    { source: 'k8s', services },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
