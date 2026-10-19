// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { isAdminUiContentSnapshot, ADMIN_UI_CONTENT_MAX_BYTES } from '../src/lib/evidence/contentSnapshot.ts'

const SOURCE_SHA = /^[0-9a-f]{40}$/
const MAX_SOURCE_FILE_BYTES = 256 * 1024
const HERE = fileURLToPath(new URL('.', import.meta.url))
const REPO = resolve(HERE, '../..')

function parseArgs(argv) {
  const flags = new Map()
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index]
    const value = argv[index + 1]
    if (!['--source-sha', '--out'].includes(flag) || !value || value.startsWith('--') || flags.has(flag)) {
      throw new Error(`invalid argument ${flag ?? ''}`)
    }
    flags.set(flag, value)
  }
  const sourceSha = flags.get('--source-sha')
  const out = flags.get('--out')
  if (!sourceSha || !SOURCE_SHA.test(sourceSha)) throw new Error('--source-sha must be a full lowercase 40-character Git SHA')
  if (!out) throw new Error('--out is required')
  return { sourceSha, out: resolve(out) }
}

function readUtf8(repoRoot, relative) {
  const filename = resolve(repoRoot, relative)
  if (!filename.startsWith(`${resolve(repoRoot)}/`)) throw new Error(`source path escaped repository: ${relative}`)
  const stat = statSync(filename)
  if (!stat.isFile() || stat.size > MAX_SOURCE_FILE_BYTES) throw new Error(`source file is not a bounded regular file: ${relative}`)
  return readFileSync(filename, 'utf8')
}

function filesIn(repoRoot, directory, extension, pathFor) {
  const root = resolve(repoRoot, directory)
  const entries = readdirSync(root, { withFileTypes: true })
  const files = entries
    .filter(entry => entry.isFile() && entry.name.endsWith(extension))
    .map(entry => entry.name)
    .sort()
  if (files.length === 0) throw new Error(`source inventory is empty: ${directory}`)
  return files.map(name => ({ path: pathFor(name), content: readUtf8(repoRoot, `${directory}/${name}`) }))
}

function generateJson(repoRoot, temporaryDirectory, name, script) {
  const output = join(temporaryDirectory, `${name}.json`)
  execFileSync(process.execPath, [join(repoRoot, 'openbank-admin-ui/scripts', script), '--repo', repoRoot, '--out', output], {
    cwd: repoRoot,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  return JSON.parse(readFileSync(output, 'utf8'))
}

/** Generate a single bounded payload from the reviewed source tree; no network or credentials. */
export function collectAdminUiContent({ repoRoot = REPO, sourceSha, now = Date.now } = {}) {
  const root = resolve(repoRoot)
  if (typeof sourceSha !== 'string' || !SOURCE_SHA.test(sourceSha)) throw new Error('source SHA must be a full lowercase 40-character Git SHA')
  const checkedOutSha = execFileSync('git', ['-C', root, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()
  if (checkedOutSha !== sourceSha) throw new Error('source SHA does not match checked-out reviewed source')

  const temporaryDirectory = mkdtempSync(join(tmpdir(), 'admin-ui-content-'))
  try {
    const bpmn = filesIn(root, 'openbank-admin-ui/src/content/bpmn', '.yaml', name => name.slice(0, -5))
      .map(item => ({ slug: item.path, yaml: item.content }))
    const libsDocs = filesIn(root, 'openbank-libs/docs', '.md', name => `openbank-libs/docs/${name}`)
      .map(item => ({ path: item.path, markdown: item.content }))
    const snapshot = {
      schemaVersion: 1,
      sourceSha,
      collectedAt: new Date(now()).toISOString(),
      content: {
        catalog: generateJson(root, temporaryDirectory, 'catalog', 'generate-catalog.mjs'),
        serviceGraph: generateJson(root, temporaryDirectory, 'service-graph', 'generate-service-graph.mjs'),
        governance: generateJson(root, temporaryDirectory, 'governance', 'generate-governance.mjs'),
        securityGraph: generateJson(root, temporaryDirectory, 'security-graph', 'generate-security-graph.mjs'),
        bcpPolicyMarkdown: readUtf8(root, 'docs/bcp/bcp-policy.md'),
        complianceControlsYaml: readUtf8(root, 'openbank-libs/governance/compliance-controls.yaml'),
        bpmnManifests: bpmn,
        libsDocs,
      },
    }
    const bytes = Buffer.from(`${JSON.stringify(snapshot)}\n`, 'utf8')
    if (bytes.byteLength > ADMIN_UI_CONTENT_MAX_BYTES) throw new Error(`content snapshot exceeds ${ADMIN_UI_CONTENT_MAX_BYTES} byte limit`)
    if (!isAdminUiContentSnapshot(snapshot, now())) throw new Error('collected content failed schema or provenance validation')
    return bytes
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true })
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    const { sourceSha, out } = parseArgs(process.argv.slice(2))
    const bytes = collectAdminUiContent({ sourceSha })
    writeFileSync(out, bytes, { mode: 0o600 })
    process.stdout.write(`Collected admin-ui source content (${bytes.byteLength} bytes) for ${sourceSha}\n`)
  } catch (error) {
    process.stderr.write(`Admin UI content collection failed: ${error instanceof Error ? error.message : 'unknown error'}\n`)
    process.exitCode = 1
  }
}
