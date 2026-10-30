// SPDX-License-Identifier: Apache-2.0
import type { InfraStatusResult } from '@/lib/infra/probes'

/** A transport observation proves reachability only, never application readiness. */
export function recentArchitectureProbe(probe: InfraStatusResult | null | undefined, now = Date.now()): InfraStatusResult | null {
  const measured = probe?.checkedAt ? Date.parse(probe.checkedAt) : NaN
  const age = now - measured
  return probe && Number.isFinite(age) && age >= -5_000 && age <= 90_000 ? probe : null
}
