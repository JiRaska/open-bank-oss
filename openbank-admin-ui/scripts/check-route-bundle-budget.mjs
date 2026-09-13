// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

// Measured from a clean production webpack build on 2026-09-08:
// 124 route-owned chunks, 2,967,299 bytes total, largest /system/tests at 97,326 bytes.
// These are deliberately shrink-only ceilings, not aspirational performance claims.
export const MAX_ROUTE_CHUNK_BYTES = 98_000
export const MAX_TOTAL_ROUTE_CHUNK_BYTES = 3_000_000

function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name)
    return entry.isDirectory() ? walk(path) : [path]
  })
}

export function assessRouteChunks(chunks, limits = {}) {
  const maxRoute = limits.maxRoute ?? MAX_ROUTE_CHUNK_BYTES
  const maxTotal = limits.maxTotal ?? MAX_TOTAL_ROUTE_CHUNK_BYTES
  if (chunks.length === 0) return { ok: false, total: 0, violations: ['no route chunks found'] }

  const total = chunks.reduce((sum, chunk) => sum + chunk.bytes, 0)
  const violations = chunks
    .filter(chunk => chunk.bytes > maxRoute)
    .map(chunk => `${chunk.route}: ${chunk.bytes} B exceeds ${maxRoute} B`)
  if (total > maxTotal) violations.push(`route-owned total: ${total} B exceeds ${maxTotal} B`)
  return { ok: violations.length === 0, total, violations }
}

export function collectRouteChunks(appChunksDirectory) {
  return walk(appChunksDirectory)
    .filter(path => /(?:^|[/\\])page-[^.]+\.js$/.test(path))
    .map(path => {
      const routeDirectory = dirname(relative(appChunksDirectory, path))
      return {
        route: routeDirectory === '.' ? '/' : `/${routeDirectory.split(sep).join('/')}`,
        bytes: statSync(path).size,
      }
    })
    .sort((left, right) => right.bytes - left.bytes || left.route.localeCompare(right.route))
}

function selfTest() {
  const green = assessRouteChunks([{ route: '/a', bytes: 80 }, { route: '/b', bytes: 20 }], { maxRoute: 80, maxTotal: 100 })
  const routeRed = assessRouteChunks([{ route: '/a', bytes: 81 }], { maxRoute: 80, maxTotal: 100 })
  const totalRed = assessRouteChunks([{ route: '/a', bytes: 60 }, { route: '/b', bytes: 41 }], { maxRoute: 80, maxTotal: 100 })
  const missing = assessRouteChunks([], { maxRoute: 80, maxTotal: 100 })
  if (!green.ok || routeRed.ok || totalRed.ok || missing.ok) throw new Error('route bundle budget self-test failed')
  console.log('route bundle budget self-test: 4/4 passed')
}

function main() {
  if (process.argv.includes('--self-test')) return selfTest()
  const moduleRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const appChunksDirectory = resolve(process.env.OPENBANK_ADMIN_UI_APP_CHUNKS ?? join(moduleRoot, '.next/static/chunks/app'))
  let chunks
  try {
    chunks = collectRouteChunks(appChunksDirectory)
  } catch (error) {
    console.error(`route bundle budget: cannot read ${appChunksDirectory}: ${error.message}`)
    process.exitCode = 1
    return
  }
  const result = assessRouteChunks(chunks)
  console.log(`route bundle budget: ${chunks.length} route-owned chunks, ${result.total} B total (ceiling ${MAX_TOTAL_ROUTE_CHUNK_BYTES} B)`)
  for (const chunk of chunks.slice(0, 10)) console.log(`  ${chunk.route}: ${chunk.bytes} B`)
  if (!result.ok) {
    for (const violation of result.violations) console.error(`ERROR: ${violation}`)
    process.exitCode = 1
    return
  }
  console.log(`route bundle budget: OK — largest ${chunks[0].bytes} B (ceiling ${MAX_ROUTE_CHUNK_BYTES} B)`)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
