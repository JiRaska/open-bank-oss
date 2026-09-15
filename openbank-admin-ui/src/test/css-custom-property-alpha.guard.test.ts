// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const sourceRoot = path.resolve(__dirname, '..')
const extensions = new Set(['.ts', '.tsx', '.css'])

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(directory, entry.name)
    if (entry.isDirectory() && entry.name === 'test') return []
    if (entry.isDirectory()) return sourceFiles(file)
    return extensions.has(path.extname(entry.name)) ? [file] : []
  })
}

describe('CSS custom-property alpha syntax', () => {
  it('never concatenates hex alpha onto var() or an interpolated colour', () => {
    const invalid = sourceFiles(sourceRoot).flatMap(file => {
      const source = readFileSync(file, 'utf8')
      return source.split('\n').flatMap((line, index) =>
        /var\(--[^)]*\)[\da-f]{2}\b|\$\{[^}]+\}[\da-f]{2}\b/i.test(line)
          ? [`${path.relative(sourceRoot, file)}:${index + 1}`]
          : [],
      )
    })

    expect(invalid, `Invalid CSS alpha concatenation:\n${invalid.join('\n')}`).toEqual([])
  })
})
