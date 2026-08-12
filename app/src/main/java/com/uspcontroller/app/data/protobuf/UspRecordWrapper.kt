package com.uspcontroller.app.data.protobuf

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.uspcontroller.app.domain.model.UspException
import usp_msg.Usp
import usp_record.UspRecord

/**
 * Wraps and unwraps USP Messages inside USP Record envelopes.
 *
 * Per TR-369, every USP message is transmitted inside a Record that provides
 * addressing (from_id, to_id) and session context. This PoC uses
 * NoSessionContext with PLAINTEXT payload security.
 */
object UspRecordWrapper {

    private const val USP_RECORD_VERSION = "1.3"

    /**
     * Wraps a [Usp.Msg] inside a USP Record envelope for transmission.
     *
     * @param msg The USP message to wrap.
     * @param fromId The Controller's Endpoint ID (e.g., "os::usp-controller-android").
     * @param toId The Agent's Endpoint ID.
     * @return Serialized Record as a byte array, ready for WebSocket binary transmission.
     */
    fun wrapMessage(msg: Usp.Msg, fromId: String, toId: String): ByteArray {
        val record = UspRecord.Record.newBuilder()
            .setVersion(USP_RECORD_VERSION)
            .setFromId(fromId)
            .setToId(toId)
            .setPayloadSecurity(UspRecord.Record.PayloadSecurity.PLAINTEXT)
            .setNoSessionContext(
                UspRecord.NoSessionContextRecord.newBuilder()
                    .setPayload(msg.toByteString())
                    .build()
            )
            .build()

        return record.toByteArray()
    }

    /**
     * Unwraps a received byte array into a USP Record and its inner USP Message.
     *
     * @param bytes Raw bytes received from the WebSocket.
     * @return A [Pair] of the parsed [UspRecord.Record] and the inner [Usp.Msg].
     * @throws UspException If the bytes cannot be parsed as a valid USP Record
     *         or the inner payload is not a valid USP Message.
     */
    fun unwrapRecord(bytes: ByteArray): Pair<UspRecord.Record, Usp.Msg> {
        val record: UspRecord.Record
        try {
            record = UspRecord.Record.parseFrom(bytes)
        } catch (e: InvalidProtocolBufferException) {
            throw UspException(-1, "Failed to parse USP Record: ${e.message}")
        }

        val payload = when {
            record.hasNoSessionContext() -> record.noSessionContext.payload
            record.hasSessionContext() && record.sessionContext.payloadCount > 0 ->
                record.sessionContext.getPayload(0)
            else -> throw UspException(-1, "USP Record contains no recognizable payload")
        }

        val msg: Usp.Msg
        try {
            msg = Usp.Msg.parseFrom(payload)
        } catch (e: InvalidProtocolBufferException) {
            throw UspException(-1, "Failed to parse USP Msg from Record payload: ${e.message}")
        }

        return record to msg
    }

    /**
     * Extracts just the [Usp.Msg] from raw record bytes (convenience method).
     *
     * @param bytes Raw bytes received from the WebSocket.
     * @return The parsed [Usp.Msg].
     * @throws UspException If parsing fails.
     */
    fun unwrapMessage(bytes: ByteArray): Usp.Msg {
        return unwrapRecord(bytes).second
    }
}
