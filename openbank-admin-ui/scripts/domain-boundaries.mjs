// SPDX-License-Identifier: Apache-2.0

/**
 * First incremental UI architecture boundary. Payment code under src/{app,components,lib}/payments
 * may reuse shared UI/auth/layout and shared lib contracts, but may not reach into the private
 * ledger, balance-sheet, or treasury implementation folders. The rule deliberately covers only
 * this one domain while the rest of the application is migrated incrementally.
 */
import path from 'node:path'

export const PAYMENTS_DOMAIN_BOUNDARY = Object.freeze({
  owner: 'src/{app,components,lib}/payments/**',
  forbiddenDomains: ['ledger', 'balance-sheet', 'treasury'],
  sharedImports: [
    '@/components/ui',
    '@/components/layout',
    '@/components/auth',
    '@/lib/<shared-module>',
  ],
})

const PAYMENT_SOURCE = /(?:^|\/)src\/(?:app|components|lib)\/payments\//
const DOMAIN_SOURCE = /(?:^|\/)src\/(?:app|components|lib)\/(ledger|balance-sheet|treasury)(?:\/|$)/

function resolveImport(source, importer) {
  if (source.startsWith('@/')) return path.posix.normalize(`src/${source.slice(2)}`)
  if (source.startsWith('.')) {
    const importerDirectory = importer.slice(0, importer.lastIndexOf('/'))
    const parts = []
    for (const part of `${importerDirectory}/${source}`.split('/')) {
      if (!part || part === '.') continue
      if (part === '..') parts.pop()
      else parts.push(part)
    }
    return parts.join('/')
  }
  return null
}

function isForbidden(source, importer) {
  if (!source || !PAYMENT_SOURCE.test(importer)) return false
  const resolved = resolveImport(source, importer)
  return resolved !== null && DOMAIN_SOURCE.test(resolved)
}

export const noPaymentsPrivateCrossDomain = {
  meta: {
    type: 'problem',
    docs: {
      description: 'Keep payment modules independent from ledger, balance-sheet, and treasury internals',
    },
    schema: [],
    messages: {
      forbidden: 'Payment modules must use shared contracts and primitives instead of private {{domain}} domain imports.',
    },
  },
  create(context) {
    // ESLint supplies an absolute filename. Normalize separators so this works on Windows too.
    const filename = context.getFilename().replaceAll('\\', '/')
    const reportSource = (node, source) => {
      if (!isForbidden(source, filename)) return
      const resolved = resolveImport(source, filename)
      const domain = DOMAIN_SOURCE.exec(resolved)?.[1]
      context.report({ node, messageId: 'forbidden', data: { domain } })
    }
    return {
      ImportDeclaration(node) { reportSource(node, node.source.value) },
      ExportNamedDeclaration(node) {
        if (node.source) reportSource(node, node.source.value)
      },
      ExportAllDeclaration(node) { reportSource(node, node.source.value) },
      ImportExpression(node) {
        if (node.source.type === 'Literal' && typeof node.source.value === 'string') {
          reportSource(node, node.source.value)
        }
      },
      CallExpression(node) {
        if (node.callee.type === 'Import' && node.arguments[0]?.type === 'Literal' && typeof node.arguments[0].value === 'string') {
          reportSource(node, node.arguments[0].value)
        }
      },
    }
  },
}

export const domainBoundariesPlugin = {
  rules: {
    'no-payments-private-cross-domain': noPaymentsPrivateCrossDomain,
  },
}
