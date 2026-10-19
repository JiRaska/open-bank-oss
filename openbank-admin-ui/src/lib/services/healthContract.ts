// SPDX-License-Identifier: Apache-2.0

export interface ServiceHealthEntry {
  name: string
  namespace?: string
  port: number
  label: string
  group: 'core' | 'payments' | 'compliance' | 'identity' | 'open-banking' | 'platform'
  container: string
  healthPath: string
  status: 'UP' | 'DOWN' | 'UNKNOWN'
  reachable: boolean
  latencyMs: number | null
  version?: string | null
  gitCommit?: string | null
  stack?: ServiceStackEntry | null
}

export interface ServiceStackEntry {
  kotlin?: { version: string }
  quarkus?: { version: string; lts?: boolean; supportUntil?: string }
  java?: { version: string; vendor?: string; arch?: string; cpu?: number; maxHeapMib?: number }
  gradle?: { version: string }
  libs?: { version: string; buildTime?: string; gitCommit?: string }
}
