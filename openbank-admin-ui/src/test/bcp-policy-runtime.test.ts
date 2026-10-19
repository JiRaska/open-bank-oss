// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'

const adminUi = path.resolve(__dirname, '../..')
const policyFile = path.resolve(adminUi, '../docs/bcp/bcp-policy.md')
const previousPolicyFile = process.env.OPENBANK_BCP_POLICY_FILE
const tempDirectories: string[] = []

async function importPolicyHandler() {
  vi.resetModules()
  return (await import('@/app/api/bcp/policy/route')).GET
}

async function getPolicyResponse() {
  return (await importPolicyHandler())()
}

function createPolicyFile(markdown?: string) {
  const directory = mkdtempSync(path.join(tmpdir(), 'openbank-bcp-policy-'))
  tempDirectories.push(directory)
  const filename = path.join(directory, 'bcp-policy.md')
  if (markdown !== undefined) writeFileSync(filename, markdown)
  return { filename }
}

afterEach(() => {
  if (previousPolicyFile === undefined) delete process.env.OPENBANK_BCP_POLICY_FILE
  else process.env.OPENBANK_BCP_POLICY_FILE = previousPolicyFile
  for (const directory of tempDirectories.splice(0)) rmSync(directory, { recursive: true, force: true })
  vi.restoreAllMocks()
  vi.resetModules()
})

describe('BCP policy runtime source', () => {
  it('loads the configured canonical file path used by the standalone image', async () => {
    process.env.OPENBANK_BCP_POLICY_FILE = policyFile

    const response = await getPolicyResponse()
    const body = await response.json() as { available: boolean; policy: { tiers: { key: string }[] } | null }

    expect(response.status).toBe(200)
    expect(body.available).toBe(true)
    expect(body.policy?.tiers.map(tier => tier.key)).toEqual(['T0', 'T1', 'T2', 'T3'])
  })

  it('uses the canonical repository file in host development when no image path is configured', async () => {
    delete process.env.OPENBANK_BCP_POLICY_FILE

    const response = await getPolicyResponse()
    const body = await response.json() as { available: boolean; policy: { source: string } | null }

    expect(response.status).toBe(200)
    expect(body.available).toBe(true)
    expect(body.policy?.source).toBe('docs/bcp/bcp-policy.md §2')
  })

  it('fails closed when the configured standalone-image file is absent', async () => {
    process.env.OPENBANK_BCP_POLICY_FILE = path.join(adminUi, 'missing-bcp-policy.md')

    const response = await getPolicyResponse()
    const body = await response.json() as { available: boolean; policy: unknown }

    expect(response.status).toBe(503)
    expect(body).toEqual({ available: false, policy: null })
  })

  it('recovers after a missing-file read expires without restarting the route process', async () => {
    const source = createPolicyFile()
    process.env.OPENBANK_BCP_POLICY_FILE = source.filename
    const startedAt = Date.now()
    vi.spyOn(Date, 'now').mockReturnValue(startedAt)
    const GET = await importPolicyHandler()

    expect((await GET()).status).toBe(503)
    writeFileSync(source.filename, readFileSync(policyFile, 'utf8'))
    expect((await GET()).status).toBe(503)

    vi.mocked(Date.now).mockReturnValue(startedAt + 60_001)
    const response = await GET()
    const body = await response.json() as { available: boolean; policy: { tiers: { key: string }[] } | null }
    expect(response.status).toBe(200)
    expect(body.available).toBe(true)
    expect(body.policy?.tiers.map(tier => tier.key)).toEqual(['T0', 'T1', 'T2', 'T3'])
  })

  it('reloads source edits after TTL while serving the cached policy within TTL', async () => {
    const originalMarkdown = readFileSync(policyFile, 'utf8')
    const source = createPolicyFile(originalMarkdown)
    process.env.OPENBANK_BCP_POLICY_FILE = source.filename
    const startedAt = Date.now()
    vi.spyOn(Date, 'now').mockReturnValue(startedAt)
    const GET = await importPolicyHandler()
    const readT0Target = async () => {
      const response = await GET()
      const body = await response.json() as { policy: { tiers: { key: string; rto: string }[] } | null }
      return body.policy?.tiers.find(tier => tier.key === 'T0')?.rto
    }

    expect(await readT0Target()).toBe('4 h')
    writeFileSync(source.filename, originalMarkdown.replace('| 4 h | 15 min |', '| 5 h | 15 min |'))
    expect(await readT0Target()).toBe('4 h')

    vi.mocked(Date.now).mockReturnValue(startedAt + 60_001)
    expect(await readT0Target()).toBe('5 h')
  })

  it('keeps the policy source in Docker build context and copies it to the configured standalone path', () => {
    const dockerfile = readFileSync(path.join(adminUi, 'Dockerfile'), 'utf8')
    const dockerignore = readFileSync(path.resolve(adminUi, '../.dockerignore'), 'utf8')

    expect(dockerignore).toContain('!docs/bcp/bcp-policy.md')
    expect(dockerfile).toContain('COPY --chmod=0644 docs/bcp/bcp-policy.md ./bcp-policy.md')
    expect(dockerfile).toContain('ENV OPENBANK_BCP_POLICY_FILE=/app/bcp-policy.md')
  })
})
