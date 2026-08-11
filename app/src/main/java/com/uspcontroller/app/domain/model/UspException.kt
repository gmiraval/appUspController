package com.uspcontroller.app.domain.model

/**
 * Represents an error received from the USP Agent in a USP Error message.
 *
 * @param code The USP error code as defined in TR-369 (e.g., 7004 = Invalid arguments).
 * @param uspMessage The human-readable error description from the Agent.
 */
class UspException(
    val code: Int,
    val uspMessage: String
) : Exception("USP Error $code: $uspMessage") {

    override fun toString(): String = "UspException(code=$code, message='$uspMessage')"
}
