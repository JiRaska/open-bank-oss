// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

import io.quarkus.arc.Arc
import io.smallrye.mutiny.Uni
import java.time.Duration

/**
 * Opens one connection on the reactive pool with `select 1`. Reflective so libs-runtime needs no
 * compile dependency on the sql client, and services without one (agent, ap2, customer-edge,
 * finrep) are reported as such rather than failing to link.
 */
internal object ReactivePoolWarmup {
    private const val POOL_CLASS = "io.vertx.mutiny.sqlclient.Pool"

    fun selectOne(timeout: Duration): String {
        val poolClass = try {
            Class.forName(POOL_CLASS, false, Thread.currentThread().contextClassLoader ?: javaClass.classLoader)
        } catch (@Suppress("SwallowedException") e: ClassNotFoundException) {
            return "no reactive sql client on classpath"
        }
        val handle = Arc.container().instance(poolClass)
        if (!handle.isAvailable) return "no reactive Pool bean"
        val pool = handle.get()
        val query = poolClass.getMethod("query", String::class.java).invoke(pool, "select 1")
        val uni = query.javaClass.getMethod("execute").invoke(query) as Uni<*>
        val rows = uni.await().atMost(timeout)
        return "select 1 -> ${rows?.javaClass?.simpleName}"
    }
}
