// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

export type CatalogSchemaScalar = 'boolean' | 'integer' | 'number' | 'string'

export interface CatalogSchemaField {
  path: string[]
  label: string
  type: CatalogSchemaScalar
  required: boolean
  choices: string[]
  description?: string
}

type JsonSchema = {
  type?: string
  properties?: Record<string, JsonSchema>
  required?: string[]
  enum?: unknown[]
  description?: string
}

const scalarTypes = new Set<CatalogSchemaScalar>(['boolean', 'integer', 'number', 'string'])

/**
 * Produces a deliberately small, safe form surface from a trusted pack schema. Complex arrays
 * remain in expert JSON mode; scalar fields nested in objects become approachable controls.
 */
export function catalogSchemaFields(document: unknown, maxDepth = 3): CatalogSchemaField[] {
  const root = asSchema(document)
  return collect(root, [], true, maxDepth)
}

function collect(node: JsonSchema, prefix: string[], required: boolean, depth: number): CatalogSchemaField[] {
  if (scalarTypes.has(node.type as CatalogSchemaScalar)) {
    return [{
      path: prefix,
      label: prefix.join(' · '),
      type: node.type as CatalogSchemaScalar,
      required,
      choices: Array.isArray(node.enum) ? node.enum.filter((value): value is string => typeof value === 'string') : [],
      description: node.description,
    }]
  }
  if (node.type !== 'object' || depth <= 0 || !node.properties) return []
  const requiredKeys = new Set(node.required ?? [])
  return Object.entries(node.properties).flatMap(([key, child]) =>
    collect(child, [...prefix, key], required && requiredKeys.has(key), depth - 1))
}

function asSchema(value: unknown): JsonSchema {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as JsonSchema : {}
}

export function catalogFieldValue(document: Record<string, unknown> | null, path: string[]): unknown {
  let cursor: unknown = document?.attributes
  for (const key of path) {
    if (!cursor || typeof cursor !== 'object' || Array.isArray(cursor)) return undefined
    cursor = (cursor as Record<string, unknown>)[key]
  }
  return cursor
}

/** Immutable update of a scalar below the editor envelope's `attributes` object. */
export function withCatalogFieldValue(
  document: Record<string, unknown>, path: string[], value: unknown,
): Record<string, unknown> {
  const attributes = isObject(document.attributes) ? document.attributes : {}
  const nextAttributes = setAtPath(attributes, path, value)
  return { ...document, attributes: nextAttributes }
}

function setAtPath(source: Record<string, unknown>, [head, ...tail]: string[], value: unknown): Record<string, unknown> {
  if (!head) return source
  if (tail.length === 0) return { ...source, [head]: value }
  const child = isObject(source[head]) ? source[head] : {}
  return { ...source, [head]: setAtPath(child, tail, value) }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}
