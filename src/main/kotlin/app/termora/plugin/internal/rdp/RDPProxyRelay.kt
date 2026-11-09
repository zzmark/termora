package app.termora.plugin.internal.rdp

import app.termora.AuthenticationType
import app.termora.Host
import app.termora.I18n
import app.termora.ProxyType
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class RDPProxyRelay(
    private val host: Host,
    private val onFailure: (IOException) -> Unit,
) : AutoCloseable {
    private val listener = ServerSocket(0, 0, InetAddress.ofLiteral(RDPConnection.LOOPBACK))
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val sockets = mutableSetOf<Socket>()
    private val closed = AtomicBoolean()
    val port: Int get() = listener.localPort

    init {
        workers.submit {
            try {
                while (!closed.get()) {
                    val inbound = listener.accept()
                    if (track(inbound)) {
                        workers.submit { forward(inbound) }
                    }
                }
            } catch (e: IOException) {
                if (!closed.get()) onFailure(e)
            }
        }
    }

    private fun track(socket: Socket): Boolean = synchronized(sockets) {
        if (closed.get()) {
            socket.close()
            false
        } else {
            sockets.add(socket)
        }
    }

    private fun forward(inbound: Socket) {
        val upstream = Socket(java.net.Proxy.NO_PROXY)
        try {
            if (!track(upstream)) return
            val input = try {
                connectProxy(upstream)
            } catch (e: IOException) {
                if (!closed.get()) onFailure(e)
                return
            }

            val upload = workers.submit {
                try {
                    inbound.getInputStream().copyTo(upstream.getOutputStream())
                    upstream.shutdownOutput()
                } catch (_: IOException) {
                    closeSockets(inbound, upstream)
                }
            }
            input.copyTo(inbound.getOutputStream())
            inbound.shutdownOutput()
            upload.get()
        } catch (_: IOException) {
            // The RDP client handles network disconnects after proxy negotiation.
        } finally {
            closeSockets(inbound, upstream)
        }
    }

    private fun connectProxy(socket: Socket): DataInputStream {
        val proxy = host.proxy
        if (proxy.port !in 1..65535 || host.port !in 1..65535) {
            throw IOException(I18n.getString("termora.rdp.error.proxy-port"))
        }
        val target = host.host.removeSurrounding("[", "]")
        val literal = runCatching { InetAddress.ofLiteral(target) }.getOrNull()
        val hostname = if (literal != null) literal.hostAddress else try {
            IDN.toASCII(target, IDN.USE_STD3_ASCII_RULES)
        } catch (e: IllegalArgumentException) {
            throw IOException(I18n.getString("termora.new-host.rdp.invalid-address"), e)
        }
        if (hostname.isEmpty()) throw IOException(I18n.getString("termora.new-host.rdp.invalid-address"))
        socket.connect(InetSocketAddress(proxy.host, proxy.port), HANDSHAKE_TIMEOUT_MS)
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        socket.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val passwordAuth = proxy.authenticationType == AuthenticationType.Password
        when (proxy.type) {
            ProxyType.HTTP -> connectHttp(socket, input, hostname, literal?.address?.size == 16, passwordAuth)
            ProxyType.SOCKS5 -> connectSocks(socket, input, hostname, literal?.address, passwordAuth)
            ProxyType.No -> error("A proxy is required")
        }
        socket.soTimeout = 0
        return input
    }

    private fun connectHttp(
        socket: Socket, input: DataInputStream, hostname: String, ipv6: Boolean, passwordAuth: Boolean,
    ) {
        val target = if (ipv6) "[$hostname]:${host.port}" else "$hostname:${host.port}"
        val request = buildString {
            append("CONNECT $target HTTP/1.1\r\nHost: $target\r\n")
            if (passwordAuth) {
                val credentials = "${host.proxy.username}:${host.proxy.password}".toByteArray(Charsets.UTF_8)
                try {
                    append("Proxy-Authorization: Basic ").append(Base64.getEncoder().encodeToString(credentials))
                    append("\r\n")
                } finally {
                    credentials.fill(0)
                }
            }
            append("\r\n")
        }
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        val headers = ByteArrayOutputStream()
        var suffix = 0
        do {
            if (headers.size() >= 16_384) throw IOException(I18n.getString("termora.rdp.error.proxy-response"))
            val byte = input.readUnsignedByte()
            headers.write(byte)
            suffix = (suffix shl 8) or byte
        } while (suffix != 0x0d0a0d0a)
        val status = headers.toString(Charsets.ISO_8859_1).substringBefore("\r\n")
        val code = HTTP_STATUS.matchEntire(status)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException(I18n.getString("termora.rdp.error.proxy-response"))
        if (code !in 200..299) throw IOException(I18n.getString("termora.rdp.error.http-proxy", code.toString()))
    }

    private fun connectSocks(
        socket: Socket, input: DataInputStream, hostname: String, literal: ByteArray?, passwordAuth: Boolean,
    ) {
        val output = socket.getOutputStream()
        val method: Byte = if (passwordAuth) 2 else 0
        output.write(byteArrayOf(5, 1, method))
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != method.toInt()) {
            throw IOException(I18n.getString("termora.rdp.error.socks-auth"))
        }
        if (passwordAuth) {
            val user = host.proxy.username.toByteArray(Charsets.UTF_8)
            val pass = host.proxy.password.toByteArray(Charsets.UTF_8)
            try {
                if (user.size !in 1..255 || pass.size !in 1..255) {
                    throw IOException(I18n.getString("termora.rdp.error.socks-auth"))
                }
                output.write(byteArrayOf(1, user.size.toByte()))
                output.write(user)
                output.write(pass.size)
                output.write(pass)
                if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) {
                    throw IOException(I18n.getString("termora.rdp.error.socks-auth"))
                }
            } finally {
                user.fill(0)
                pass.fill(0)
            }
        }
        val request = ByteArrayOutputStream()
        request.write(byteArrayOf(5, 1, 0))
        if (literal == null) {
            val domain = hostname.toByteArray(Charsets.US_ASCII)
            if (domain.size !in 1..255) throw IOException(I18n.getString("termora.new-host.rdp.invalid-address"))
            request.write(byteArrayOf(3, domain.size.toByte()))
            request.write(domain)
        } else {
            request.write(if (literal.size == 4) 1 else 4)
            request.write(literal)
        }
        request.write(host.port ushr 8)
        request.write(host.port and 0xff)
        output.write(request.toByteArray())

        val version = input.readUnsignedByte()
        val status = input.readUnsignedByte()
        val reserved = input.readUnsignedByte()
        if (version != 5 || reserved != 0) throw IOException(I18n.getString("termora.rdp.error.proxy-response"))
        if (status != 0) throw IOException(I18n.getString("termora.rdp.error.socks-proxy", status))
        val length = when (input.readUnsignedByte()) {
            1 -> 4
            3 -> input.readUnsignedByte()
            4 -> 16
            else -> throw IOException(I18n.getString("termora.rdp.error.proxy-response"))
        }
        input.skipNBytes((length + 2).toLong())
    }

    private fun closeSockets(vararg connections: Socket) {
        synchronized(sockets) {
            for (socket in connections) {
                sockets.remove(socket)
                runCatching { socket.close() }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        synchronized(sockets) {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
        workers.shutdownNow()
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        val HTTP_STATUS = Regex("""HTTP/1\.[01] ([0-9]{3})(?: .*)?""")
    }
}
