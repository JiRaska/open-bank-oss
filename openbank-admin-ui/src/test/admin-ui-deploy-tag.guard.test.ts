import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const repoRoot = join(__dirname, '../../..')

describe('Admin UI deploy image-tag guard', () => {
  it('makes every non-push evidence refresh unique without changing push tags', () => {
    const script = readFileSync(
      join(repoRoot, 'openbank-infra/scripts/build-push-admin-ui.sh'),
      'utf8',
    )
    const workflow = readFileSync(
      join(repoRoot, '.github/workflows/admin-ui-deploy.yml'),
      'utf8',
    )

    // ECR intentionally makes tags immutable. The normal push remains the
    // reproducible commit tag, while an operator or scheduled refresh gets a
    // bounded run id suffix and can therefore deliver evidence that arrived
    // after the first build.
    expect(script).toContain('TAG="sandbox-${GIT_SHA}${TAG_SUFFIX:+-${TAG_SUFFIX}}"')
    expect(script).toContain('^run[0-9]+$')
    expect(workflow).toContain('if [ "${{ github.event_name }}" = "workflow_dispatch" ] || [ "${{ github.event_name }}" = "schedule" ] || [ "${{ github.event_name }}" = "workflow_run" ]; then')
    expect(workflow).toContain('ADMIN_UI_IMAGE_TAG_SUFFIX="run${GITHUB_RUN_ID}"')
  })
})
