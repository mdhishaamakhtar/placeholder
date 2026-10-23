package dev.placeholder.server.analytics

import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Turns a request into an anonymous visitor id for unique-visitor counts: an HMAC of the IP address and user agent
 * under a server-side key. The same browser gets the same id, so it's counted once; without the key the id can't be
 * traced back to an address. The id itself is never stored either: it only goes into HyperLogLog sketches, which
 * keep a few bits of its hash and can't be read back.
 */
class Visitors(key: String) {
    private val key = SecretKeySpec(key.encodeToByteArray(), ALGORITHM)

    fun id(ip: String, userAgent: String): String {
        // Mac instances aren't thread-safe; making one per call is cheap next to the HMAC itself.
        val mac = Mac.getInstance(ALGORITHM).apply { init(key) }
        val digest = mac.doFinal("$ip\n$userAgent".encodeToByteArray())
        return HexFormat.of().formatHex(digest, 0, ID_BYTES)
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"

        /** 128 bits: collisions between distinct visitors are out of the question at any realistic traffic. */
        const val ID_BYTES = 16
    }
}
