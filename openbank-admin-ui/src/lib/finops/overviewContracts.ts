// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod'

const number = z.number().finite()
const nullableNumber = number.nullable()
const runway = z.enum(['ok', 'warn', 'critical'])
export const lifecycleSchema = z.object({
  currentVersion: z.string(), currentTier: z.string(), daysToStandardEnd: number,
  runwayStatus: runway, standardSupportEnds: z.string(), extendedSupportEnds: z.string(),
  monthlyEstimate: number, annualEstimate: number, monthlySavingsVsExtended: number,
  annualSavingsVsExtended: number, minRunwayDays: number,
  versions: z.array(z.object({
    version: z.string(), eksRelease: z.string(), standardSupportEnds: z.string(),
    extendedSupportEnds: z.string(), daysToStandardEnd: number, daysToExtendedEnd: number,
    tier: z.enum(['upcoming', 'standard', 'extended', 'end_of_life']), runwayStatus: runway, isCurrent: z.boolean(),
  })),
  components: z.array(z.object({
    name: z.string(), kind: z.string(), version: z.string(), tier: z.string(), managedBy: z.string(),
    standardEnd: z.string().nullable(), daysRemaining: nullableNumber,
  })),
  dataSource: z.enum(['live', 'snapshot']), lastRefreshed: z.string(), adrRef: z.string(),
})
export const resourcesSchema = z.object({
  available: z.literal(true), degraded: z.boolean().optional(), error: z.string().nullable().optional(), fleetHeapPct: nullableNumber, underutilisedCount: number,
  serviceCount: number, collectedAt: z.string(), services: z.array(z.object({
    name: z.string(), short: z.string(), heap: z.object({ usedBytes: nullableNumber, maxBytes: nullableNumber, pct: nullableNumber }),
    cpuCoresUsed: nullableNumber, requestsPerSec: nullableNumber,
    efficiency: z.enum(['underutilised', 'normal', 'high', 'unknown']),
  })),
})
export const costsSchema = z.object({
  available: z.literal(true), currency: z.string(), periodStart: z.string(), periodEnd: z.string(), total: number,
  services: z.array(z.object({ name: z.string(), amount: number, domain: z.string() })),
  daily: z.array(z.object({ date: z.string(), amount: number })),
  collectedAt: z.string().nullable(), source: z.string(),
})
export const rightSizingSchema = z.object({
  available: z.literal(true), degraded: z.boolean().optional(), error: z.string().nullable().optional(), collectedAt: z.string(), fleetCpuEfficiencyPct: nullableNumber,
  fleetMemEfficiencyPct: nullableNumber, highSavingsCount: number, vpaHasData: z.boolean(),
  services: z.array(z.object({
    namespace: z.string(), displayName: z.string(),
    cpu: z.object({ requestedMillicores: nullableNumber, usedMillicores: nullableNumber, efficiencyPct: nullableNumber }),
    memory: z.object({ requestedMiB: nullableNumber, usedMiB: nullableNumber, efficiencyPct: nullableNumber }),
    savingsPotential: z.enum(['high', 'medium', 'low', 'unknown']),
    vpaRecommendation: z.object({ cpuMillicores: nullableNumber, memoryMiB: nullableNumber }),
  })),
})
const anomalySchema = z.object({
  id: z.string(), detectedAt: z.string(), detector: z.string(), severity: z.enum(['warning', 'critical']),
  title: z.string(), rootCause: z.string().nullable(), proposalPrUrl: z.string().nullable(),
  status: z.enum(['open', 'proposed', 'approved', 'rejected', 'resolved']), estimatedMonthlySavingUsd: nullableNumber,
})
export const anomaliesSchema = z.object({ available: z.literal(true), anomalies: z.array(anomalySchema) })
const windowSchema = z.object({ requestedHours: number, availableHours: number, partial: z.boolean() })
export const aiCostsSchema = z.object({
  available: z.literal(true), collectedAt: z.string(), totalCostLast7dUsd: nullableNumber,
  totalCostLast30dUsd: nullableNumber, selfHostedPct: nullableNumber,
  coverage: z.object({
    source: z.literal('prometheus'), retentionHours: number, dataFrom: z.string(), dataTo: z.string(),
    lastSuccessfulLoad: z.string().nullable(), windows: z.object({ '24h': windowSchema, '7d': windowSchema, '30d': windowSchema }),
  }),
  agents: z.array(z.object({
    agentId: z.string(), model: z.string(), tokensLast24h: nullableNumber, tokensLast7d: nullableNumber,
    costLast24hUsd: nullableNumber, costLast7dUsd: nullableNumber, budgetMonthlyUsd: nullableNumber,
    budgetUsedPct: nullableNumber, burnRate: z.enum(['low', 'normal', 'high', 'exceeded']).nullable(), anomalyZ: nullableNumber,
  })), anomalies: z.array(anomalySchema),
})
export type FinOpsAnomaly = z.infer<typeof anomalySchema>
export type DailyCost = z.infer<typeof costsSchema>['daily'][number]
export type ServiceCost = z.infer<typeof costsSchema>['services'][number]
