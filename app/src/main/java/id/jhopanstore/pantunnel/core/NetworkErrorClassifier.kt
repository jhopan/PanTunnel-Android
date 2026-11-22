package id.jhopanstore.pantunnel.core

/**
 * How a sing-box core log line should be treated.
 *
 * The distinction matters because the two kinds look identical in a raw log but mean opposite
 * things:
 *  - [TRANSIENT] is one dead connection. The tunnel is fine; a new connection works immediately.
 *  - [FATAL] means the tunnel itself cannot carry traffic and a reconnect is required.
 *
 * Before this existed every line landed in the status box unlabelled, so users could not tell a
 * harmless "read tcp: connection reset" from a broken tunnel.
 */
enum class ErrorKind {
    /** Not worth surfacing. */
    IGNORE,

    /** A single connection ended. Tunnel still healthy. */
    TRANSIENT,

    /** The tunnel cannot carry traffic. */
    FATAL,
}

object NetworkErrorClassifier {

    /** Substrings that only ever appear on an already-established connection dying. */
    private val transientMarkers = listOf(
        "read tcp",
        "write tcp",
        "connection reset by peer",
        "broken pipe",
        "ws closed",
        "unexpected eof",
        "eof",
        "context canceled",
        "use of closed network connection",
    )

    /** Substrings that mean the outbound could not be established at all. */
    private val fatalMarkers = listOf(
        "connection refused",
        "no route to host",
        "network is unreachable",
        "tls handshake",
        "authentication failed",
        "invalid user",
        "invalid uuid",
        "handshake failed",
        "no such host",
    )

    /**
     * @param line         raw core log line (already lowercased by the caller is NOT required)
     * @param connecting   true while the tunnel is still being established; during that phase
     *                     dial/handshake failures are normal and must not be called fatal.
     */
    fun classify(line: String, connecting: Boolean = false): ErrorKind {
        val lower = line.lowercase()

        if (fatalMarkers.any { lower.contains(it) }) return ErrorKind.FATAL
        if (transientMarkers.any { lower.contains(it) }) return ErrorKind.TRANSIENT

        // A dial timeout while connecting is just a slow handshake, not a dead tunnel.
        if (lower.contains("timeout") || lower.contains("deadline exceeded")) {
            return if (connecting) ErrorKind.IGNORE else ErrorKind.TRANSIENT
        }

        if (lower.contains("error") || lower.contains("fatal") || lower.contains("warn")) {
            return if (connecting) ErrorKind.IGNORE else ErrorKind.TRANSIENT
        }

        return ErrorKind.IGNORE
    }

    /** Short human label shown in the status box. */
    fun label(kind: ErrorKind): String = when (kind) {
        ErrorKind.TRANSIENT -> "sementara"
        ErrorKind.FATAL -> "fatal"
        ErrorKind.IGNORE -> ""
    }
}