// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import * as ts from 'typescript'

export type SidebarDestination = { href: string; external: boolean }

/** Parse ALL_NAV rather than keeping a second, stale copy of the sidebar destinations. */
export function readSidebarDestinations(): SidebarDestination[] {
  const file = resolve(process.cwd(), 'src/components/layout/Sidebar.tsx')
  const source = ts.createSourceFile(file, readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
  const declarations = new Map<string, ts.Expression>()

  const collectDeclarations = (node: ts.Node): void => {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer) {
      declarations.set(node.name.text, node.initializer)
    }
    ts.forEachChild(node, collectDeclarations)
  }
  collectDeclarations(source)

  const array = declarations.get('ALL_NAV')
  if (!array) throw new Error('Sidebar.tsx no longer declares ALL_NAV')

  const destinations: SidebarDestination[] = []
  const visitArray = (expression: ts.Expression, visited: Set<string>): void => {
    if (!ts.isArrayLiteralExpression(expression)) throw new Error('ALL_NAV entries must remain statically discoverable')
    for (const element of expression.elements) {
      if (ts.isSpreadElement(element)) {
        if (!ts.isIdentifier(element.expression)) throw new Error('ALL_NAV spread must name a navigation array')
        const name = element.expression.text
        const nested = declarations.get(name)
        if (!nested || visited.has(name)) throw new Error(`Cannot resolve sidebar navigation array ${name}`)
        visitArray(nested, new Set([...visited, name]))
        continue
      }
      if (!ts.isObjectLiteralExpression(element)) throw new Error('Sidebar navigation entries must be object literals')
      let href: string | undefined
      let external = false
      for (const property of element.properties) {
        if (!ts.isPropertyAssignment(property)) continue
        const name = property.name && (ts.isIdentifier(property.name) || ts.isStringLiteral(property.name))
          ? property.name.text
          : undefined
        if (name === 'href' && ts.isStringLiteral(property.initializer)) href = property.initializer.text
        if (name === 'external' && property.initializer.kind === ts.SyntaxKind.TrueKeyword) external = true
      }
      if (!href) throw new Error('A sidebar entry has no static href')
      destinations.push({ href, external })
    }
  }

  visitArray(array, new Set(['ALL_NAV']))
  return destinations
}

const DEFAULT_PAGE_EXTENSIONS = ['tsx', 'ts', 'jsx', 'js'] as const

/**
 * Read Next's static `pageExtensions` setting. The route inventory deliberately fails closed
 * for dynamic config or custom extensions: silently guessing here would let new routes escape
 * the acceptance inventory.
 */
export function readConfiguredPageExtensions(configFile = resolve(process.cwd(), 'next.config.mjs')): string[] {
  const sourceText = readFileSync(configFile, 'utf8')
  const source = ts.createSourceFile(configFile, sourceText, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS)
  let configObject: ts.ObjectLiteralExpression | undefined

  const visit = (node: ts.Node): void => {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.name.text === 'nextConfig'
      && node.initializer && ts.isObjectLiteralExpression(node.initializer)) {
      configObject = node.initializer
    }
    ts.forEachChild(node, visit)
  }
  visit(source)

  if (!configObject) throw new Error('Cannot find a static `nextConfig` object in next.config.mjs')
  if (configObject.properties.some(ts.isSpreadAssignment)) {
    throw new Error('Cannot verify `pageExtensions` because nextConfig uses a spread')
  }
  const pageExtensionProperties = configObject.properties.filter(property => {
    const name = property.name && (ts.isIdentifier(property.name) || ts.isStringLiteral(property.name))
      ? property.name.text
      : undefined
    return name === 'pageExtensions'
  })
  if (pageExtensionProperties.length > 1) throw new Error('Multiple `pageExtensions` settings found in next.config.mjs')
  const configuredProperty = pageExtensionProperties[0]
  if (configuredProperty && !ts.isPropertyAssignment(configuredProperty)) {
    throw new Error('`pageExtensions` must be a static array for route inventory')
  }
  const pageExtensionsProperty = configuredProperty as ts.PropertyAssignment | undefined
  if (!pageExtensionsProperty) return [...DEFAULT_PAGE_EXTENSIONS]

  const initializer = pageExtensionsProperty.initializer
  if (!ts.isArrayLiteralExpression(initializer)) {
    throw new Error('`pageExtensions` must be a static array for route inventory')
  }
  const extensions = initializer.elements.map(element => {
    if (!ts.isStringLiteral(element)) throw new Error('`pageExtensions` must contain only string literals')
    return element.text.replace(/^\./, '')
  })
  if (extensions.length === 0) throw new Error('`pageExtensions` cannot be empty for route inventory')

  const unsupported = extensions.filter(extension => !DEFAULT_PAGE_EXTENSIONS.includes(extension as typeof DEFAULT_PAGE_EXTENSIONS[number]))
  if (unsupported.length > 0) {
    throw new Error(`Unsupported Next.js pageExtensions in route inventory: ${unsupported.join(', ')}`)
  }
  if (new Set(extensions).size !== extensions.length) throw new Error('Duplicate `pageExtensions` in next.config.mjs')
  return extensions
}

