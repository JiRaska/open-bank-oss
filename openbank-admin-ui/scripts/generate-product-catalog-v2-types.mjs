// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const YAML = await import('yaml').then(module => module.default).catch(() => {
  const dependencyRoot = process.env.OPENBANK_ADMIN_UI_MODULE_ROOT
  if (!dependencyRoot) throw new Error('Install admin-ui dependencies before generating catalog types')
  return createRequire(resolve(dependencyRoot, 'package.json'))('yaml')
})

const moduleRoot = resolve(
  process.env.OPENBANK_ADMIN_UI_MODULE_ROOT ?? resolve(dirname(fileURLToPath(import.meta.url)), '..'),
)
const root = resolve(process.env.OPENBANK_REPO_ROOT ?? resolve(moduleRoot, '..'))
const input = resolve(root, 'openbank-product-catalog/src/main/resources/openapi.yaml')
const output = resolve(moduleRoot, 'src/lib/generated/product-catalog-v2.ts')
const document = YAML.parse(readFileSync(input, 'utf8'))
const schemas = document.components.schemas
const selected = [
  'SchemaRef', 'CatalogSchema', 'Specification', 'MarketContext', 'Offering',
  'SpecificationRequest', 'OfferingRequest', 'RevisionRequest', 'PublishRequest',
  'PriceComponent', 'EligibilityOperator', 'EligibilityRule', 'OfferingRelationship', 'RevisionContent',
  'ProductRevision',
  'SchemaViolation', 'ValidateCatalogRequest', 'ValidateCatalogResponse', 'CatalogValidationProblem',
  'CatalogEvent', 'CatalogEventPage',
]

function typeOf(schema = {}) {
  if (schema.$ref) return schema.$ref.split('/').at(-1)
  if (schema.enum) return schema.enum.map(value => JSON.stringify(value)).join(' | ')
  if (Array.isArray(schema.type)) return schema.type.map(type => typeOf({ ...schema, type })).join(' | ')
  if (schema.type === 'array') return `Array<${typeOf(schema.items)}>`
  if (schema.type === 'integer' || schema.type === 'number') return 'number'
  if (schema.type === 'boolean') return 'boolean'
  if (schema.type === 'null') return 'null'
  if (schema.type === 'string') return 'string'
  if (schema.type === 'object' || schema.properties) {
    if (schema.additionalProperties === true) return 'Record<string, unknown>'
    if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
      return `Record<string, ${typeOf(schema.additionalProperties)}>`
    }
    const required = new Set(schema.required ?? [])
    const properties = Object.entries(schema.properties ?? {}).map(([name, value]) =>
      `${JSON.stringify(name)}${required.has(name) ? '' : '?'}: ${typeOf(value)}`)
    return `{ ${properties.join('; ')} }`
  }
  return 'unknown'
}

function parameterObject(parameters, location) {
  const selectedParameters = parameters
    .map(parameter => parameter.$ref
      ? document.components.parameters[parameter.$ref.split('/').at(-1)]
      : parameter)
    .filter(parameter => parameter.in === location)
  if (!selectedParameters.length) return '{}'
  return `{ ${selectedParameters.map(parameter =>
    `${JSON.stringify(parameter.name)}${parameter.required ? '' : '?'}: ${typeOf(parameter.schema)}`).join('; ')} }`
}

function responseType(operation) {
  const response = Object.entries(operation.responses ?? {})
    .find(([status]) => /^2\d\d$/.test(status))?.[1]
  return typeOf(response?.content?.['application/json']?.schema)
}

const operations = Object.entries(document.paths)
  .filter(([path]) => path.startsWith('/api/v2'))
  .flatMap(([path, pathItem]) => Object.entries(pathItem)
    .filter(([, operation]) => operation?.operationId)
    .map(([method, operation]) => [operation.operationId, {
      method: method.toUpperCase(),
      path: path.slice('/api/v2'.length),
      parameters: [...(pathItem.parameters ?? []), ...(operation.parameters ?? [])],
      request: operation.requestBody?.content?.['application/json']?.schema,
      response: responseType(operation),
    }]))
const operationBody = JSON.stringify(Object.fromEntries(operations.map(([id, operation]) => [id, {
  method: operation.method, path: operation.path,
}])), null, 2)
const operationTypes = operations.map(([id, operation]) =>
  `  ${JSON.stringify(id)}: {\n` +
  `    path: ${parameterObject(operation.parameters, 'path')}\n` +
  `    query: ${parameterObject(operation.parameters, 'query')}\n` +
  `    headers: ${parameterObject(operation.parameters, 'header')}\n` +
  `    request: ${operation.request ? typeOf(operation.request) : 'undefined'}\n` +
  `    response: ${operation.response}\n` +
  `  }`).join('\n')
const body = selected.map(name => `export type ${name} = ${typeOf(schemas[name])}`).join('\n\n')
mkdirSync(dirname(output), { recursive: true })
writeFileSync(output, `// GENERATED from openbank-product-catalog/src/main/resources/openapi.yaml. DO NOT EDIT.\n` +
  `// Run: npm run generate:catalog-types\n\n${body}\n\n` +
  `export const catalogOperations = ${operationBody} as const\n\n` +
  `export type CatalogOperationId = keyof typeof catalogOperations\n\n` +
  `export interface CatalogOperationTypes {\n${operationTypes}\n}\n\n` +
  `export type CatalogOperationRequest<I extends CatalogOperationId> = CatalogOperationTypes[I]['request']\n` +
  `export type CatalogOperationResponse<I extends CatalogOperationId> = CatalogOperationTypes[I]['response']\n` +
  `export type CatalogOperationPathParameters<I extends CatalogOperationId> = CatalogOperationTypes[I]['path']\n` +
  `export type CatalogOperationQuery<I extends CatalogOperationId> = CatalogOperationTypes[I]['query']\n` +
  `export type CatalogOperationHeaders<I extends CatalogOperationId> = CatalogOperationTypes[I]['headers']\n\n` +
  `export function catalogOperationPath(\n` +
  `  operationId: CatalogOperationId,\n` +
  `  pathParameters: Record<string, string | number> = {},\n` +
  `  query: Record<string, string | number | undefined> = {},\n` +
  `): string {\n` +
  `  let path: string = catalogOperations[operationId].path\n` +
  `  for (const [name, value] of Object.entries(pathParameters)) {\n` +
  `    path = path.replace(\`{\${name}}\`, encodeURIComponent(String(value)))\n` +
  `  }\n` +
  `  if (/\\{[^}]+\\}/.test(path)) throw new Error(\`missing path parameter for \${operationId}\`)\n` +
  `  const parameters = new URLSearchParams()\n` +
  `  for (const [name, value] of Object.entries(query)) {\n` +
  `    if (value !== undefined) parameters.set(name, String(value))\n` +
  `  }\n` +
  `  const suffix = parameters.toString()\n` +
  `  return suffix ? \`\${path}?\${suffix}\` : path\n` +
  `}\n`)
