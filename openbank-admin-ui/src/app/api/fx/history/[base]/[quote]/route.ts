import { NextResponse } from 'next/server'
import { inCluster, resolveInClusterBaseUrl } from '@/lib/discovery'
import { auth } from '@/auth'

const FX_SERVICE_URL = process.env.FX_SERVICE_URL ?? 'http://localhost:8119'
const CURRENCY = /^[A-Z]{3}$/

export async function GET(_request: Request, context: { params: Promise<{ base: string; quote: string }> }) {
  const { base: rawBase, quote: rawQuote } = await context.params
  const base = rawBase.toUpperCase()
  const quote = rawQuote.toUpperCase()
  if (!CURRENCY.test(base) || !CURRENCY.test(quote)) return NextResponse.json({ error: 'Invalid currency' }, { status: 400 })

  const session = await auth()
  const accessToken = session?.user?.accessToken
  if (!accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })

  const to = new Date()
  const from = new Date(to)
  from.setUTCMonth(from.getUTCMonth() - 3)
  try {
    const serviceUrl = inCluster() ? await resolveInClusterBaseUrl('fx-service') : FX_SERVICE_URL
    if (!serviceUrl) return NextResponse.json({ error: 'FX history unavailable' }, { status: 503 })
    const url = `${serviceUrl}/api/v1/fx/rates/${base}/${quote}/history?source=CNB&limit=100&from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`
    const response = await fetch(url, {
      cache: 'no-store',
      headers: { Accept: 'application/json', Authorization: `Bearer ${accessToken}` },
      signal: AbortSignal.timeout(8000),
    })
    if (!response.ok) return NextResponse.json({ error: 'FX history unavailable' }, { status: response.status })
    return NextResponse.json(await response.json())
  } catch {
    return NextResponse.json({ error: 'FX history unreachable' }, { status: 503 })
  }
}
