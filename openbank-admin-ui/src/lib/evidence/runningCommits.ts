// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Which commit is each service RUNNING — answered from the Kubernetes API, never from the
 * services. One list call per namespace covers the whole fleet, no pod receives a request,
 * and a KEDA scale-to-zero service is not woken up by someone opening an evidence page.
 *
 * The commit comes from the image tag auto-deploy writes (`sandbox-<sha>[-run<id>]`); the
 * service's own `/q/openbank/docs` reports the same commit (#11585, `verifyServiceDocs`), so
 * the tag is the cheap read of a fact the pod would confirm. A tag of any other shape yields
 * `null` — unknown is reported as unknown, never guessed.
 */
import { discoverServices } from '@/lib/discovery'
import { getRegistry } from '@/lib/services/fleet'
import { k8sNameOf } from '@/lib/services/registry'

const SANDBOX_TAG = /:sandbox-([0-9a-f]{7,40})(?:-run\d+)?$/

export function commitFromImage(image: string | undefined): string | null {
  if (!image) return null
  const m = SANDBOX_TAG.exec(image)
  return m ? m[1] : null
}

export interface RunningWorkload {
  component: string
  commit: string | null
  image: string | null
}

/** Running workloads keyed by monorepo component (`openbank-<x>`), or null off-cluster. */
export async function loadRunningCommits(): Promise<Map<string, RunningWorkload> | null> {
  const discovered = await discoverServices()
  if (!discovered) return null
  const componentByK8sName = new Map(getRegistry().map(svc => [k8sNameOf(svc), svc.container]))
  const out = new Map<string, RunningWorkload>()
  for (const d of discovered) {
    const component = componentByK8sName.get(d.name) ?? `openbank-${d.name}`
    out.set(component, { component, commit: commitFromImage(d.image), image: d.image ?? null })
  }
  return out
}
