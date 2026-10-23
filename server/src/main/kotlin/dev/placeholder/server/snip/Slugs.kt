package dev.placeholder.server.snip

import dev.placeholder.shared.Rules
import java.security.SecureRandom

/**
 * Random slugs, no coordination between servers. With 57 symbols and 7 characters there are ~1.9 trillion,
 * so a collision is rare; when one happens the insert fails and [SnipService] simply rolls again.
 */
class Slugs(
    private val random: SecureRandom = SecureRandom(),
) {
    fun next(): String =
        buildString(Rules.SLUG_LENGTH) {
            repeat(Rules.SLUG_LENGTH) { append(Rules.SLUG_ALPHABET[random.nextInt(Rules.SLUG_ALPHABET.length)]) }
        }
}
