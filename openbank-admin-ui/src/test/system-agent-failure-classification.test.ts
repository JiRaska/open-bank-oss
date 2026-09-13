import { describe, expect, it } from 'vitest'
import { AgentCallError, classifyAgentFailure } from '@/lib/agent/mcpFailure'

describe('agent failure classification', () => {
  it.each([
    [401, 'unauthorized'],
    [403, 'unauthorized'],
    [404, 'not_deployed'],
    [408, 'unreachable'],
    [502, 'unreachable'],
    [503, 'unreachable'],
    [504, 'unreachable'],
    [400, 'error'],
    [409, 'error'],
    [500, 'error'],
  ] as const)('maps HTTP %i to %s', (status, expected) => {
    expect(classifyAgentFailure(status)).toBe(expected)
  })

  it('keeps the public error generic and carries only the safe UI classification', () => {
    const failure = new AgentCallError('unreachable')
    expect(failure.kind).toBe('unreachable')
    expect(failure.message).toBe('Agent request failed')
  })
})
