// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
//   node src/cli.ts --list [--trace]             ACTIVE vs PENDING report; --trace maps ADR delivery-check lines
//   node src/cli.ts --run [--include-pending] [--only <id>[,<id>]] [--json]
//   node src/cli.ts --journey                    layer-2 connect → read → propose
import { ATTACKS, contextFor, exitCodeFor, runAttacks } from './attacks.ts'
import type { AttackCase } from './attacks.ts'
import { loadConfig } from './config.ts'
import { traceReport } from './delivery-checks.ts'
import { runJourney } from './journey.ts'

const args = process.argv.slice(2)
const has = (f: string): boolean => args.includes(f)
const valueOf = (f: string): string | undefined => { const i = args.indexOf(f); return i >= 0 ? args[i + 1] : undefined }

export function listReport(cases: AttackCase[]): string {
  const active = cases.filter((c) => c.status === 'ACTIVE')
  const pending = cases.filter((c) => c.status === 'PENDING')
  const lines = [`ACTIVE ${active.length}  PENDING ${pending.length} (with body ${pending.filter((c) => c.run).length})  TOTAL ${cases.length}`, '']
  for (const c of cases) {
    lines.push(`${c.status.padEnd(7)} ${c.id}`)
    lines.push(`        ${c.source} — expects: ${c.expected}`)
    if (c.status === 'PENDING') lines.push(`        enabled by: ${c.enabledBy}${c.run ? '' : ` | body needs: ${c.needs}`}`)
  }
  return lines.join('\n')
}

async function main(): Promise<number> {
  if (has('--list')) {
    if (has('--json')) {
      process.stdout.write(`${JSON.stringify(ATTACKS.map(({ run, ...c }) => ({ ...c, hasBody: Boolean(run) })), null, 2)}\n`)
    } else {
      process.stdout.write(`${listReport(ATTACKS)}\n`)
      if (has('--trace')) process.stdout.write(`\nDelivery-check trace (ADR line → cases | [other layer])\n${traceReport()}\n`)
    }
    return 0
  }
  const cfg = loadConfig()
  if (has('--journey')) {
    try {
      await runJourney(cfg, (r) => process.stdout.write(`${r.ok ? 'OK  ' : 'FAIL'} ${r.step}${r.detail ? ` — ${r.detail}` : ''}\n`))
      return 0
    } catch (e) {
      process.stderr.write(`journey failed: ${(e as Error).message}\n`)
      return 1
    }
  }
  if (has('--run')) {
    const only = valueOf('--only')?.split(',')
    const cases = only ? ATTACKS.filter((c) => only.includes(c.id)) : ATTACKS
    if (only && cases.length !== only.length) throw new Error(`unknown case id in --only ${only.join(',')}`)
    const results = await runAttacks(cases, contextFor(cfg), { includePending: has('--include-pending') })
    if (has('--json')) process.stdout.write(`${JSON.stringify(results, null, 2)}\n`)
    else for (const r of results) process.stdout.write(`${r.outcome.padEnd(18)} ${r.id}${r.detail ? ` — ${r.detail}` : ''}\n`)
    const n = (o: string) => results.filter((r) => r.outcome === o).length
    process.stdout.write(`\nPASS ${n('PASS')}  FAIL ${n('FAIL')}  SKIP ${n('SKIP')}  PENDING ${n('PENDING') + n('PENDING-WOULD-PASS') + n('PENDING-FAILS')}\n`)
    return exitCodeFor(results)
  }
  process.stderr.write('usage: cli.ts --list | --run [--include-pending] [--only ids] [--json] | --journey\n')
  return 2
}

if (import.meta.main) process.exitCode = await main()
