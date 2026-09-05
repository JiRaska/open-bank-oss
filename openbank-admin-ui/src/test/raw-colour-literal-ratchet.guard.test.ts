import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const UI_ROOTS = [
  path.resolve(__dirname, '../app'),
  path.resolve(__dirname, '../components'),
]
const SOURCE_EXTENSIONS = new Set(['.tsx', '.css'])
// Do not treat an issue reference such as `#5904` as a colour. The admin UI uses only
// three- and six-digit hexadecimal CSS colours, so those are the two forms this ratchet owns.
const HEX_LITERAL = /#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}(?![0-9a-fA-F])/g

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(file)
    if (file.endsWith('globals.css') || !SOURCE_EXTENSIONS.has(path.extname(file))) return []
    return [file]
  })
}

function rawColourCount(): number {
  return UI_ROOTS
    .flatMap(sourceFiles)
    .reduce((total, file) => total + (readFileSync(file, 'utf8').match(HEX_LITERAL)?.length ?? 0), 0)
}

describe('admin UI semantic colour migration', () => {
  it('does not add raw hexadecimal colours outside the token stylesheet', () => {
    // Baseline captured after migrating approval identity badges to shared semantic tones.
    // Lowering this number is always safe; raising it requires an intentional token decision.
    expect(rawColourCount()).toBeLessThanOrEqual(1782)
  })
})
