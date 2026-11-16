// SPDX-License-Identifier: Apache-2.0

package com.openbank.legalprocess.isds

import com.openbank.legalprocess.PostgresLegalDeliveryInbox
import com.sun.net.httpserver.HttpServer
import org.postgresql.ds.PGSimpleDataSource
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue

/** Starts the ISDS boundary locally and runs the same poll cycle that the scheduler calls. */
object SimulatedIsdsInboxPoll {
    @JvmStatic
    fun main(args: Array<String>) {
        val mailbox = env("LEGAL_INBOX_E2E_MAILBOX")
        val deliveryId = env("LEGAL_INBOX_E2E_DELIVERY_ID")
        require(ISDS_MESSAGE_ID.matches(deliveryId))
        val message = readFixture("LEGAL_INBOX_E2E_ORIGINAL")
        val receipt = readFixture("LEGAL_INBOX_E2E_RECEIPT")
        val dataSource = PGSimpleDataSource().apply {
            setURL(env("LEGAL_INBOX_E2E_JDBC_URL"))
            user = "postgres"
            password = env("LEGAL_INBOX_E2E_DB_PASSWORD")
        }
        dataSource.connection.use { db ->
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT count(*) FROM legal_delivery_inbox").use {
                    check(it.next() && it.getLong(1) == 0L) { "E2E inbox must start empty" }
                }
            }
        }
        val store = PostgresIsdsPoll(dataSource)
        val fixture = SoapFixture(deliveryId, message, receipt)
        fixture.run { client ->
            val start = Instant.parse("2026-01-01T12:00:00Z")
            val worker = IsdsMailboxPoll(store, PostgresLegalDeliveryInbox(dataSource), client, mailbox, start)
            checkNotNull(worker.run(start.plus(Duration.ofMinutes(5))))
        }
        val run = store.recent(listOf(mailbox)).single()
        check(
            run.status == "COMPLETE" && run.discoveredCount == 1 && run.importedCount == 1 && run.pendingCount == 0L,
        ) {
            "The poll must discover and commit exactly one message and leave no pending delivery"
        }
    }

    private fun env(name: String): String = checkNotNull(System.getenv(name)) { "Missing $name" }

    private fun readFixture(name: String): String =
        Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(env(name))))
}

private class SoapFixture(private val id: String, private val message: String, private val receipt: String) {
    private val calls = ConcurrentLinkedQueue<String>()

    fun run(poll: (IsdsSoapDownloads) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/DS/") { exchange ->
            exchange.use {
                val request = exchange.requestBody.readAllBytes().decodeToString()
                val response = if (exchange.requestMethod == "POST") {
                    answer(exchange.requestURI.path, request)
                } else {
                    null
                }
                if (response == null) {
                    exchange.sendResponseHeaders(400, -1)
                } else {
                    exchange.responseHeaders.set("Content-Type", "text/xml; charset=utf-8")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.write(response)
                }
            }
        }
        server.start()
        try {
            val http = HttpClient.newHttpClient()
            val client = IsdsSoapDownloads.usingTransport { endpoint, xml ->
                val request = HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + server.address.port + "/DS/$endpoint",
                    ),
                )
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(xml))
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                check(response.statusCode() == 200) { "Simulated ISDS rejected the SOAP request" }
                response.body()
            }
            poll(client)
            check(
                calls.toList() == listOf("GetListOfReceivedMessages", "SignedMessageDownload", "GetSignedDeliveryInfo"),
            ) {
                "Expected exactly one listing, one signed message and one signed delivery receipt"
            }
        } finally {
            server.stop(0)
        }
    }

    private fun answer(path: String, request: String): ByteArray? = when {
        path == "/DS/dx" && "<v:GetListOfReceivedMessages>" in request ->
            soap(
                "GetListOfReceivedMessages",
                "<v:dmRecords><v:dmRecord><v:dmID>$id</v:dmID></v:dmRecord></v:dmRecords>",
            )
        path == "/DS/dz" && "<v:SignedMessageDownload>" in request && "<v:dmID>$id</v:dmID>" in request ->
            soap("SignedMessageDownload", "<v:dmSignature>$message</v:dmSignature>")
        path == "/DS/dx" && "<v:GetSignedDeliveryInfo>" in request && "<v:dmID>$id</v:dmID>" in request ->
            soap("GetSignedDeliveryInfo", "<v:dmSignature>$receipt</v:dmSignature>")
        else -> null
    }

    private fun soap(operation: String, body: String): ByteArray {
        calls.add(operation)
        return """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:v="http://isds.czechpoint.cz/v20">
              <s:Body><v:${operation}Response>$body
                <v:dmStatus><v:dmStatusCode>0000</v:dmStatusCode></v:dmStatus>
              </v:${operation}Response></s:Body>
            </s:Envelope>
        """.trimIndent().encodeToByteArray()
    }
}
