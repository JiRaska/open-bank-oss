import { render } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const telemetry = vi.hoisted(() => ({
  initRum: vi.fn(),
  recordScreenView: vi.fn(),
  recordWebVital: vi.fn(),
}))
const navigation = vi.hoisted(() => ({ pathname: '/payments/party-123456' }))
const vitals = vi.hoisted(() => ({
  callbacks: [] as Array<(metric: { id: string; name: string; value: number; delta: number; rating: string }) => void>,
  fireDuringLayout: undefined as { id: string; name: string; value: number; delta: number; rating: string } | undefined,
}))

vi.mock('next/navigation', () => ({ usePathname: () => navigation.pathname }))
vi.mock('next/web-vitals', async () => {
  const React = await import('react')
  return {
    useReportWebVitals: (callback: (metric: typeof vitals.fireDuringLayout) => void) => {
      React.useLayoutEffect(() => {
        if (vitals.fireDuringLayout) callback(vitals.fireDuringLayout)
      })
      React.useEffect(() => { vitals.callbacks.push(callback) }, [callback])
    },
  }
})
vi.mock('@/lib/telemetry/rum', () => telemetry)

import { RumScreenTracker } from '@/components/telemetry/RumScreenTracker'

describe('RUM Web Vitals tracker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vitals.callbacks = []
    vitals.fireDuringLayout = undefined
    navigation.pathname = '/payments/party-123456'
  })

  it('wires the built-in Next.js reporter to the initial document route', () => {
    const view = render(<RumScreenTracker enabled />)
    navigation.pathname = '/dashboard'
    view.rerender(<RumScreenTracker enabled />)
    const metric = { id: 'vital-a', name: 'LCP', value: 1_200, delta: 1_200, rating: 'good' }
    vitals.callbacks.at(-1)?.(metric)

    expect(telemetry.initRum).toHaveBeenCalledTimes(2)
    expect(telemetry.recordScreenView).toHaveBeenLastCalledWith('/dashboard')
    expect(telemetry.recordWebVital).toHaveBeenCalledWith(metric, '/payments/party-123456')
    expect(new Set(vitals.callbacks).size).toBe(1)
  })

  it('keeps one observer owner and suppresses metrics after moving to a public surface', () => {
    const view = render(<RumScreenTracker enabled />)
    vitals.fireDuringLayout = { id: 'vital-b', name: 'CLS', value: 0.01, delta: 0.01, rating: 'good' }
    view.rerender(<RumScreenTracker enabled={false} />)

    expect(telemetry.recordWebVital).not.toHaveBeenCalled()
    expect(new Set(vitals.callbacks).size).toBe(1)
  })

  it('does not relabel a public document vital after client navigation to a protected route', () => {
    navigation.pathname = '/auth/login'
    const view = render(<RumScreenTracker enabled={false} />)
    navigation.pathname = '/dashboard'
    view.rerender(<RumScreenTracker enabled />)
    vitals.callbacks.at(-1)?.({ id: 'vital-c', name: 'LCP', value: 900, delta: 900, rating: 'good' })

    expect(telemetry.recordScreenView).toHaveBeenCalledWith('/dashboard')
    expect(telemetry.recordWebVital).not.toHaveBeenCalled()
    expect(new Set(vitals.callbacks).size).toBe(1)
  })

})
