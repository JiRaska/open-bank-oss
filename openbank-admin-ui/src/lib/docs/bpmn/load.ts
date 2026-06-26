// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// ---------------------------------------------------------------------------
// BPMN process-diagram loader (server-only). Reads src/content/bpmn/<slug>.yaml,
// parses it and validates against BpmnProcessSchema — so a malformed or drifted
// manifest fails `next build` (the CI gate) rather than rendering broken.
//
// The slug is the filename, injected here, so it is never authored twice. Order
// is preserved as authored via an explicit `ORDER` list (the page tabs read it),
// with any not-yet-listed slug appended alphabetically so a new manifest still
// shows up without a code change.
// ---------------------------------------------------------------------------

import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { parse } from 'yaml'
import { BpmnProcessSchema, type BpmnProcess } from './schema'

const BPMN_DIR = join(process.cwd(), 'src', 'content', 'bpmn')

// Tab order shown in the UI (business-process narrative order). A slug not in
// this list is still loaded — appended after, alphabetically.
const ORDER = [
  'account-opening',
  'sepa-payment',
  'kyc-process',
  'aml-screening',
  'card-issuance',
  'international-wire',
  'sepa-instant',
  'sdd',
  'dispute',
  'standing-order',
  'interest',
  'lending',
  'psd2-tpp',
  'sca-push',
  'account-closure',
  'closings',
]

export function loadBpmnProcess(slug: string): BpmnProcess {
  const raw = readFileSync(join(BPMN_DIR, `${slug}.yaml`), 'utf8')
  const data = parse(raw) as Record<string, unknown>
  // `slug` comes from the filename — the single source — then validate the whole.
  return BpmnProcessSchema.parse({ ...data, slug })
}

export function listBpmnSlugs(): string[] {
  const found = readdirSync(BPMN_DIR)
    .filter((f) => f.endsWith('.yaml'))
    .map((f) => f.replace(/\.yaml$/, ''))
  const ranked = (s: string) => {
    const i = ORDER.indexOf(s)
    return i === -1 ? ORDER.length : i
  }
  return found.sort((a, b) => ranked(a) - ranked(b) || a.localeCompare(b))
}

export function loadAllBpmnProcesses(): BpmnProcess[] {
  return listBpmnSlugs().map(loadBpmnProcess)
}
