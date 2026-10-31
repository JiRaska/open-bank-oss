// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { UI_MESSAGE_KEYS } from '@/components/communication/UiMessageEditor'

describe('mobile message catalog', () => {
  it('exposes exactly the copy keys accepted by the communication service', () => {
    const source = readFileSync('../openbank-communication-service/src/main/kotlin/com/openbank/communication/domain/UiMessages.kt', 'utf8')
    const declaration = source.match(/val keys = setOf\(([\s\S]*?)\n    \)/)?.[1]
    expect(declaration).toBeDefined()
    const serviceKeys = [...declaration!.matchAll(/"([^"]+)"/g)].map(match => match[1]).sort()
    const editorKeys = UI_MESSAGE_KEYS.map(([key]) => key).sort()
    expect(editorKeys).toEqual(serviceKeys)
  })
})
