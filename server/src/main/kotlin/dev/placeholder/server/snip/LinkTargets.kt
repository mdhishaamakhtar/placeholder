package dev.placeholder.server.snip

import dev.placeholder.server.InvalidInputException
import java.net.IDN
import java.net.URI

/**
 * Where a short link may send people, and in what form. A redirect's address travels in the Location header, which
 * is ASCII only, so an internationalised host becomes its punycode form and every other character outside printable
 * ASCII is percent-encoded as UTF-8. What's already percent-encoded stays exactly as it was.
 *
 * Links back to this site are refused: a short link to a short link adds nothing, and two of them pointing at each
 * other would redirect forever.
 */
class LinkTargets(
    publicBaseUrl: String,
) {
    private val ownHost = URI(publicBaseUrl).host.lowercase()

    /** [link] is a single http(s) URL, as `Rules.asLink` accepts it. */
    fun normalize(link: String): String {
        val authorityStart = link.indexOf("://") + SCHEME_SEPARATOR.length
        val authorityEnd = link.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 } ?: link.length
        val authority = link.substring(authorityStart, authorityEnd)

        val userInfoEnd = authority.lastIndexOf('@')
        val hostAndPort = authority.substring(userInfoEnd + 1)
        // An IPv6 literal ([::1]:8080) has colons of its own; it's ASCII already, so it passes through as it is.
        val hostEnd = if (hostAndPort.startsWith("[")) hostAndPort.indexOf(']') + 1 else hostAndPort.indexOf(':')
        val host = if (hostEnd > 0) hostAndPort.substring(0, hostEnd) else hostAndPort
        val asciiHost =
            try {
                IDN.toASCII(host).lowercase()
            } catch (_: IllegalArgumentException) {
                throw InvalidInputException(INVALID)
            }
        if (asciiHost.isEmpty()) throw InvalidInputException(INVALID)
        if (asciiHost == ownHost) throw InvalidInputException(OWN_SITE)

        return buildString {
            append(link, 0, authorityStart)
            if (userInfoEnd >= 0) append(encode(authority.substring(0, userInfoEnd + 1)))
            append(asciiHost)
            append(hostAndPort, host.length, hostAndPort.length)
            append(encode(link.substring(authorityEnd)))
        }
    }

    private fun encode(part: String): String =
        buildString {
            part.codePoints().forEach { point ->
                if (point in PRINTABLE_ASCII) {
                    appendCodePoint(point)
                } else {
                    String(Character.toChars(point)).encodeToByteArray().forEach { append("%%%02X".format(it)) }
                }
            }
        }

    private companion object {
        const val SCHEME_SEPARATOR = "://"
        val PRINTABLE_ASCII = 0x21..0x7E
        const val INVALID = "That link's address isn't valid."
        const val OWN_SITE = "That's already a placeholder link. Share it as it is."
    }
}
