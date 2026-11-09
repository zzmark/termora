package app.termora.plugin.internal.rdp

import app.termora.AuthenticationType
import app.termora.Host
import app.termora.Proxy
import app.termora.ProxyType
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RDPProxyRelayTest {
    @Test
    fun `HTTP CONNECT uses the configured target and credentials and keeps buffered tunnel data`() {
        withRelay(ProxyType.HTTP, AuthenticationType.Password, upstream = { input, socket ->
            val lines = readHttpHeaders(input)
            assertEquals(
                listOf(
                    "CONNECT rdp.internal.test:3390 HTTP/1.1",
                    "Host: rdp.internal.test:3390",
                    "Proxy-Authorization: Basic dHJpYWw6cHJveHk=",
                ),
                lines,
            )
            socket.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\nreply".toByteArray())
            assertEquals("request", String(input.readNBytes(7)))
            socket.shutdownOutput()
        }) { client, _ ->
            client.getOutputStream().write("request".toByteArray())
            client.shutdownOutput()
            assertEquals("reply", String(client.getInputStream().readAllBytes()))
        }
    }

    @Test
    fun `HTTP refusal reports the status without sending disabled credentials`() {
        withRelay(ProxyType.HTTP, AuthenticationType.No, upstream = { input, socket ->
            assertFalse(readHttpHeaders(input).any { it.startsWith("Proxy-Authorization:") })
            socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray())
        }) { client, failure ->
            assertEquals(-1, client.getInputStream().read())
            assertTrue(failure.get(5, TimeUnit.SECONDS).message.orEmpty().contains("407"))
        }
    }

    @Test
    fun `SOCKS5 authenticates then requests remote resolution of the RDP hostname`() {
        withRelay(ProxyType.SOCKS5, AuthenticationType.Password, upstream = { input, socket ->
            val output = socket.getOutputStream()
            assertContentEquals(byteArrayOf(5, 1, 2), input.readNBytes(3))
            output.write(byteArrayOf(5, 2))
            assertEquals(1, input.readUnsignedByte())
            assertEquals("trial", String(input.readNBytes(input.readUnsignedByte())))
            assertEquals("proxy", String(input.readNBytes(input.readUnsignedByte())))
            output.write(byteArrayOf(1, 0))
            assertContentEquals(byteArrayOf(5, 1, 0, 3), input.readNBytes(4))
            assertEquals("rdp.internal.test", String(input.readNBytes(input.readUnsignedByte())))
            assertEquals(3390, input.readUnsignedShort())
            output.write(byteArrayOf(5, 0, 0, 3, 3, 97, 98, 99, 0, 1) + "reply".toByteArray())
            assertEquals("request", String(input.readNBytes(7)))
            socket.shutdownOutput()
        }) { client, _ ->
            client.getOutputStream().write("request".toByteArray())
            client.shutdownOutput()
            assertEquals("reply", String(client.getInputStream().readAllBytes()))
        }
    }

    @Test
    fun `SOCKS5 rejects a method the client did not offer`() {
        withRelay(ProxyType.SOCKS5, AuthenticationType.No, upstream = { input, socket ->
            assertContentEquals(byteArrayOf(5, 1, 0), input.readNBytes(3))
            socket.getOutputStream().write(byteArrayOf(5, 2))
            assertEquals(-1, input.read())
        }) { client, failure ->
            assertEquals(-1, client.getInputStream().read())
            assertTrue(failure.get(5, TimeUnit.SECONDS).message.orEmpty().contains("SOCKS5"))
        }
    }

    private fun withRelay(
        type: ProxyType,
        authentication: AuthenticationType,
        upstream: (DataInputStream, Socket) -> Unit,
        downstream: (Socket, CompletableFuture<IOException>) -> Unit,
    ) {
        val loopback = InetAddress.ofLiteral(RDPConnection.LOOPBACK)
        ServerSocket(0, 0, loopback).use { proxy ->
            proxy.soTimeout = 5_000
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val mock = executor.submit {
                    proxy.accept().use { socket ->
                        socket.soTimeout = 5_000
                        upstream(DataInputStream(socket.getInputStream()), socket)
                    }
                }
                val failure = CompletableFuture<IOException>()
                val host = Host(
                    name = "RDP relay test",
                    protocol = "RDP",
                    host = "rdp.internal.test",
                    port = 3390,
                    proxy = Proxy(type, RDPConnection.LOOPBACK, proxy.localPort, authentication, "trial", "proxy"),
                )
                val relayPort = RDPProxyRelay(host, failure::complete).use { relay ->
                    Socket(loopback, relay.port).use { client ->
                        client.soTimeout = 5_000
                        downstream(client, failure)
                    }
                    mock.get(5, TimeUnit.SECONDS)
                    relay.port
                }
                assertFailsWith<IOException> { Socket(loopback, relayPort).close() }
            }
        }
    }

    private fun readHttpHeaders(input: DataInputStream): List<String> = buildList {
        val reader = input.bufferedReader(Charsets.US_ASCII)
        while (true) {
            val line = reader.readLine() ?: error("Unexpected EOF while reading HTTP request")
            if (line.isEmpty()) break
            add(line)
        }
    }
}
