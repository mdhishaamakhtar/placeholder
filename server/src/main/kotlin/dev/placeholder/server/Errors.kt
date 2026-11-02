package dev.placeholder.server

/**
 * Every failure the app expects, in one place. Messages are written for the person using the site,
 * because http/ErrorHandling.kt shows them as-is. Anything else is a bug and becomes a generic 500.
 */
sealed class AppException(
    message: String,
) : RuntimeException(message)

/** The input can't be saved as given (blank, too large, unknown language). → 400 */
class InvalidInputException(
    message: String,
) : AppException(message)

/** The content couldn't be reformatted (invalid JSON, unsupported language). → 422 */
class PrettifyException(
    message: String,
) : AppException(message)

/** Signing in is required, or the credentials didn't match. → 401 */
class UnauthorizedException(
    message: String,
) : AppException(message)

/** Signed in, but this isn't yours. → 403 */
class ForbiddenException(
    message: String,
) : AppException(message)

/** Something already exists (an account with that email). → 409 */
class ConflictException(
    message: String,
) : AppException(message)

/** The client has used up its budget for this window. → 429 with Retry-After */
class RateLimitedException(
    val retryAfterSeconds: Long,
) : AppException("Slow down a little. Try again in $retryAfterSeconds seconds.")
