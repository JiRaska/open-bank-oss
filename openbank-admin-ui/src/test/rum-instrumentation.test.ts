import { InMemorySpanExporter, SimpleSpanProcessor, type ReadableSpan } from '@opentelemetry/sdk-trace-web'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createRumProvider,
  looksLikeIdentifier,
  recordScreenView,
  resetRumForTests,
  RUM_INGEST_PATH,
  RUM_SERVICE_NAME,
  toDeviceModel,
  toOsType,
  toScreenName,
} from '@/lib/telemetry/rum'

const IPHONE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15'
const TEST_COLLECTOR = 'http://collector.invalid:4318'

function exportScreen(pathname: string) {
  const exporter = new InMemorySpanExporter()
  const provider = createRumProvider(
    { userAgent: IPHONE_UA, appVersion: '1.2.3' },
    new SimpleSpanProcessor(exporter),
  )
  recordScreenView(pathname, provider.getTracer(RUM_SERVICE_NAME))
  return exporter.getFinishedSpans() as ReadableSpan[]
}

afterEach(() => {
  resetRumForTests()
  delete process.env.OTEL_EXPORTER_OTLP_ENDPOINT
  vi.unstubAllGlobals()
})

describe('authenticated Admin UI browser RUM', () => {
  it('exports screen and resource attributes instead of merely configuring a tracer', () => {
    const [span] = exportScreen('/payments/sepa')
    expect(span.name).toBe('screen./payments/sepa')
    expect(span.attributes).toMatchObject({ 'screen.name': '/payments/sepa', 'http.route': '/payments/sepa' })
    expect(span.resource.attributes).toMatchObject({
      'service.name': 'openbank-admin-ui-browser',
      'app.version': '1.2.3',
      'device.model': 'iphone',
      'os.type': 'ios',
    })
  })

  it('removes identifiers and queries before any route reaches telemetry', () => {
    expect(toScreenName('/parties/9f3c1e2a-77b1-4d0e-9a55-000000000001/cases/8812?q=secret')).toBe('/parties/:id/cases/:id')
    expect(toScreenName('/customer-360')).toBe('/customer-360')
    expect(looksLikeIdentifier('4111111111111111')).toBe(true)
    expect(toDeviceModel(IPHONE_UA)).toBe('iphone')
    expect(toOsType(IPHONE_UA)).toBe('ios')
  })

  it('uses the authenticated same-origin relay, never an external browser collector', () => {
    expect(RUM_INGEST_PATH).toBe('/api/telemetry/traces')
  })

  it('relays to the runtime-only collector endpoint and exposes disabled state distinctly', async () => {
    const { POST } = await import('@/app/api/telemetry/traces/route')
    const disabled = await POST(new Request('http://localhost/api/telemetry/traces', { method: 'POST', body: '{}' }))
    expect(disabled.headers.get('x-rum-relay')).toBe('disabled')

    process.env.OTEL_EXPORTER_OTLP_ENDPOINT = `${TEST_COLLECTOR}/`
    const calls: string[] = []
    vi.stubGlobal('fetch', async (url: string) => {
      calls.push(url)
      return new Response(null, { status: 200 })
    })
    const forwarded = await POST(new Request('http://localhost/api/telemetry/traces', { method: 'POST', body: '{}' }))
    expect(forwarded.headers.get('x-rum-relay')).toBe('forwarded')
    expect(calls).toEqual([`${TEST_COLLECTOR}/v1/traces`])
  })

  it('enforces the relay budget in bytes so Unicode cannot bypass it', async () => {
    process.env.OTEL_EXPORTER_OTLP_ENDPOINT = TEST_COLLECTOR
    const { POST } = await import('@/app/api/telemetry/traces/route')
    const oversized = await POST(new Request('http://localhost/api/telemetry/traces', {
      method: 'POST', body: '😀'.repeat(131_073),
    }))
    expect(oversized.status).toBe(413)
  })
})
