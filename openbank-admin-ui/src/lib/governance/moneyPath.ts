// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-only: catalog.json is generated from rules.yaml by generate-catalog.mjs and baked into
// the image. A missing or malformed catalog must not quietly mark money-path services as ordinary.
import { readFileSync } from 'node:fs'
import path from 'node:path'

export function moneyPathServices(): Set<string> {
  const file = process.env.OPENBANK_CATALOG ?? path.resolve(process.cwd(), 'catalog.json')
  const catalog = JSON.parse(readFileSync(file, 'utf-8')) as { services?: Array<{ name?: unknown; moneyPath?: unknown }> }
  if (!Array.isArray(catalog.services)) throw new Error('derived service catalog has no services array')

  const moneyPath = new Set<string>()
  for (const service of catalog.services) {
    if (!service || typeof service.name !== 'string' || typeof service.moneyPath !== 'boolean') {
      throw new Error('derived service catalog lacks a service name or money-path classification')
    }
    if (service.moneyPath) moneyPath.add(service.name.replace(/^openbank-/, ''))
  }
  if (moneyPath.size === 0) throw new Error('derived service catalog has no money-path services')
  return moneyPath
}
