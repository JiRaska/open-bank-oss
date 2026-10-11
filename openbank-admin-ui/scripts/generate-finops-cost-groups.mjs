// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Bake the FinOps showback taxonomy from its governed source. Never maintain a second
// service-to-flow list in the UI: a short list is still a valid-looking but false report.
import { readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseDocument } from 'yaml'

const here = path.dirname(fileURLToPath(import.meta.url))
const arg = (flag, fallback) => {
  const i = process.argv.indexOf(flag)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}
const repo = path.resolve(arg('--repo', path.resolve(here, '..', '..')))
const out = path.resolve(arg('--out', path.resolve(here, '..', 'finops-cost-groups.json')))
const moneyPathOut = path.resolve(arg('--money-path-out', path.resolve(here, '..', 'finops-money-path.json')))

function parseRules(source) {
  const document = parseDocument(source, { uniqueKeys: true })
  if (document.errors.length) throw new Error(`invalid rules.yaml: ${document.errors[0].message}`)
  return document.toJS()
}

export function deriveCostGroups(source) {
  const groups = parseRules(source)?.finops_cost_groups
  if (!groups || typeof groups !== 'object' || Array.isArray(groups) || !Object.keys(groups).length) {
    throw new Error('rules.yaml:finops_cost_groups must be a nonempty mapping')
  }
  return Object.entries(groups).map(([id, entry]) => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) throw new Error(`invalid cost group ${id}`)
    const { label_en: labelEn, label_cs: labelCs, services, regulatory_ref: regulatoryRef } = entry
    if (![id, labelEn, labelCs].every(value => typeof value === 'string' && value.trim())) {
      throw new Error(`cost group ${id} requires nonempty id and bilingual labels`)
    }
    if (!Array.isArray(services) || !services.length || !services.every(value => typeof value === 'string' && value.trim())) {
      throw new Error(`cost group ${id} requires a nonempty service list`)
    }
    if (new Set(services).size !== services.length) throw new Error(`cost group ${id} has duplicate services`)
    if (regulatoryRef !== undefined && typeof regulatoryRef !== 'string') throw new Error(`invalid regulatory ref for ${id}`)
    return { id, labelEn, labelCs, services, ...(regulatoryRef === undefined ? {} : { regulatoryRef }) }
  })
}

/** Service-card ids omit the openbank- prefix and conventional -service suffix. */
export function deriveMoneyPathT0(source) {
  const declared = parseRules(source)?.money_path_services
  if (!Array.isArray(declared) || !declared.length ||
      declared.some(name => typeof name !== 'string' || !/^openbank-[a-z0-9]+(?:-[a-z0-9]+)*$/.test(name)) ||
      new Set(declared).size !== declared.length) {
    throw new Error('rules.yaml:money_path_services must be a nonempty unique service list')
  }
  const ids = declared.map(name => name.replace(/^openbank-/, '').replace(/-service$/, ''))
  if (new Set(ids).size !== ids.length) throw new Error('money-path service-card ids collide')
  return ids.sort()
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const source = readFileSync(path.join(repo, 'openbank-libs/governance/rules.yaml'), 'utf8')
    const groups = deriveCostGroups(source)
    const moneyPath = deriveMoneyPathT0(source)
    writeFileSync(out, JSON.stringify(groups, null, 2) + '\n')
    writeFileSync(moneyPathOut, JSON.stringify(moneyPath, null, 2) + '\n')
    console.log(`finops-cost-groups: ${groups.length} derived flows, ${moneyPath.length} money-path T0 services`)
  } catch (error) {
    console.error(`finops-cost-groups generation failed: ${error instanceof Error ? error.message : String(error)}`)
    process.exitCode = 1
  }
}
