// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Entry point for the generated catalogue reports: the shared contract lives in
// `generators/core.ts`, one module per report family next to it. The list of sources a report read is
// recorded from the calls it actually made, never kept by hand.

import { amlSar } from '@/lib/regulatory/generators/aml'
import {
  periodBounds, periodKindOf,
  type GeneratedReport, type GeneratedReportId, type GeneratedTemplate, type GenerationConfig, type ReportSourceClient,
} from '@/lib/regulatory/generators/core'
import { doraIct } from '@/lib/regulatory/generators/dora'
import { ecbPayments } from '@/lib/regulatory/generators/ecb'
import { fatca } from '@/lib/regulatory/generators/fatca'
import { sdatAccounts, sdatPayments } from '@/lib/regulatory/generators/sdat'

export * from '@/lib/regulatory/generators/core'

function recording(client: ReportSourceClient, seen: Set<string>): ReportSourceClient {
  return {
    get: (service, path, query) => { seen.add(service); return client.get(service, path, query) },
    request: (service, method, path, options) => { seen.add(service); return client.request(service, method, path, options) },
  }
}

export async function generateReport(
  reportId: GeneratedReportId,
  period: string,
  client: ReportSourceClient,
  config: GenerationConfig,
  now: Date = new Date(),
): Promise<GeneratedReport> {
  const bounds = periodBounds(periodKindOf(reportId), period)
  if (!bounds) throw new RangeError(`invalid period '${period}' for ${reportId}`)
  const { start, end } = bounds
  const seen = new Set<string>()
  const source = recording(client, seen)
  let templates: GeneratedTemplate[]
  switch (reportId) {
    case 'sdat-payments': templates = await sdatPayments(source, period, start, end); break
    case 'sdat-accounts': templates = await sdatAccounts(source, period, end); break
    case 'aml-far': templates = await amlSar(source, period, start, end, config); break
    case 'ecb-payments': templates = await ecbPayments(source, period, start, end, config); break
    case 'fatca-report': templates = await fatca(source, period, config); break
    case 'dora-ict': templates = await doraIct(source, period, start, end); break
  }
  return {
    reportId,
    period,
    periodStart: start,
    periodEnd: end,
    closed: end < now.toISOString().slice(0, 10),
    generatedAt: now.toISOString(),
    sources: [...seen].sort(),
    templates,
  }
}
