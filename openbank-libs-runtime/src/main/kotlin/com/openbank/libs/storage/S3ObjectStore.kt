// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.storage

import io.quarkus.arc.properties.IfBuildProperty
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.net.URI
import java.time.Duration
import java.util.Optional

/**
 * Production [ObjectStorePort] adapter (ADR-0161 D2) — backed by AWS S3 via the
 * real AWS SDK v2 client (`software.amazon.awssdk:s3`), superseding the bespoke
 * SigV4 signer in `openbank-analytics-sink`'s `S3WormArchive` as the platform
 * default for new object-storage consumers.
 *
 * Applies server-side encryption (SSE, `AES256`) on every `put`. Write-once /
 * evidential-integrity guarantees (S3 Object Lock in COMPLIANCE mode) and
 * SSE-KMS key selection are a property of the *bucket*, provisioned by Terraform
 * per ADR-0161 D3 — this adapter deliberately does NOT hardcode a KMS key ARN or
 * a lock configuration; that is an environment/Terraform concern, never a
 * compiled-in literal.
 *
 * Selected by `openbank.objectstore.backend=s3` (see [PostgresBlobStore] for the
 * default, non-S3 adapter). Credentials are resolved via the AWS SDK's
 * [DefaultCredentialsProvider] chain (IRSA in-cluster, environment variables
 * locally/CI) — no access key/secret ever flows through OpenBank configuration.
 */
@ApplicationScoped
@IfBuildProperty(name = "openbank.objectstore.backend", stringValue = "s3")
class S3ObjectStore(
    @ConfigProperty(name = "openbank.objectstore.s3.bucket")
    private val bucket: String,

    @ConfigProperty(name = "openbank.objectstore.s3.region", defaultValue = "eu-central-1")
    private val region: String,

    // Optional<String>, not a plain String (CLAUDE.md pitfall): SmallRye Config throws
    // SRCFG00040 at boot for a missing optional property typed as plain String. Only ever
    // set for localstack-style testing; production leaves this unset and the SDK talks to
    // the real regional S3 endpoint.
    @ConfigProperty(name = "openbank.objectstore.s3.endpoint-override")
    private val endpointOverride: Optional<String>,
) : ObjectStorePort {

    private val client: S3Client by lazy {
        val builder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
        endpointOverride.ifPresent { builder.endpointOverride(URI.create(it)) }
        builder.build()
    }

    private val presigner: S3Presigner by lazy {
        val builder = S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
        endpointOverride.ifPresent { builder.endpointOverride(URI.create(it)) }
        builder.build()
    }

    override suspend fun put(key: String, bytes: ByteArray, contentType: String, metadata: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .metadata(metadata)
                .build()
            client.putObject(request, RequestBody.fromBytes(bytes))
        }
    }

    override suspend fun get(key: String): ByteArray = withContext(Dispatchers.IO) {
        val request = GetObjectRequest.builder().bucket(bucket).key(key).build()
        client.getObject(request).readAllBytes()
    }

    @Suppress("SwallowedException") // NoSuchKeyException itself IS the "does not exist" signal
    override suspend fun exists(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
            true
        } catch (e: NoSuchKeyException) {
            false
        } catch (e: S3Exception) {
            // HeadObject returns a bare 404 with no XML error body, so the SDK sometimes
            // surfaces it as a generic S3Exception rather than NoSuchKeyException.
            if (e.statusCode() == HTTP_NOT_FOUND) false else throw e
        }
    }

    override suspend fun presignGet(key: String, ttlSeconds: Long): String = withContext(Dispatchers.IO) {
        val getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofSeconds(ttlSeconds))
            .getObjectRequest(getRequest)
            .build()
        presigner.presignGetObject(presignRequest).url().toString()
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
