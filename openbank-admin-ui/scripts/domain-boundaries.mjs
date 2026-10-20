// SPDX-License-Identifier: Apache-2.0

import path from 'node:path'

/**
 * Ownership is explicit for business domains; shared infrastructure folders are
 * deliberately absent. App routes compose domains, while component/lib code may
 * cross an ownership boundary only through a reviewed public contract.
 */
export const DOMAIN_ROOTS = Object.freeze([
  { owner: 'payments', roots: ['src/app/payments', 'src/components/payments', 'src/lib/payments'] },
  { owner: 'ledger', roots: ['src/app/ledger', 'src/lib/ledger'] },
  { owner: 'balance-sheet', roots: ['src/app/balance-sheet', 'src/components/balance-sheet'] },
  { owner: 'treasury', roots: ['src/app/treasury', 'src/components/treasury'] },
  { owner: 'pension', roots: ['src/app/pension', 'src/components/pension'] },
  { owner: 'approvals', roots: ['src/app/approvals', 'src/components/approvals', 'src/lib/approvals'] },
  { owner: 'risk', roots: ['src/app/risk', 'src/lib/risk'] },
  { owner: 'lending', roots: ['src/app/lending', 'src/components/lending', 'src/lib/lending'] },
  { owner: 'party', roots: ['src/app/parties', 'src/components/party', 'src/lib/party'] },
  { owner: 'customer360', roots: ['src/app/customer-360', 'src/lib/customer360'] },
  { owner: 'loyalty', roots: ['src/app/loyalty', 'src/lib/loyalty'] },
  { owner: 'kyc', roots: ['src/app/kyc', 'src/components/kyc'] },
])

export const DOMAIN_IMPORT_BOUNDARIES = Object.freeze([
  {
    name: 'payments',
    owners: ['payments'],
    forbidden: ['ledger', 'balance-sheet', 'treasury'],
  },
  {
    name: 'pension, treasury, and approvals',
    owners: ['pension', 'treasury', 'approvals'],
    forbidden: ['balance-sheet'],
  },
])

export const PUBLIC_SHARED_CONTRACTS = Object.freeze([
  'src/lib/loyalty/contracts',
  'src/lib/ledger/contracts',
  'src/lib/risk/contracts',
  'src/lib/party/contracts',
  'src/lib/customer360/contracts',
  'src/lib/lending/contracts',
])

function sourcePath(filename) {
  const normalized = filename.replaceAll('\\', '/')
  const marker = '/src/'
  const index = normalized.lastIndexOf(marker)
  if (index < 0) return null
  return `src/${normalized.slice(index + marker.length)}`
}

function resolveImport(source, importer) {
  if (source.startsWith('@/')) return path.posix.normalize(`src/${source.slice(2)}`)
  if (source.startsWith('.')) {
    const importerDirectory = importer.slice(0, importer.lastIndexOf('/'))
    return path.posix.normalize(`${importerDirectory}/${source}`)
  }
  return null
}

function ownerForPath(pathname) {
  return DOMAIN_ROOTS.find(({ roots }) => roots.some(root => pathname === root || pathname.startsWith(`${root}/`)))?.owner ?? null
}

function modulePath(pathname) {
  return pathname.replace(/\.(?:[cm]?[jt]sx?)$/, '').replace(/\/index$/, '')
}

function importsRouteModule(importer, target) {
  if (!target.startsWith('src/app/api/')) return false
  // Shared libraries and feature components are lower layers than route
  // handlers. App pages may still compose route-specific types during migration.
  return importer.startsWith('src/lib/') || importer.startsWith('src/components/')
}

export function findBoundaryViolation(source, importerFilename) {
  const importer = sourcePath(importerFilename)
  if (!importer) return null
  const target = resolveImport(source, importer)
  if (!target) return null

  if (importsRouteModule(importer, target)) {
    return { kind: 'route-module', target }
  }

  const owner = ownerForPath(importer)
  const targetOwner = ownerForPath(target)
  if (!owner || !targetOwner || owner === targetOwner) return null

  // Preserve the focused bans even at the app composition layer. They protect
  // financial domains whose current implementation imports are known to be private.
  for (const boundary of DOMAIN_IMPORT_BOUNDARIES) {
    if (boundary.owners.includes(owner) && boundary.forbidden.includes(targetOwner)) {
      return { kind: 'domain', domain: targetOwner, boundary: boundary.name }
    }
  }

  // App routes are the composition layer. For lower layers, every cross-owner
  // import must resolve to one exact, reviewed public module path.
  if ((importer.startsWith('src/components/') || importer.startsWith('src/lib/'))
      && !PUBLIC_SHARED_CONTRACTS.includes(modulePath(target))) {
    return { kind: 'private', domain: targetOwner, owner }
  }
  return null
}

export const noPrivateCrossImport = {
  meta: {
    type: 'problem',
    docs: { description: 'Keep feature internals behind shared contracts and primitives' },
    schema: [],
    messages: {
      route: 'Application code must import a shared contract instead of an API route module ({{target}}).',
      domain: '{{boundary}} code must use shared contracts and primitives instead of private {{domain}} imports.',
      private: '{{owner}} implementation must import a public contract instead of private {{domain}} internals.',
    },
  },
  create(context) {
    const filename = context.getFilename().replaceAll('\\', '/')
    const reportSource = (node, source) => {
      const violation = findBoundaryViolation(source, filename)
      if (!violation) return
      if (violation.kind === 'route-module') {
        context.report({ node, messageId: 'route', data: { target: violation.target } })
      } else if (violation.kind === 'domain') {
        context.report({ node, messageId: 'domain', data: violation })
      } else {
        context.report({ node, messageId: 'private', data: violation })
      }
    }
    return {
      ImportDeclaration(node) { reportSource(node, node.source.value) },
      ExportNamedDeclaration(node) { if (node.source) reportSource(node, node.source.value) },
      ExportAllDeclaration(node) { reportSource(node, node.source.value) },
      ImportExpression(node) {
        if (node.source.type === 'Literal' && typeof node.source.value === 'string') reportSource(node, node.source.value)
      },
      CallExpression(node) {
        if (node.callee.type === 'Import' && node.arguments[0]?.type === 'Literal' && typeof node.arguments[0].value === 'string') {
          reportSource(node, node.arguments[0].value)
        }
      },
    }
  },
}

// Keep the original rule name as a compatibility alias for existing editor configs.
export const noPaymentsPrivateCrossDomain = noPrivateCrossImport

export const PAYMENTS_DOMAIN_BOUNDARY = Object.freeze({
  owner: 'src/{app,components,lib}/payments/**',
  forbiddenDomains: ['ledger', 'balance-sheet', 'treasury'],
  sharedImports: ['@/components/ui', '@/components/layout', '@/components/auth', '@/lib/<shared-module>'],
})

export const domainBoundariesPlugin = {
  rules: {
    'no-private-cross-import': noPrivateCrossImport,
    'no-payments-private-cross-domain': noPaymentsPrivateCrossDomain,
  },
}
