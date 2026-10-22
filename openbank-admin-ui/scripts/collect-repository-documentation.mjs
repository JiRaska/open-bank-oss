// SPDX-License-Identifier: Apache-2.0
import { lstatSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { parse as parseYaml } from 'yaml'

function documents(root, corpus, readSource) {
  const directory = `docs/${corpus}`
  const files = readdirSync(join(root, directory), { withFileTypes: true })
    .filter(entry => entry.name.endsWith('.md') && (corpus !== 'adr' || /^\d{4}[-.]/.test(entry.name)) && (corpus !== 'agents' || entry.name !== 'README.md'))
    .sort((a, b) => a.name.localeCompare(b.name))
  if (files.length > 1000) throw new Error(`documentation corpus exceeds file limit: ${directory}`)
  return files.map(entry => {
      if (!entry.isFile()) throw new Error(`documentation is not a regular source file: ${directory}/${entry.name}`)
      const path = `${directory}/${entry.name}`
      return { path, markdown: readSource(root, path) }
    })
}

/** Normalize only the declared flag definitions, never ConfigMap metadata or runtime evaluations. */
export function collectRepositoryDocumentation({ root, catalog, events, readSource }) {
  let rawBytes = 0
  const read = (repo, path) => {
    const text = readSource(repo, path)
    rawBytes += Buffer.byteLength(text, 'utf8')
    if (rawBytes > 8 * 1024 * 1024) throw new Error('repository documentation sources exceed size limit')
    return text
  }
  const services = new Map(catalog.services.map(service => [service.name.replace(/^openbank-/, ''), service]))
  const featureFlags = []
  for (const entry of readdirSync(join(root, 'openbank-infra/gitops/components'), { withFileTypes: true })) {
    if (!entry.isDirectory()) continue
    const path = `openbank-infra/gitops/components/${entry.name}/feature-flags.yaml`
    try { lstatSync(join(root, path)) } catch (error) { if (error?.code === 'ENOENT') continue; throw error }
    const config = parseYaml(read(root, path))
    if (config?.kind !== 'ConfigMap' || typeof config?.data?.['flags.json'] !== 'string') throw new Error(`invalid declared flags: ${path}`)
    const service = config.metadata?.labels?.['app.kubernetes.io/name']
    if (typeof service !== 'string' || !services.has(service.replace(/^openbank-/, ''))) throw new Error(`flag owner is not an explicit catalog service: ${path}`)
    const declared = JSON.parse(config.data['flags.json'])
    if (!declared.flags || typeof declared.flags !== 'object' || Array.isArray(declared.flags)) throw new Error(`invalid flag definitions: ${path}`)
    for (const [key, flag] of Object.entries(declared.flags)) {
      if (!flag || typeof flag !== 'object' || Array.isArray(flag)) throw new Error(`invalid flag definition: ${path}`)
      featureFlags.push({ service, key, state: flag.state, defaultVariant: flag.defaultVariant, variants: flag.variants,
        targeted: flag.targeting != null, classification: services.get(service.replace(/^openbank-/, '')).moneyPath ? 'money-path' : 'feature' })
    }
  }
  if (events.available === false) throw new Error('repository event source is unavailable')
  return {
    schema: 'openbank.repository-documentation/v1',
    adrs: documents(root, 'adr', read), threatModels: documents(root, 'threat-models', read), agentCharters: documents(root, 'agents', read),
    events: { ...events, available: true },
    featureFlags: featureFlags.sort((a, b) => a.service.localeCompare(b.service) || a.key.localeCompare(b.key)),
  }
}
