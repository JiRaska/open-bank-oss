// SPDX-License-Identifier: Apache-2.0
// Exact synthetic-record contract for the authenticated, read-only inbox probe.
import { realpath, writeFile } from 'node:fs/promises'
import { basename, dirname, isAbsolute, join, relative } from 'node:path'

export function verifyApprovalInboxEvidence(payload, fixture) {
  if (payload?.sources?.billing !== 'ok') throw new Error('billing approval source did not answer successfully')
  const matches = Array.isArray(payload.items)
    ? payload.items.filter(item => item?.domain === 'billing' && item.id === fixture.id)
    : []
  if (matches.length !== 1 || matches[0].action !== fixture.action ||
      matches[0].resourceId !== fixture.resourceId || matches[0].maker !== fixture.makerId ||
      Date.parse(matches[0].proposedAt) !== Date.parse(fixture.createdAt)) {
    throw new Error('sandbox fixture did not traverse billing provider and approval inbox BFF unchanged')
  }
  return matches[0]
}

// The fixture wrapper suppresses child stdout to avoid leaking browser/session data.
// Persist only this fixed, non-secret read result; wrapper cleanup is a separate gate.
export async function writeApprovalReadEvidence(path, buildSha, fixtureId) {
  if (!isAbsolute(path)) throw new Error('approval evidence path must be absolute')
  const parent = await realpath(dirname(path))
  const repository = await realpath(new URL('../../', import.meta.url).pathname)
  const withinRepo = relative(repository, parent)
  if (withinRepo === '' || (!withinRepo.startsWith('..') && !isAbsolute(withinRepo))) {
    throw new Error('approval evidence must stay outside the repository')
  }
  const evidence = {
    journey: 'approval-inbox-authenticated-read', result: 'read-verified',
    buildSha, fixtureId, source: 'billing',
    assertions: ['operator-session', 'bff-200', 'provider-ok', 'mapping', 'rendered-row', 'read-only'],
  }
  await writeFile(join(parent, basename(path)), `${JSON.stringify(evidence)}\n`, { flag: 'wx', mode: 0o600 })
}
