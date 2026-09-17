// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

const sourceRoot = join(process.cwd(), 'src')

function productionStyleSources(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return productionStyleSources(path)
    return entry.isFile() && /\.(?:css|ts|tsx)$/.test(entry.name) ? [path] : []
  })
}

describe('minimum readable font-size contract', () => {
  it('keeps production CSS text at or above the 10px compact-label floor', () => {
    const violations = productionStyleSources(sourceRoot).flatMap(path => {
      const source = readFileSync(path, 'utf8')
      const declarations = [
        ...source.matchAll(/font-size:\s*((?:\d+(?:\.\d+)?|\.\d+))(px|rem)\b/g),
        ...source.matchAll(/fontSize:\s*['"]((?:\d+(?:\.\d+)?|\.\d+))(px|rem)['"]/g),
        ...source.matchAll(/fontSize:\s*(\d+(?:\.\d+)?)\b/g),
      ]
      return declarations
        .filter(match => Number(match[1]) * (match[2] === 'rem' ? 16 : 1) < 10)
        .map(match => `${relative(process.cwd(), path)}:${source.slice(0, match.index).split('\n').length} (${match[0]})`)
    })

    expect(violations, violations.join('\n')).toEqual([])
  })
})
