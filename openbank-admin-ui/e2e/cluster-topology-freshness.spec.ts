// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test('cluster dossier shows the generated GitOps snapshot and its source date', async ({ page, context, baseURL }) => {
  const snapshot = JSON.parse(readFileSync(path.join(process.cwd(), 'cluster-topology.json'), 'utf8')) as {
    generatedAt: string
    counts: Record<'namespaces' | 'networkPolicies' | 'externalSecrets' | 'clusterPolicies', number>
  }
  await signInAsOperator(context, baseURL!)

  const topologyResponse = page.waitForResponse((response) => response.url().endsWith('/api/cluster/topology'))
  await page.goto('/docs/cluster')
  const response = await topologyResponse
  expect(response.ok()).toBe(true)
  expect(await response.json()).toMatchObject({ generatedAt: snapshot.generatedAt, counts: snapshot.counts })

  for (const [label, count] of [
    ['Namespaces', snapshot.counts.namespaces],
    ['NetworkPolicies', snapshot.counts.networkPolicies],
    ['External Secrets', snapshot.counts.externalSecrets],
    ['Admission policies', snapshot.counts.clusterPolicies],
  ] as const) {
    const card = page.locator('.card').filter({ has: page.getByText(label, { exact: true }) }).first()
    await expect(card).toContainText(String(count))
  }
  await expect(page.locator('p').filter({ hasText: /GitOps/ }).last())
    .toContainText(String(new Date(snapshot.generatedAt).getFullYear()))
})
