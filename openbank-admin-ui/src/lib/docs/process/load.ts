// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// ---------------------------------------------------------------------------
// Process manifest loader (server-only). Reads src/content/processes/<slug>.yaml,
// parses it and validates against ProcessSchema — so a malformed or drifted
// manifest fails `next build` (the CI gate) rather than rendering broken.
//
// The slug is the filename, injected here, so it is never authored twice.
// ---------------------------------------------------------------------------

import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { parse } from 'yaml'
import { ProcessSchema, type Process } from './schema'

const PROCESS_DIR = join(process.cwd(), 'src', 'content', 'processes')

export function loadProcess(slug: string): Process {
  const raw = readFileSync(join(PROCESS_DIR, `${slug}.yaml`), 'utf8')
  const data = parse(raw) as Record<string, unknown>
  // `slug` comes from the filename — the single source — then validate the whole.
  return ProcessSchema.parse({ ...data, slug })
}

export function listProcessSlugs(): string[] {
  return readdirSync(PROCESS_DIR)
    .filter((f) => f.endsWith('.yaml'))
    .map((f) => f.replace(/\.yaml$/, ''))
    .sort()
}

export function loadAllProcesses(): Process[] {
  return listProcessSlugs().map(loadProcess)
}
