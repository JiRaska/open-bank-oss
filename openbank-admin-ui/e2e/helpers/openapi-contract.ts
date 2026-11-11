// SPDX-License-Identifier: Apache-2.0
//
// ADR-0370 U1 — validate a mocked BFF response against the service's own openapi.yaml.
//
// A `page.route()` mock is a claim about what a service returns. Nothing used to check that
// claim, so a spec could stay green against a shape the service never sends. This module is
// the pure half (no Playwright): resolve `/api/svc/<key>/<path>` to the service module and the
// matching operation, then validate the mocked JSON body against that operation's response
// schema. The Playwright half lives in `contract-guard.ts`.

import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import Ajv2020Module from 'ajv/dist/2020.js'
import type { ValidateFunction } from 'ajv/dist/2020.js'
import { parse } from 'yaml'

export type ViolationKind =
  | 'unmapped-service' // `/api/svc/<key>` names no module with an openapi.yaml
  | 'unknown-operation' // no path + method in the spec matches the request
  | 'undeclared-status' // the spec declares no response for the mocked status
  | 'non-json-body' // the operation declares JSON and the mock body does not parse
  | 'schema' // the body does not satisfy the declared schema

export interface Violation {
  kind: ViolationKind
  service: string
  method: string
  /** The spec's path template (or the raw request path when no template matched). */
  operation: string
  status: number
  detail: string
}

export interface MockedResponse {
  method: string
  /** Full request URL as the browser sent it. */
  url: string
  status: number
  /** Parsed JSON value, or the raw string body. `undefined` = no body. */
  body: unknown
}

// ajv ships CommonJS. Vitest hands back the class; Playwright's ESM loader hands back the
// module object with the class on `.default`. Accept both.
const Ajv2020 = ((Ajv2020Module as unknown as { default?: typeof Ajv2020Module }).default ?? Ajv2020Module)
type Ajv2020 = InstanceType<typeof Ajv2020Module>

const SVC_PREFIX = '/api/svc/'
const METHODS = ['get', 'put', 'post', 'delete', 'patch', 'head', 'options'] as const

type JsonObject = Record<string, unknown>

interface LoadedSpec {
  doc: JsonObject
  ajv: Ajv2020
  templates: { template: string; regex: RegExp; literalLength: number }[]
  validators: Map<string, ValidateFunction | null>
}

export class OpenApiContract {
  private readonly specs = new Map<string, LoadedSpec | null>()

  /**
   * `strictObjects` (ADR-0370 U7): an object schema that declares `properties` and says nothing
   * about `additionalProperties` is read as closed, so a mocked field the spec does not declare
   * is a violation. Off for the U1 guard, which judges only what a spec states; on for the
   * version-skew check, which asks whether a field exists in one spec version and not another.
   */
  // Plain fields, not parameter properties: Node's type stripping runs this file directly for the
  // version-skew script, and it cannot transform parameter properties.
  private readonly repoRoot: string
  private readonly options: { strictObjects?: boolean }

  constructor(repoRoot: string, options: { strictObjects?: boolean } = {}) {
    this.repoRoot = repoRoot
    this.options = options
  }

  /** `null` when the URL is not a generic service-proxy call (BFF-owned route). */
  check(mock: MockedResponse): Violation[] | null {
    const target = parseProxyUrl(mock.url)
    if (!target) return null
    const method = mock.method.toLowerCase()
    const base = { service: target.service, method: method.toUpperCase(), status: mock.status }
    const spec = this.load(target.service)
    if (!spec) {
      return [{ ...base, kind: 'unmapped-service', operation: target.path, detail: `no openapi.yaml for ${target.service}` }]
    }
    const template = matchTemplate(spec, method, target.path)
    if (!template) {
      return [{ ...base, kind: 'unknown-operation', operation: target.path, detail: `${base.method} ${target.path} is not in the spec` }]
    }
    const op = ((spec.doc.paths as JsonObject)[template] as JsonObject)[method] as JsonObject
    const responses = (op.responses ?? {}) as JsonObject
    const statusKey = [String(mock.status), `${String(mock.status)[0]}XX`, 'default'].find(k => k in responses)
    if (!statusKey) {
      return [{ ...base, kind: 'undeclared-status', operation: template, detail: `status ${mock.status} not declared` }]
    }
    const validate = this.validator(spec, template, method, statusKey)
    if (!validate || mock.body === undefined) return []
    let value = mock.body
    if (typeof value === 'string') {
      try {
        value = JSON.parse(value)
      } catch {
        return [{ ...base, kind: 'non-json-body', operation: template, detail: 'body is not JSON' }]
      }
    }
    if (validate(value)) return []
    const detail = (validate.errors ?? [])
      .slice(0, 3)
      .map(e => `${e.instancePath || '/'} ${e.message ?? ''}`.trim())
      .join('; ')
    return [{ ...base, kind: 'schema', operation: template, detail }]
  }

