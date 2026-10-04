// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import YAML from 'yaml'

const routeSource = fs.readFileSync(path.join(process.cwd(), 'src/app/api/approvals/pending/route.ts'), 'utf8')
const pageSource = fs.readFileSync(path.join(process.cwd(), 'src/app/approvals/page.tsx'), 'utf8')

function yamlFiles(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const full = path.join(dir, entry.name)
    return entry.isDirectory() ? yamlFiles(full) : /\.ya?ml$/.test(entry.name) ? [full] : []
  })
}

function deployedServiceDestinations(): Map<string, Set<string>> {
  const components = path.resolve(process.cwd(), '../openbank-infra/gitops/components')
  const destinations = new Map<string, Set<string>>()
  for (const file of yamlFiles(components)) {
    for (const doc of YAML.parseAllDocuments(fs.readFileSync(file, 'utf8'))) {
      const resource = doc.toJS() as { kind?: string; metadata?: { name?: string; namespace?: string }; spec?: { ports?: { port?: number }[] } } | null
      if (resource?.kind !== 'Service' || !resource.metadata?.name || !resource.metadata.namespace) continue
      const routes = destinations.get(resource.metadata.name) ?? new Set<string>()
      for (const port of resource.spec?.ports ?? []) {
        if (port.port) routes.add(`${resource.metadata.namespace}:${port.port}`)
      }
      destinations.set(resource.metadata.name, routes)
    }
  }
  return destinations
}

describe('approval inbox source truthfulness', () => {
  it('addresses every approval provider at its declared GitOps service namespace and port', () => {
    const destinations = deployedServiceDestinations()
    const directTargets = [...routeSource.matchAll(/serverSvcUrl\(\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*(\d+)/g)]
    const operatorTargets = [...routeSource.matchAll(/operatorApprovalsPending\(\s*'(?:sca|settlement)'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*(\d+)/g)]
    expect(operatorTargets).toHaveLength(2)
    const targets = [...directTargets, ...operatorTargets]
    expect(targets.length).toBeGreaterThan(20)
    for (const [, name, namespace, port] of targets) {
      expect(destinations.get(name), `${name} is missing from GitOps Services`).toBeDefined()
      expect(destinations.get(name)?.has(`${namespace}:${port}`), `${name} must be addressed at its GitOps namespace and port`).toBe(true)
    }
  })

  it('connects the notification approval store to its declared Redis Service', () => {
    const manifest = path.resolve(process.cwd(), '../openbank-infra/gitops/components/notifications/notification-service.yaml')
    const deployment = YAML.parseAllDocuments(fs.readFileSync(manifest, 'utf8'))
      .map(doc => doc.toJS() as { kind?: string; spec?: { template?: { spec?: { containers?: { name?: string; env?: { name: string; value?: string }[] }[] } } } })
      .find(resource => resource.kind === 'Deployment')
    const notification = deployment?.spec?.template?.spec?.containers?.find(container => container.name === 'notification-service')
    const redisUrls = notification?.env?.filter(variable => variable.name === 'QUARKUS_REDIS_HOSTS') ?? []
    expect(redisUrls).toHaveLength(1)
    expect(redisUrls[0].value).toBe('redis://redis.notifications.svc:6379')
    expect(deployedServiceDestinations().get('redis')?.has('notifications:6379')).toBe(true)
  })

  it('does not label a wired queue as not configured', () => {
    expect(routeSource).toContain("'not-configured'")
    expect(routeSource).not.toContain("balance: 'not-configured'")
    expect(routeSource).not.toContain("consent: 'not-configured'")
    expect(routeSource).not.toContain("billing: 'not-configured'")
    expect(routeSource).not.toContain('NOT_CONFIGURED_SOURCES')
    expect(routeSource).toContain('balancePending(headers)')
    expect(routeSource).toContain('billingPending(headers)')
  })

  it('does not render an empty-state claim while a source is not configured', () => {
    expect(pageSource).toContain('const notConfiguredSources = useMemo(')
    expect(pageSource).toContain('!domainLoadFailed && unavailableSources.length === 0')
    expect(pageSource).toContain('notConfiguredSources.length === 0 && domainApprovalItems.length === 0')
    expect(pageSource).toContain('Decisions from these domains will not appear here until their read endpoint is available.')
  })

  it('renders the proposer identity supplied by the BFF rather than assuming every proposer is a bot', () => {
    expect(pageSource).toContain("const proposerKind = chartered ? 'agent' : 'unverified'")
    expect(pageSource).not.toContain('/assistant|agent|\\bai\\b/i.test(p.proposedBy)')
  })
})