/** Discover filesystem App Router pages while applying Next's URL-segment rules. */
export function discoverAppPageRoutes(appDir: string, pageExtensions: readonly string[]): string[] {
  if (pageExtensions.length === 0) throw new Error('At least one page extension is required')
  const unsupported = pageExtensions.filter(extension => !DEFAULT_PAGE_EXTENSIONS.includes(extension as typeof DEFAULT_PAGE_EXTENSIONS[number]))
  if (unsupported.length > 0) {
    throw new Error(`Unsupported Next.js pageExtensions in route inventory: ${unsupported.join(', ')}`)
  }
  const pageFiles = new Set(pageExtensions.map(extension => `page.${extension.replace(/^\./, '')}`))
  const routes: string[] = []

  const visit = (directory: string, urlSegments: string[]): void => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const file = join(directory, entry.name)
      if (entry.isDirectory()) {
        // Private folders and parallel route slots do not create independently addressable URLs.
        if (entry.name.startsWith('_') || entry.name.startsWith('@')) continue
        // Intercepting routes need routing-tree context; fail rather than inventory a false URL.
        if (/^\(\.\.?(?:\.\.?)?\)/.test(entry.name)) {
          throw new Error(`Unsupported intercepting route in route inventory: ${entry.name}`)
        }
        // Route groups organize source files but do not contribute a URL segment.
        const routeGroup = /^\((?!\.\))(?!\.\.\))(?!\.\.\.\))[^/]+\)$/.test(entry.name)
        if (/^\((?:\.\)|\.\.\)|\.\.\.\))/.test(entry.name) && !routeGroup) {
          throw new Error(`Unsupported intercepting route in route inventory: ${entry.name}`)
        }
        visit(file, routeGroup ? urlSegments : [...urlSegments, entry.name])
      } else if (entry.isFile() && pageFiles.has(entry.name)) {
        routes.push(`/${urlSegments.join('/')}`)
      }
    }
  }

  visit(appDir, [])
  const duplicates = routes.filter((route, index) => routes.indexOf(route) !== index)
  if (duplicates.length > 0) {
    throw new Error(`Multiple App Router pages resolve to the same URL: ${[...new Set(duplicates)].join(', ')}`)
  }
  return routes.sort()
}

export function readAppPageRoutes(): string[] {
  const appDir = resolve(process.cwd(), 'src/app')
  return discoverAppPageRoutes(appDir, readConfiguredPageExtensions())
}

/**
 * The sidebar smoke checks navigation and page rendering, not populated backend records.
 * Dynamic templates are tracked separately; each classification states which fixture a
 * future data-backed smoke needs. Adding a page template requires making that decision.
 */
export const DYNAMIC_ROUTE_CLASSIFICATIONS: Record<string, string> = {
  '/accounts/[id]': 'entity id; requires an account fixture for populated detail',
  '/approvals/delegation/[id]': 'approval id; requires a delegation fixture',
  '/approvals/sca/[id]': 'approval id; requires an SCA fixture',
  '/approvals/settlement/[id]': 'approval id; requires a settlement fixture',
  '/balance-sheet/curve-sets/[id]': 'curve-set id; requires a curve-set fixture',
  '/balance-sheet/snapshots/[id]': 'snapshot id; requires a snapshot fixture',
  '/balance-sheet/snapshots/[id]/capital': 'snapshot id; requires a snapshot fixture',
  '/balance-sheet/snapshots/[id]/irrbb': 'snapshot id; requires a snapshot fixture',
  '/balance-sheet/snapshots/[id]/liquidity': 'snapshot id; requires a snapshot fixture',
  '/balance-sheet/snapshots/[id]/liquidity-forecast': 'snapshot id; requires a snapshot fixture',
  '/balance-sheet/snapshots/[id]/min-reserves': 'snapshot id; requires a snapshot fixture',
  '/campaigns/[id]': 'campaign id; requires a campaign fixture',
  '/cards/[id]': 'card id; requires a card fixture',
  '/communication/[personaId]': 'persona id; requires a persona fixture',
  '/communication/edit/[personaKey]': 'persona key; requires a persona fixture',
  '/delegations/[id]': 'delegation id; requires a delegation fixture',
  '/docs/adr/[slug]': 'ADR slug; route requires a known document',
  '/docs/changelog/[service]': 'service key; route requires catalog data',
  '/docs/release-notes/[service]': 'service key; route requires catalog data',
  '/docs/threat-models/[service]': 'service key; route requires catalog data',
  '/iaops/agents/[agentId]': 'agent id; requires an agent fixture',
  '/iaops/cases/[caseId]': 'case id; requires a case fixture',
  '/iaops/flaky-test-hunter/[id]': 'finding id; requires a finding fixture',
  '/lending/applications/[id]': 'application id; requires an application fixture',
  '/parties/[id]': 'party id; requires a party fixture',
  '/payments/[id]': 'payment id; requires a payment fixture',
  '/pension/applications/[id]': 'application id; requires an application fixture',
  '/pension/contracts/[id]': 'contract id; requires a contract fixture',
  '/r/[token]': 'opaque recovery token; requires an issued token',
  '/services/[name]/docs/[[...slug]]': 'service + optional document path; requires a service and document fixture',
  '/settlements/[id]': 'settlement id; requires a settlement fixture',
  '/swift/[id]': 'message id; requires a SWIFT message fixture',
  '/treasury/deals/[id]': 'deal id; requires a treasury deal fixture',
}
