// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/** The only facts the dashboard learns from the fleet-health endpoint. */
export type FleetHealthSample = {
  deployed: boolean | null
  up: boolean
  /** Omit for legacy boolean samples; UNKNOWN remains neutral, not a failed check. */
  unknown?: boolean
  latencyMs: number | null
}

export type FleetHealthSummary = {
  total: number
  deployed: number
  healthy: number
  observed: number
  unknown: number
  unhealthy: number
  notDeployed: number
  averageHealthCheckLatencyMs: number | null
}

/**
 * Summarises one *current* health-check sample. It deliberately does not manufacture
 * uptime, error rate, percentile latency, throughput, security or compliance claims:
 * none of those signals are in the discovery response.
 */
export function summarizeFleetHealth(samples: FleetHealthSample[]): FleetHealthSummary {
  const deployed = samples.filter(sample => sample.deployed === true)
  const deploymentUnknown = samples.filter(sample => sample.deployed === null).length
  const observed = deployed.filter(sample => sample.unknown !== true)
  const unknown = deployed.length - observed.length + deploymentUnknown
  const latencies = deployed.flatMap(sample => sample.latencyMs === null ? [] : [sample.latencyMs])

  return {
    total: samples.length,
    deployed: deployed.length,
    healthy: observed.filter(sample => sample.up).length,
    observed: observed.length,
    unknown,
    unhealthy: observed.filter(sample => !sample.up).length,
    notDeployed: samples.filter(sample => sample.deployed === false).length,
    averageHealthCheckLatencyMs:
      latencies.length === 0 ? null : Math.round(latencies.reduce((sum, latency) => sum + latency, 0) / latencies.length),
  }
}

export type FleetHealthState = 'healthy' | 'degraded' | 'unavailable'

export function fleetHealthState(summary: FleetHealthSummary): FleetHealthState {
  if (summary.unhealthy > 0) return 'degraded'
  if (summary.observed === 0 || summary.unknown > 0) return 'unavailable'
  return 'healthy'
}
