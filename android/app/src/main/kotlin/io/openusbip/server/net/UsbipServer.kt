package io.openusbip.server.net

import io.openusbip.server.protocol.CmdSubmit
import io.openusbip.server.protocol.CmdUnlink
import io.openusbip.server.protocol.OpHeader
import io.openusbip.server.protocol.OpReply
import io.openusbip.server.protocol.Protocol
import io.openusbip.server.protocol.RetSubmit
import io.openusbip.server.protocol.RetUnlink
import io.openusbip.server.protocol.Wire
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A USB/IP server: the network half of exporting devices from this phone.
 *
 * Protocol shape, which drives the structure below:
 *
 *  - A connection begins in the "op" phase with an 8-byte header.
 *  - OP_REQ_DEVLIST is answered and the connection then ends. The reference
 *    client opens a fresh connection to import, so holding it open serves no
 *    purpose.
 *  - OP_REQ_IMPORT claims one device and the connection switches permanently
 *    to the URB phase, becoming a stream of 48-byte commands until it drops.
 *
 * One thread per connection. URBs on a single device are inherently serialised
 * by the client's endpoint queueing, so a thread per connection costs little
 * and keeps ordering obvious.
 */
class UsbipServer(
    private val registry: DeviceRegistry,
    private val port: Int = Protocol.DEFAULT_PORT,
    private val listener: Listener = Listener.NONE,
) {

    /** Hooks for the UI, so it can show what is happening without polling. */
    interface Listener {
        fun onClientConnected(client: String) {}
        fun onClientDisconnected(client: String) {}
        fun onDeviceImported(client: String, busid: String) {}
        fun onDeviceReleased(client: String, busid: String) {}
        fun onError(context: String, error: Throwable) {}

        companion object {
            val NONE = object : Listener {}
        }
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool()

    val isRunning: Boolean get() = running.get()

    /** The port actually bound, useful when [port] was 0 in tests. */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    /**
     * Bind and begin accepting. Returns once listening, with the accept loop
     * running on its own thread.
     */
    fun start() {
        if (running.getAndSet(true)) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket

        workers.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    workers.execute { serveConnection(client) }
                } catch (e: IOException) {
                    // A close() during accept() lands here; only report it if
                    // we did not ask for the shutdown.
                    if (running.get()) listener.onError("accept", e)
                }
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
    }

    // --- per-connection handling -----------------------------------------

    private fun serveConnection(socket: Socket) {
        val client = "${socket.inetAddress.hostAddress}:${socket.port}"
        listener.onClientConnected(client)

        // Nagle would batch small URB replies and add latency to every
        // request/response round trip, which USB is very sensitive to.
        runCatching { socket.tcpNoDelay = true }

        try {
            socket.getInputStream().use { input ->
                BufferedOutputStream(socket.getOutputStream()).use { output ->
                    runOpPhase(client, input, output)
                }
            }
        } catch (e: Exception) {
            // A client vanishing mid-stream is routine, not exceptional.
            if (running.get()) listener.onError("session $client", e)
        } finally {
            registry.releaseAll(client)
            runCatching { socket.close() }
            listener.onClientDisconnected(client)
        }
    }

    private fun runOpPhase(client: String, input: InputStream, output: OutputStream) {
        val header = OpHeader.decode(Wire.readExactly(input, Protocol.OP_HEADER_SIZE))

        when (header.code) {
            Protocol.OP_REQ_DEVLIST -> {
                output.write(OpReply.devlist(registry.availableDevices()))
                output.flush()
                // The client closes after a devlist; nothing more to do.
            }

            Protocol.OP_REQ_IMPORT -> {
                val busidBytes = Wire.readExactly(input, Protocol.BUSID_LEN)
                val busid = Wire.reader(busidBytes).let { buf ->
                    val raw = ByteArray(Protocol.BUSID_LEN)
                    buf.get(raw)
                    val end = raw.indexOfFirst { it == 0.toByte() }
                        .let { if (it < 0) raw.size else it }
                    String(raw, 0, end, Charsets.US_ASCII)
                }

                val backend = registry.claim(busid, client)
                if (backend == null) {
                    // Unknown or already held. The failure reply is the bare
                    // header -- see OpReply.importFailure.
                    output.write(OpReply.importFailure())
                    output.flush()
                    return
                }

                output.write(OpReply.importSuccess(backend.device))
                output.flush()
                listener.onDeviceImported(client, busid)

                try {
                    runUrbPhase(backend, input, output)
                } finally {
                    registry.release(busid, client)
                    listener.onDeviceReleased(client, busid)
                }
            }

            else -> {
                // Unknown opcode: say nothing and drop the connection rather
                // than guessing at a reply shape the client would misparse.
                listener.onError(
                    "op phase",
                    IllegalArgumentException("unsupported opcode 0x%04x".format(header.code)),
                )
            }
        }
    }

    /**
     * Pump URBs until the connection closes.
     *
     * Every message here is a fixed 48-byte header. For an OUT transfer the
     * payload follows immediately and must be consumed even if the transfer is
     * going to be refused -- leaving it in the stream desynchronises everything
     * after it.
     */
    private fun runUrbPhase(backend: UsbBackend, input: InputStream, output: OutputStream) {
        while (running.get()) {
            val headerBytes = try {
                Wire.readExactly(input, Protocol.URB_HEADER_SIZE)
            } catch (e: IOException) {
                return   // client detached
            }

            val command = Wire.reader(headerBytes).int

            when (command) {
                Protocol.CMD_SUBMIT -> handleSubmit(backend, headerBytes, input, output)

                Protocol.CMD_UNLINK -> {
                    val unlink = CmdUnlink.decode(headerBytes)
                    // URBs are serviced synchronously here, so by the time an
                    // unlink arrives its target has already completed. Report
                    // -ECONNRESET, which is what the kernel expects for "the
                    // URB was not pending".
                    output.write(
                        RetUnlink(
                            seqnum = unlink.header.seqnum,
                            devid = unlink.header.devid,
                            status = -104,
                        ).encode(),
                    )
                    output.flush()
                }

                else -> {
                    listener.onError(
                        "urb phase",
                        IllegalArgumentException("unsupported command 0x%08x".format(command)),
                    )
                    return
                }
            }
        }
    }

    private fun handleSubmit(
        backend: UsbBackend,
        headerBytes: ByteArray,
        input: InputStream,
        output: OutputStream,
    ) {
        val cmd = CmdSubmit.decode(headerBytes)

        // Consume an OUT payload unconditionally: it is already on the wire,
        // and skipping it would corrupt every following message.
        val outData = if (!cmd.header.isIn && cmd.transferBufferLength > 0) {
            Wire.readExactly(input, cmd.transferBufferLength)
        } else {
            null
        }

        val result = if (cmd.isIsochronous) {
            // Android has no isochronous API. Refuse cleanly rather than
            // pretending, so the client's driver sees a real error.
            UrbResult.protocolError()
        } else {
            try {
                backend.submit(cmd, outData)
            } catch (e: Exception) {
                listener.onError("urb ep${cmd.header.ep}", e)
                UrbResult.stall()
            }
        }

        // An IN transfer must never claim more than the client asked for.
        val payload = if (cmd.header.isIn) {
            result.data?.let {
                if (it.size > cmd.transferBufferLength) {
                    it.copyOf(cmd.transferBufferLength)
                } else {
                    it
                }
            }
        } else {
            null
        }

        val ret = RetSubmit(
            seqnum = cmd.header.seqnum,
            devid = cmd.header.devid,
            direction = cmd.header.direction,
            ep = cmd.header.ep,
            status = result.status,
            actualLength = if (cmd.header.isIn) (payload?.size ?: 0) else result.actualLength,
        )

        output.write(ret.encode())
        if (payload != null && payload.isNotEmpty()) output.write(payload)
        output.flush()
    }
}
