package io.openusbip.server.mtp

/**
 * The 12-byte container that wraps every PTP message, plus its payload.
 *
 * A transaction is a fixed sequence: the host sends a Command container, an
 * optional Data container flows in whichever direction the operation implies,
 * and the device closes with a Response container carrying the same
 * transaction id. Losing track of that id is how a client ends up matching a
 * reply to the wrong request.
 */
data class PtpContainer(
    val type: Int,
    val code: Int,
    val transactionId: Int,
    /** Command/response parameters. At most five, per the spec. */
    val parameters: List<Int> = emptyList(),
    /** Data-container payload; empty for command and response containers. */
    val payload: ByteArray = ByteArray(0),
) {

    val totalLength: Int
        get() = MtpConstants.CONTAINER_HEADER_SIZE +
            if (type == MtpConstants.CONTAINER_DATA) payload.size else parameters.size * 4

    fun encode(): ByteArray {
        val w = PtpCodec.Writer(totalLength)
        w.u32(totalLength)
        w.u16(type)
        w.u16(code)
        w.u32(transactionId)

        if (type == MtpConstants.CONTAINER_DATA) {
            w.bytes(payload)
        } else {
            parameters.forEach { w.u32(it) }
        }
        return w.toByteArray()
    }

    /** Convenience accessor; absent parameters read as 0, as clients expect. */
    fun param(index: Int): Int = parameters.getOrElse(index) { 0 }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PtpContainer) return false
        return type == other.type && code == other.code &&
            transactionId == other.transactionId &&
            parameters == other.parameters &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = type
        r = 31 * r + code
        r = 31 * r + transactionId
        r = 31 * r + parameters.hashCode()
        r = 31 * r + payload.contentHashCode()
        return r
    }

    companion object {

        /**
         * Decode one container.
         *
         * A command container's parameters are however many u32s follow the
         * header -- the spec allows nought to five, and clients genuinely send
         * short ones, so the count comes from the declared length rather than
         * being assumed.
         */
        fun decode(bytes: ByteArray): PtpContainer? {
            if (bytes.size < MtpConstants.CONTAINER_HEADER_SIZE) return null

            val r = PtpCodec.Reader(bytes)
            val length = r.u32()
            val type = r.u16()
            val code = r.u16()
            val transactionId = r.u32()

            // Trust the smaller of declared and actual: a truncated read must
            // not send us past the end of the buffer.
            val usable = minOf(
                if (length in MtpConstants.CONTAINER_HEADER_SIZE..bytes.size) length else bytes.size,
                bytes.size,
            )
            val bodyBytes = usable - MtpConstants.CONTAINER_HEADER_SIZE

            return if (type == MtpConstants.CONTAINER_DATA) {
                PtpContainer(type, code, transactionId, payload = r.bytes(bodyBytes))
            } else {
                val count = (bodyBytes / 4).coerceIn(0, 5)
                PtpContainer(type, code, transactionId, List(count) { r.u32() })
            }
        }

        fun response(code: Int, transactionId: Int, vararg params: Int) = PtpContainer(
            type = MtpConstants.CONTAINER_RESPONSE,
            code = code,
            transactionId = transactionId,
            parameters = params.toList(),
        )

        fun data(code: Int, transactionId: Int, payload: ByteArray) = PtpContainer(
            type = MtpConstants.CONTAINER_DATA,
            code = code,
            transactionId = transactionId,
            payload = payload,
        )
    }
}
