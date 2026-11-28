// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.keycloak.mcp;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/** Attaches the MCP issuer workload identity only to the MCP authority HTTP transports. */
final class McpOutboundHttp {
    private static final String KEY_STORE = "MCP_AUTHORITY_TLS_KEYSTORE";
    private static final String TRUST_STORE = "MCP_AUTHORITY_TLS_TRUSTSTORE";
    private static final String PASSWORD_FILE = "MCP_AUTHORITY_TLS_PASSWORD_FILE";

    private McpOutboundHttp() {
    }

    static HttpClient create(Duration timeout) {
        var http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER);
        var keyStore = System.getenv(KEY_STORE);
        var trustStore = System.getenv(TRUST_STORE);
        var passwordFile = System.getenv(PASSWORD_FILE);
        if (keyStore == null && trustStore == null && passwordFile == null) {
            return http.build();
        }
        if (missing(keyStore) || missing(trustStore) || missing(passwordFile)) {
            throw new IllegalStateException("MCP authority TLS requires key store, trust store and password file");
        }
        try {
            var password = Files.readString(Path.of(passwordFile)).stripTrailing().toCharArray();
            try {
                var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(load(keyStore, password), password);
                var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trust.init(load(trustStore, password));
                var context = SSLContext.getInstance("TLS");
                context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
                var parameters = new SSLParameters();
                parameters.setProtocols(new String[]{"TLSv1.3"});
                return http.sslContext(context).sslParameters(parameters)
                        .build();
            } finally {
                Arrays.fill(password, '\0');
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("MCP authority TLS material could not be loaded", e);
        }
    }

    private static KeyStore load(String path, char[] password) throws IOException, GeneralSecurityException {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(Path.of(path))) {
            store.load(input, password);
        }
        return store;
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }
}
