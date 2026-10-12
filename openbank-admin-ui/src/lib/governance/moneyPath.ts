// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-only: catalog.json is generated from rules.yaml by generate-catalog.mjs and baked into
// the image. A missing or malformed catalog must not quietly mark money-path services as ordinary.
import { readFileSync } from 'node:fs'
import path from 'node:path'

type DerivedCatalog = { services?: Array<{ name?: unknown; moneyPath?: unknown }>; availabilitySlo?: unknown }

function readCatalog(): DerivedCatalog {
  const file = process.env.OPENBANK_CATALOG ?? path.resolve(process.cwd(), 'catalog.json')
  return JSON.parse(readFileSync(file, 'utf-8')) as DerivedCatalog
}

export function moneyPathServices(): Set<string> {
  const catalog = readCatalog()
  if (!Array.isArray(catalog.services)) throw new Error('derived service catalog has no services array')

  const moneyPath = new Set<string>()
  for (const service of catalog.services) {
    if (!service || typeof service.name !== 'string' || !/^openbank-[a-z0-9]+(?:-[a-z0-9]+)*$/.test(service.name)
        || typeof service.moneyPath !== 'boolean') {
      throw new Error('derived service catalog lacks a service name or money-path classification')
    }
    if (service.moneyPath) moneyPath.add(service.name.replace(/^openbank-/, ''))
  }
  if (moneyPath.size === 0) throw new Error('derived service catalog has no money-path services')
  return moneyPath
}

/** The declared per-service availability SLOs. Actual traffic evidence comes from Prometheus. */
export function moneyPathAvailabilitySlo(): { names: string[]; target: number; window: string } {
  const names = [...moneyPathServices()]
    .map(service => `openbank-${service.replace(/-service$/, '')}-availability`)
    .sort()
  if (new Set(names).size !== names.length) throw new Error('derived SLO names are not unique')
  const value = readCatalog().availabilitySlo
  if (!value || typeof value !== 'object') throw new Error('derived catalog has no availability SLO')
  const slo = value as { target?: unknown; window?: unknown }
  if (typeof slo.target !== 'number' || !Number.isFinite(slo.target)
      || slo.target <= 0 || slo.target >= 1
      || typeof slo.window !== 'string' || !/^\d+d$/.test(slo.window)) {
    throw new Error('derived catalog has an invalid availability SLO')
  }
  return { names, target: slo.target, window: slo.window }
}
