// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Writes platform-versions.json: the versions the admin UI shows, PARSED from the infra sources
// of truth (see scripts/lib/platform-versions.mjs). Exits non-zero if any value cannot be parsed;
// it never emits a default. Deterministic (no wall-clock), so regeneration never churns.
import { writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { derivePlatformVersions } from './lib/platform-versions.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const arg = (flag, fallback) => {
  const i = process.argv.indexOf(flag)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}
const repo = path.resolve(arg('--repo', path.resolve(here, '..', '..')))
const out = path.resolve(arg('--out', path.resolve(here, '..', 'platform-versions.json')))

try {
  const data = derivePlatformVersions(repo)
  writeFileSync(out, JSON.stringify(data, null, 2) + '\n')
  console.log(`platform-versions: EKS ${data.kubernetesVersion}, ${data.nodeGroup.instanceType} x${data.nodeGroup.desiredSize}, Loki chart ${data.loki.chartVersion} / app ${data.loki.appVersion}`)
} catch (err) {
  console.error(`platform-versions generator FAILED: ${err.message}`)
  process.exit(1)
}
