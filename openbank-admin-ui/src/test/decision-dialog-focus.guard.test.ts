// SPDX-License-Identifier: Apache-2.0

import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const productionRoot = path.resolve(__dirname, '..')

function tsxFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const absolute = path.join(directory, entry.name)
    if (entry.isDirectory()) return tsxFiles(absolute)
    return entry.isFile() && entry.name.endsWith('.tsx') ? [absolute] : []
  })
}

function focusLifecycle(source: string) {
  return {
    ownsEntryFocus: source.includes('onOpenAutoFocus=')
      || source.includes('autoFocus')
      || /(?:cancel|safeAction)Ref\.current\?\.focus\(\)/u.test(source),
    ownsReturnFocus: source.includes('onCloseAutoFocus=')
      || (/\.(?:focus)\(\)/u.test(source)
        && (source.includes('requestAnimationFrame(') || source.includes('queueMicrotask('))),
  }
}

describe('decision-dialog focus lifecycle guard', () => {
  it('requires every alert dialog to own safe entry and deterministic return focus', () => {
    const dialogs = tsxFiles(productionRoot)
      .map(file => ({ file, source: readFileSync(file, 'utf8') }))
      .filter(({ source }) => source.includes('role="alertdialog"'))

    expect(dialogs.length).toBeGreaterThanOrEqual(14)
    for (const { file, source } of dialogs) {
      const relative = path.relative(productionRoot, file)
      const { ownsEntryFocus, ownsReturnFocus } = focusLifecycle(source)

      expect(ownsEntryFocus, `${relative} must put initial focus on a safe dialog action`).toBe(true)
      expect(ownsReturnFocus, `${relative} must restore focus after cancel and successful removal`).toBe(true)
    }
  })

  it('rejects a modal that only declares its ARIA role', () => {
    expect(focusLifecycle('<div role="alertdialog"><button>Confirm</button></div>'))
      .toEqual({ ownsEntryFocus: false, ownsReturnFocus: false })
  })
})
