import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = resolve(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(path)
    return /\.(?:css|tsx)$/.test(entry.name) ? [path] : []
  })
}

describe('responsive grid minimum contract', () => {
  it('bounds every fixed auto-fit and auto-fill minimum by its available width', () => {
    for (const file of sourceFiles(resolve(process.cwd(), 'src'))) {
      const source = readFileSync(file, 'utf8')
      expect(source, file).not.toMatch(/repeat\(auto-(?:fit|fill),\s*minmax\([0-9]+px,\s*1fr\)\)/)
    }
  })
})