  private load(service: string): LoadedSpec | null {
    if (this.specs.has(service)) return this.specs.get(service) ?? null
    const file = resolveSpecFile(this.repoRoot, service)
    let loaded: LoadedSpec | null = null
    if (file) {
      const doc = normalize(parse(readFileSync(file, 'utf8')), { strictObjects: this.options.strictObjects === true }) as JsonObject
      const ajv = new Ajv2020({ strict: false, allErrors: true, validateFormats: false })
      ajv.addSchema(doc, 'spec')
      const templates = Object.keys((doc.paths ?? {}) as JsonObject).map(template => ({
        template,
        regex: new RegExp(`^${template.split(/\{[^}]+\}/).map(escapeRegex).join('[^/]+')}/?$`),
        literalLength: template.replace(/\{[^}]+\}/g, '').length,
      }))
      // Most literal first: `/cards/summary` must win over `/cards/{id}`.
      templates.sort((a, b) => b.literalLength - a.literalLength)
      loaded = { doc, ajv, templates, validators: new Map() }
    }
    this.specs.set(service, loaded)
    return loaded
  }

  private validator(spec: LoadedSpec, template: string, method: string, status: string): ValidateFunction | null {
    const key = `${method} ${template} ${status}`
    if (spec.validators.has(key)) return spec.validators.get(key) ?? null
    const op = ((spec.doc.paths as JsonObject)[template] as JsonObject)[method] as JsonObject
    const response = resolveRef(spec.doc, (op.responses as JsonObject)[status]) as JsonObject | undefined
    const content = (response?.content ?? {}) as JsonObject
    const mediaType = Object.keys(content).find(m => m.includes('json'))
    const schema = mediaType ? (content[mediaType] as JsonObject | undefined)?.schema : undefined
    // Every internal `$ref` was rewritten to `spec#/...` at load, so a sub-schema compiles on
    // its own and still resolves against the whole document.
    const validate = schema ? spec.ajv.compile(schema as JsonObject) : null
    spec.validators.set(key, validate)
    return validate
  }
}

export function parseProxyUrl(url: string): { service: string; path: string } | null {
  const pathname = new URL(url, 'http://localhost').pathname
  if (!pathname.startsWith(SVC_PREFIX)) return null
  const rest = pathname.slice(SVC_PREFIX.length)
  const slash = rest.indexOf('/')
  if (slash <= 0) return null
  return { service: rest.slice(0, slash), path: rest.slice(slash) }
}

/** `/api/svc/<key>` is a Kubernetes workload name; the module is `openbank-<key>[-service]`. */
export function resolveSpecFile(repoRoot: string, service: string): string | null {
  for (const moduleDir of [`openbank-${service}`, `openbank-${service}-service`]) {
    const file = path.join(repoRoot, moduleDir, 'src/main/resources/openapi.yaml')
    if (existsSync(file)) return file
  }
  return null
}

function matchTemplate(spec: LoadedSpec, method: string, requestPath: string): string | null {
  const paths = spec.doc.paths as JsonObject
  for (const t of spec.templates) {
    if (t.regex.test(requestPath) && METHODS.includes(method as (typeof METHODS)[number]) && (paths[t.template] as JsonObject)[method]) {
      return t.template
    }
  }
  return null
}

function resolveRef(doc: JsonObject, node: unknown): unknown {
  const ref = (node as JsonObject | undefined)?.$ref
  if (typeof ref !== 'string' || !ref.startsWith('spec#/')) return node
  return ref
    .slice('spec#/'.length)
    .split('/')
    .map(s => s.replace(/~1/g, '/').replace(/~0/g, '~'))
    .reduce<unknown>((acc, k) => (acc as JsonObject | undefined)?.[k], doc)
}

/**
 * OpenAPI 3.0 `nullable: true` is not JSON Schema; 3.1 specs already use `type: [x, 'null']`.
 * Rewrite the former into the latter so one 2020-12 validator serves the whole fleet.
 */
const COMPOSITION = ['allOf', 'oneOf', 'anyOf'] as const

export function normalize(node: unknown, opts: { strictObjects?: boolean } = {}, inComposition = false): unknown {
  if (Array.isArray(node)) return node.map(n => normalize(n, opts, inComposition))
  if (!node || typeof node !== 'object') return node
  const out: JsonObject = {}
  for (const [k, v] of Object.entries(node as JsonObject)) {
    out[k] = normalize(v, opts, (COMPOSITION as readonly string[]).includes(k))
  }
  // Closing a schema that is a member of allOf would reject the sibling members' properties,
  // and one that itself composes cannot be closed without merging them, so both stay open.
  if (opts.strictObjects && !inComposition && out.properties && typeof out.properties === 'object'
    && out.additionalProperties === undefined && !COMPOSITION.some(k => k in out)) {
    out.additionalProperties = false
  }
  if (out.nullable === true) {
    if (typeof out.type === 'string') out.type = [out.type, 'null']
    else if (!out.type) {
      const variants = ['allOf', 'oneOf', 'anyOf'].find(k => Array.isArray(out[k]))
      if (variants) {
        out.anyOf = [{ [variants]: out[variants] }, { type: 'null' }]
        if (variants !== 'anyOf') delete out[variants]
      }
    }
    if (Array.isArray(out.enum) && !out.enum.includes(null)) out.enum = [...out.enum, null]
  }
  delete out.nullable
  if (typeof out.$ref === 'string' && out.$ref.startsWith('#/')) out.$ref = `spec${out.$ref}`
  return out
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export function violationKey(specFile: string, v: Violation): string {
  return `${specFile}|${v.method} ${v.service} ${v.operation}|${v.status}|${v.kind}`
}
