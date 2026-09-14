// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFile, readdir, stat } from 'node:fs/promises'
import { relative, resolve } from 'node:path'
import vm from 'node:vm'

// This is the JavaScript every admin route must download before route-level code can run.
// The 500 KiB raw ceiling leaves measured headroom above the 452.7 KiB baseline while catching
// the regression that put the 484 KiB Sentry SDK in rootMainFiles on its own. Route-specific and
// asynchronously imported chunks are deliberately outside this budget.
const MAX_ROOT_CLIENT_BYTES = 500 * 1024
const MAX_ROUTE_CLIENT_BYTES = 500 * 1024
const nextDir = resolve('.next')
const manifest = JSON.parse(await readFile(resolve(nextDir, 'build-manifest.json'), 'utf8'))

if (!Array.isArray(manifest.rootMainFiles) || manifest.rootMainFiles.length === 0) {
  throw new Error('Client bundle budget: .next/build-manifest.json has no rootMainFiles')
}

const files = await Promise.all(manifest.rootMainFiles.map(async (file) => ({
  file,
  bytes: (await stat(resolve(nextDir, file))).size,
})))
const totalBytes = files.reduce((sum, file) => sum + file.bytes, 0)
const kib = (bytes) => (bytes / 1024).toFixed(1)

if (totalBytes > MAX_ROOT_CLIENT_BYTES) {
  const breakdown = files
    .sort((left, right) => right.bytes - left.bytes)
    .map(({ file, bytes }) => `  ${kib(bytes)} KiB  ${file}`)
    .join('\n')
  throw new Error(
    `Root client JavaScript is ${kib(totalBytes)} KiB; budget is ${kib(MAX_ROOT_CLIENT_BYTES)} KiB.\n${breakdown}`,
  )
}

console.log(
  `[client-bundle-budget] root JavaScript ${kib(totalBytes)} KiB / ${kib(MAX_ROOT_CLIENT_BYTES)} KiB`,
)

async function pageManifests(directory) {
  const found = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name)
    if (entry.isDirectory()) found.push(...await pageManifests(path))
    else if (entry.name === 'page_client-reference-manifest.js') found.push(path)
  }
  return found
}

const routes = []
for (const path of await pageManifests(resolve(nextDir, 'server/app'))) {
  const context = { globalThis: {} }
  vm.runInNewContext(await readFile(path, 'utf8'), context, { filename: path })
  const chunks = new Set()
  for (const routeManifest of Object.values(context.globalThis.__RSC_MANIFEST ?? {})) {
    for (const clientModule of Object.values(routeManifest.clientModules ?? {})) {
      for (const chunk of clientModule.chunks ?? []) {
        if (typeof chunk === 'string' && chunk.includes('/')) chunks.add(chunk)
      }
    }
  }
  let bytes = 0
  // Next URL-encodes dynamic route segments in the manifest (`%5Bid%5D`) while the emitted
  // directory retains `[id]`; decode the generated relative URL before reading the artifact.
  for (const chunk of chunks) bytes += (await stat(resolve(nextDir, decodeURIComponent(chunk)))).size
  const route = `/${relative(resolve(nextDir, 'server/app'), path)}`
    .replace(/\/page_client-reference-manifest\.js$/, '')
  routes.push({ route, bytes })
}

const oversizedRoutes = routes.filter(({ bytes }) => bytes > MAX_ROUTE_CLIENT_BYTES)
if (oversizedRoutes.length > 0) {
  const breakdown = oversizedRoutes
    .sort((left, right) => right.bytes - left.bytes)
    .map(({ route, bytes }) => `  ${kib(bytes)} KiB  ${route}`)
    .join('\n')
  throw new Error(
    `Initial route JavaScript exceeds the ${kib(MAX_ROUTE_CLIENT_BYTES)} KiB budget.\n${breakdown}`,
  )
}

const largestRoute = routes.sort((left, right) => right.bytes - left.bytes)[0]
console.log(
  `[client-bundle-budget] largest route ${largestRoute.route} ${kib(largestRoute.bytes)} KiB / ${kib(MAX_ROUTE_CLIENT_BYTES)} KiB`,
)
