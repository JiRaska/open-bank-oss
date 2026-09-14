// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFile, stat } from 'node:fs/promises'
import { resolve } from 'node:path'

// This is the JavaScript every admin route must download before route-level code can run.
// The 500 KiB raw ceiling leaves measured headroom above the 452.7 KiB baseline while catching
// the regression that put the 484 KiB Sentry SDK in rootMainFiles on its own. Route-specific and
// asynchronously imported chunks are deliberately outside this budget.
const MAX_ROOT_CLIENT_BYTES = 500 * 1024
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
