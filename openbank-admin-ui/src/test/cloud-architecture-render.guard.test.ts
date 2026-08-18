import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const file = path.resolve(process.cwd(), 'src/app/docs/cloud-architecture/page.tsx')

describe('cloud architecture render performance contract', () => {
  it('keeps reusable diagram primitives outside the page render', () => {
    const source = fs.readFileSync(file, 'utf8')
    expect(source).toContain('function CloudNodeBox(')
    expect(source).toContain('function ArchitectureZone(')
    expect(source).toContain('function ArchitectureArrow(')
    expect(source).not.toContain('const Box = ({ n }')
    expect(source).not.toContain('const Zone = ({ title')
    expect(source).not.toContain('const Arrow = ({ label')
    expect(source).toContain('const selectNode = useCallback(')
  })
})
