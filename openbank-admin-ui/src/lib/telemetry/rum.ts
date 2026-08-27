// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { context, trace, type Tracer } from '@opentelemetry/api'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http'
import { resourceFromAttributes } from '@opentelemetry/resources'
import { BatchSpanProcessor, WebTracerProvider, type SpanProcessor } from '@opentelemetry/sdk-trace-web'
import { ATTR_HTTP_ROUTE, ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION } from '@opentelemetry/semantic-conventions'

/** Browser posts to this same-origin, authenticated relay — never directly to an internal collector. */
export const RUM_INGEST_PATH = '/api/telemetry/traces'
export const RUM_SERVICE_NAME = 'openbank-admin-ui'
export const ATTR_SCREEN_NAME = 'screen.name'
export const ATTR_APP_VERSION = 'app.version'
export const ATTR_DEVICE_MODEL = 'device.model'
export const ATTR_OS_TYPE = 'os.type'
export const ATTR_OS_VERSION = 'os.version'

/** Conservative redaction: screen names must never contain operator, customer or case identifiers. */
export function looksLikeIdentifier(segment: string): boolean {
  if (!/^[a-z][a-z0-9-]*$/.test(segment)) return true
  if (segment.length > 24 || /\d{4}/.test(segment)) return true
  return (segment.match(/\d/g) ?? []).length > 4
}

/** Converts paths to low-cardinality routes and removes queries/fragments before export. */
export function toScreenName(pathname: string): string {
  const segments = pathname.split('?')[0].split('#')[0].split('/').filter(Boolean)
  return segments.length === 0 ? '/' : `/${segments.map(segment => looksLikeIdentifier(segment) ? ':id' : segment).join('/')}`
}

/** Closed device set prevents user-agent fingerprinting and metrics-cardinality growth. */
export function toDeviceModel(userAgent: string): string {
  if (/iPad/i.test(userAgent)) return 'ipad'
  if (/iPhone/i.test(userAgent)) return 'iphone'
  if (/Android/i.test(userAgent)) return /Mobile/i.test(userAgent) ? 'android-phone' : 'android-tablet'
  if (/Macintosh|Mac OS X/i.test(userAgent)) return 'desktop-mac'
  if (/Windows/i.test(userAgent)) return 'desktop-windows'
  if (/Linux|X11/i.test(userAgent)) return 'desktop-linux'
  return 'unknown'
}

export function toOsType(userAgent: string): string {
  if (/iPhone|iPad|iPod/i.test(userAgent)) return 'ios'
  if (/Android/i.test(userAgent)) return 'android'
  if (/Macintosh|Mac OS X/i.test(userAgent)) return 'darwin'
  if (/Windows/i.test(userAgent)) return 'windows'
  if (/Linux|X11/i.test(userAgent)) return 'linux'
  return 'unknown'
}

export function toOsVersion(userAgent: string): string {
  const match = /(?:Windows NT|Android|CPU(?: iPhone)? OS|Mac OS X) ([0-9]+(?:[._][0-9]+)?)/i.exec(userAgent)
  return match ? match[1].replace(/_/g, '.') : 'unknown'
}

export interface RumResourceInput { userAgent: string; appVersion: string }

export function buildRumResourceAttributes(input: RumResourceInput): Record<string, string> {
  return {
    [ATTR_SERVICE_NAME]: RUM_SERVICE_NAME,
    [ATTR_SERVICE_VERSION]: input.appVersion,
    [ATTR_APP_VERSION]: input.appVersion,
    [ATTR_DEVICE_MODEL]: toDeviceModel(input.userAgent),
    [ATTR_OS_TYPE]: toOsType(input.userAgent),
    [ATTR_OS_VERSION]: toOsVersion(input.userAgent),
  }
}

let provider: WebTracerProvider | undefined

/** Creates a real browser provider; processor injection gives tests exported-span proof. */
export function createRumProvider(input: RumResourceInput, spanProcessor?: SpanProcessor): WebTracerProvider {
  const processor = spanProcessor ?? new BatchSpanProcessor(new OTLPTraceExporter({ url: RUM_INGEST_PATH }), {
    scheduledDelayMillis: 5_000,
    maxExportBatchSize: 64,
  })
  return new WebTracerProvider({
    resource: resourceFromAttributes(buildRumResourceAttributes(input)),
    spanProcessors: [processor],
  })
}

export function initRum(input: RumResourceInput, spanProcessor?: SpanProcessor): WebTracerProvider {
  if (provider) return provider
  provider = createRumProvider(input, spanProcessor)
  provider.register()
  return provider
}

export function resetRumForTests(): void { provider = undefined }

function rumTracer(): Tracer { return trace.getTracer(RUM_SERVICE_NAME) }

/** The span name is a screen to support Tempo span-name aggregations as well as attribute queries. */
export function recordScreenView(pathname: string, tracer: Tracer = rumTracer()): void {
  const screen = toScreenName(pathname)
  const span = tracer.startSpan(`screen.${screen}`, {
    attributes: { [ATTR_SCREEN_NAME]: screen, [ATTR_HTTP_ROUTE]: screen },
  })
  context.with(trace.setSpan(context.active(), span), () => span.end())
}
