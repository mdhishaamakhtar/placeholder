package dev.placeholder.shared

/**
 * Every page's address, in one place. The server needs them to answer page requests with the app (and a 404 for
 * anything else); the browser needs them for links. Keeping both in step by hand is how links quietly break.
 */
object Paths {
    const val HOME = "/"
    const val NEW = "/new"
    const val SIGN_IN = "/signin"
    const val LINKS = "/links"

    /** The fixed pages, as opposed to `/{slug}`. */
    val PAGES = listOf(HOME, NEW, SIGN_IN, LINKS)

    fun snip(slug: String) = "/$slug"

    fun raw(slug: String) = "/$slug/raw"

    fun edit(slug: String) = "$NEW?edit=$slug"

    fun copyOf(slug: String) = "$NEW?from=$slug"

    /** Sign in (or create an account), then continue to [next]. Only plain same-site paths are followed. */
    fun signIn(
        create: Boolean = false,
        next: String = NEW,
    ): String {
        val params =
            listOfNotNull(
                "mode=create".takeIf { create },
                "next=$next".takeIf { next != NEW && isSafeNext(next) },
            )
        return if (params.isEmpty()) SIGN_IN else "$SIGN_IN?" + params.joinToString("&")
    }

    /** A path we're willing to send someone to after signing in: same site, no scheme, no `//host` tricks. */
    fun isSafeNext(path: String): Boolean = SAFE_NEXT.matches(path)

    private val SAFE_NEXT = Regex("^/(?!/)[A-Za-z0-9/_-]*(\\?[A-Za-z0-9=_-]*)?$")
}
