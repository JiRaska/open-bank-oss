// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { readSdlcEvidence } from './lib/sdlc-evidence.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '../..')
const evidence = readSdlcEvidence(root)
// Immutable source links refer to the checked-out revision, never to an assumed live main.
const buildRevision = process.env.BUILD_GIT_SHA
let revision = buildRevision && /^[a-f0-9]{7,40}$/.test(buildRevision) ? buildRevision : null
if (!revision && !buildRevision) {
  try { revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim() } catch { /* A source archive has no Git metadata. */ }
}
if (revision && !/^[a-f0-9]{7,40}$/.test(revision)) throw new Error('SDLC evidence: invalid source revision')
writeFileSync(resolve(here, '../sdlc-evidence.json'), JSON.stringify({ revision, ...evidence }, null, 2) + '\n')
console.log(`sdlc-evidence: ${revision?.slice(0, 8) ?? 'unavailable'}, ledger ${evidence.ledger.replicas} replica(s), traffic routing ${evidence.ledger.trafficRouting}`)
