// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// DORA ICT incident report (EU 2022/2554).

import {
  ReportSourceError, activeAccounts, amount, completedTransactions, count, currencyCells, gap, isPayment, malformed, mapLimit, num,
  offsetRoster, round2, rows, str, sumByCurrency, template, text, within,
  type GeneratedCell, type GeneratedTemplate, type GenerationConfig, type ReportSourceClient, type Row,
} from '@/lib/regulatory/generators/core'

const DORA_MAJOR = new Set(['P1_CRITICAL', 'P2_HIGH'])

/** DORA — ICT incidents detected in the period; P1/P2 are the major (reportable) ones. */
export async function doraIct(client: ReportSourceClient, period: string, start: string, end: string): Promise<GeneratedTemplate[]> {
  const incidents = (await offsetRoster(client, 'security-scanner-service', '/api/v1/ict-incidents', {}, 200))
    .filter(i => within(str(i, 'detectedAt'), start, end))
    .sort((a, b) => (str(a, 'detectedAt') ?? '').localeCompare(str(b, 'detectedAt') ?? ''))
  const major = incidents.filter(i => DORA_MAJOR.has(str(i, 'severity') ?? ''))
  const minutes = (from: string | null, to: string | null) => (from && to ? Math.round((Date.parse(to) - Date.parse(from)) / 60_000) : null)

  const summary = template('DORA-00', period, [
    count('r0010', 'Počet ICT incidentů v období', incidents.length),
    count('r0020', 'z toho závažných (P1/P2) — podléhají hlášení', major.length),
    count('r0030', 'z toho již nahlášeno regulátorovi', major.filter(i => i.reportedToRegulator === true).length),
    count('r0040', 'Nevyřešené závažné incidenty', major.filter(i => !['RESOLVED', 'CLOSED'].includes(str(i, 'status') ?? '')).length),
  ])
  const lines = major.map((inc, i) => {
    const n = String(i + 1).padStart(3, '0')
    const services = Array.isArray(inc.affectedServices) ? (inc.affectedServices as unknown[]).filter(s => typeof s === 'string').join(', ') : ''
    const rto = num(inc, 'rtoMinutes')
    const rpo = num(inc, 'rpoMinutes')
    const resolvedIn = minutes(str(inc, 'detectedAt'), str(inc, 'resolvedAt'))
    return [
      text(`l${n}`, 'id', `#${n} · Incident`, `${str(inc, 'id') ?? '—'} — ${str(inc, 'title') ?? ''}`),
      text(`l${n}`, 'class', `#${n} · Klasifikace incidentu`, `${str(inc, 'severity') ?? '—'} / ${str(inc, 'category') ?? '—'} / ${str(inc, 'status') ?? '—'}`),
      text(`l${n}`, 'impact', `#${n} · Dopad (dotčené služby)`, services || null),
      text(`l${n}`, 'timeline', `#${n} · Zjištěno / omezeno / vyřešeno`, [str(inc, 'detectedAt'), str(inc, 'containedAt') ?? '—', str(inc, 'resolvedAt') ?? '—'].join(' / ')),
      text(`l${n}`, 'rto', `#${n} · RTO/RPO (min)`, `${rto ?? '—'} / ${rpo ?? '—'}${resolvedIn !== null ? ` · doba řešení ${resolvedIn} min` : ''}`),
      text(`l${n}`, 'cause', `#${n} · Příčina a nápravná opatření`, str(inc, 'description')),
      text(`l${n}`, 'reported', `#${n} · Hlášeno regulátorovi`, inc.reportedToRegulator === true ? `ano (${str(inc, 'regulatoryReportId') ?? '—'})` : 'ne'),
    ]
  })
  return [summary, template('DORA-01', period, lines.length ? lines.flat() : [text('l000', 'none', 'Závažné incidenty', 'V období nenastal žádný závažný ICT incident')])]
}
