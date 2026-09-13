import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'

const repoRoot = join(__dirname, '../../..')
const guard = join(repoRoot, '.github/scripts/authorize-admin-ui-deploy-source.sh')
const manifest = 'openbank-infra/gitops/components/admin-ui/admin-ui.yaml'
const temporaryRepositories: string[] = []

function git(repository: string, ...args: string[]) {
  return execFileSync('git', args, { cwd: repository, encoding: 'utf8' }).trim()
}

function createRepository() {
  const repository = mkdtempSync(join(tmpdir(), 'admin-ui-deploy-source-'))
  temporaryRepositories.push(repository)
  git(repository, 'init', '-q')
  git(repository, 'config', 'user.email', 'test@example.com')
  git(repository, 'config', 'user.name', 'Admin UI deploy guard test')
  git(repository, 'config', 'commit.gpgsign', 'false')
  commitFile(repository, 'openbank-admin-ui/src/app/page.tsx', 'initial\n', 'feat(admin-ui): seed source')
  return repository
}

function commitFile(repository: string, path: string, content: string, subject: string) {
  const target = join(repository, path)
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, content)
  git(repository, 'add', '--', path)
  git(repository, 'commit', '-qm', subject)
  return git(repository, 'rev-parse', 'HEAD')
}

function authorize(repository: string, source: string, main: string) {
  return execFileSync('bash', [guard, source, main], {
    cwd: repository,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim()
}

afterEach(() => {
  for (const repository of temporaryRepositories.splice(0)) {
    rmSync(repository, { recursive: true, force: true })
  }
})

describe('Admin UI deploy source authorization', () => {
  it('keeps a waiting source eligible when main advanced only by its generated image pin', () => {
    const repository = createRepository()
    const source = git(repository, 'rev-parse', 'HEAD')
    const main = commitFile(
      repository,
      manifest,
      'image: admin-ui:sandbox-source\n',
      'chore(admin-ui): deploy sandbox-source (#1)',
    )

    expect(authorize(repository, source, main)).toBe('true')
  })

  it('rejects a source overtaken by build-relevant work', () => {
    const repository = createRepository()
    const source = git(repository, 'rev-parse', 'HEAD')
    const main = commitFile(
      repository,
      'openbank-admin-ui/src/app/page.tsx',
      'newer source\n',
      'fix(admin-ui): change newer source',
    )

    expect(authorize(repository, source, main)).toBe('false')
  })

  it('rejects a deploy-looking commit that changes anything beyond the image pin', () => {
    const repository = createRepository()
    const source = git(repository, 'rev-parse', 'HEAD')
    mkdirSync(join(repository, 'openbank-infra/gitops/components/admin-ui'), { recursive: true })
    writeFileSync(join(repository, manifest), 'image: admin-ui:sandbox-source\n')
    writeFileSync(join(repository, 'openbank-admin-ui/README.md'), 'unexpected\n')
    git(repository, 'add', '--', manifest, 'openbank-admin-ui/README.md')
    git(repository, 'commit', '-qm', 'chore(admin-ui): deploy spoofed')
    const main = git(repository, 'rev-parse', 'HEAD')

    expect(authorize(repository, source, main)).toBe('false')
  })

  it('rejects the generated deploy commit itself and accepts an ordinary current source', () => {
    const repository = createRepository()
    const ordinary = git(repository, 'rev-parse', 'HEAD')
    expect(authorize(repository, ordinary, ordinary)).toBe('true')

    const generated = commitFile(
      repository,
      manifest,
      'image: admin-ui:sandbox-source\n',
      'chore(admin-ui): deploy sandbox-source',
    )
    expect(authorize(repository, generated, generated)).toBe('false')
  })
})
