// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { modelLabel } from '@/components/agent/AgentDock'

/**
 * The picker's labels are cosmetic; what must not break is the relationship between them and the
 * id underneath. A label mapping that silently swallows an unknown model would show the operator a
 * name that is not what agent-service will run, and every audit event records the id, not the label.
 */
describe('modelLabel', () => {
  it('labels the registered models', () => {
    expect(modelLabel('openai/gpt-oss-120b')).toContain('GPT-OSS 120B')
    expect(modelLabel('deepseek-ai/DeepSeek-V4-Pro')).toContain('DeepSeek V4 Pro')
    expect(modelLabel('llama-3.3-70b-versatile')).toContain('free tier')
  })

  it('falls back to the id itself for a model added to the gateway but not to this map', () => {
    // The point: a new route must be usable the moment it is registered, without an admin-ui
    // change, and must never be displayed as something else.
    expect(modelLabel('zai-org/GLM-5.2')).toBe('GLM-5.2')
    expect(modelLabel('some-model')).toBe('some-model')
  })

  it('never returns an empty label', () => {
    // A trailing slash would make `split('/').pop()` an empty string, which renders as a blank
    // option the operator cannot identify.
    expect(modelLabel('vendor/')).not.toBe('')
    expect(modelLabel('')).not.toBe('')
  })
})
