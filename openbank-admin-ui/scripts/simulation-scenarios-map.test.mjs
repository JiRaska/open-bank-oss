// SPDX-License-Identifier: Apache-2.0
// Regression guard for the simulationScenarios map in collect-test-intelligence.mjs.
//
// The map is the ONLY attribution path from a simulation suite's JUnit XML to per-component
// Test Intelligence cells. A scenario class that exists but is not mapped is silently counted
// only in the harness-wide openbank-simulation total, so the domain rows it proves stay
// "missing" in the matrix — the drift this test pins down (goal #14366 sebereview D1).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(HERE, '..', '..')
const COLLECTOR = path.join(HERE, 'collect-test-intelligence.mjs')
const SIMULATION_ROOT = path.join(REPO_ROOT, 'openbank-simulation', 'src', 'test', 'kotlin', 'com', 'openbank', 'simulation')

// The collector runs main() on import, so the map is parsed from source instead.
const source = fs.readFileSync(COLLECTOR, 'utf8')
const block = source.match(/const simulationScenarios = \[([\s\S]*?)\n\]/)?.[1]
assert.ok(block, 'simulationScenarios map not found in collect-test-intelligence.mjs')

const mapped = [...block.matchAll(/\{ classname: '([^']+)', components: \[([^\]]*)\], scope: '([^']+)' \}/g)]
  .map(match => ({
    classname: match[1],
    components: match[2].split(',').map(item => item.trim().replace(/^'|'$/g, '')).filter(Boolean),
    scope: match[3],
  }))

function* walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) yield* walk(full)
    else if (entry.name.endsWith('.kt')) yield full
  }
}

// Scenario attribution covers the `scenario` subpackage, plus the harness-wide
// DstSimulationTest sweep which the map attributes to the payment/ledger path.
// Engine, invariant and model tests are openbank-simulation's own unit layer,
// not per-component simulation evidence, so they must NOT appear in the map.
const scenarioDir = path.join(SIMULATION_ROOT, 'scenario')
const actual = [
  ...[...walk(scenarioDir)].map(file => path.join('scenario', path.basename(file))),
  ...fs.readdirSync(SIMULATION_ROOT)
    .filter(name => name === 'DstSimulationTest.kt')
    .map(name => name),
]
  .map(relative => {
    const file = path.join(SIMULATION_ROOT, relative)
    const text = fs.readFileSync(file, 'utf8')
    const className = path.basename(file, '.kt')
    const declared = new RegExp(`\\bclass\\s+${className}\\b`).test(text)
    assert.ok(declared, `${path.basename(file)} does not declare its top-level class`)
    return `com.openbank.simulation.${relative.replace(/\.kt$/, '').split(path.sep).join('.')}`
  })

test('every simulation scenario class is attributed in the collector map', () => {
  const mappedClasses = new Set(mapped.map(entry => entry.classname))
  const unmapped = actual.filter(classname => !mappedClasses.has(classname))
  assert.deepEqual(unmapped, [],
    `scenario classes missing from simulationScenarios: ${unmapped.join(', ')}`)
})

test('the collector map names only scenario classes that exist', () => {
  const actualClasses = new Set(actual)
  const stale = mapped.map(entry => entry.classname).filter(classname => !actualClasses.has(classname))
  assert.deepEqual(stale, [],
    `simulationScenarios entries with no scenario class: ${stale.join(', ')}`)
})

test('every map entry attributes at least one component and carries a scope', () => {
  for (const entry of mapped) {
    assert.ok(entry.components.length > 0, `${entry.classname} attributes no component`)
    assert.ok(entry.components.every(component => /^openbank-[a-z0-9-]+$/.test(component)),
      `${entry.classname} has a malformed component name`)
    assert.ok(entry.scope.length > 0, `${entry.classname} has an empty scope`)
  }
})
