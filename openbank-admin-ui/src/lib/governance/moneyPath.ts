// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { promises as fs } from 'fs'
import path from 'path'
import { parse as parseYaml } from 'yaml'

/** The governance rule is authoritative; a missing or malformed source must not downgrade a service. */
export function parseMoneyPathServices(source: string): Set<string> {
  const rules = parseYaml(source) as Record<string, unknown> | null
  const declared = rules?.money_path_services
  if (!Array.isArray(declared) || declared.length === 0 ||
      declared.some(name => typeof name !== 'string' || !/^openbank-[a-z0-9-]+$/.test(name)) ||
      new Set(declared).size !== declared.length) {
    throw new Error('Invalid governance money_path_services declaration')
  }
  return new Set(declared.map(name => name.replace(/^openbank-/, '')))
}

export async function loadMoneyPathServices(): Promise<Set<string>> {
  const candidates = [
    process.env.OPENBANK_GOVERNANCE_DOCS && path.join(process.env.OPENBANK_GOVERNANCE_DOCS, 'rules.yaml'),
    path.resolve(process.cwd(), 'governance-bundle', 'rules.yaml'),
    path.resolve(process.cwd(), '..', 'openbank-libs', 'governance', 'rules.yaml'),
  ].filter((candidate): candidate is string => Boolean(candidate))
  for (const candidate of candidates) {
    try { return parseMoneyPathServices(await fs.readFile(candidate, 'utf-8')) }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') continue
      throw error
    }
  }
  throw new Error('Governance money_path_services source unavailable')
}
