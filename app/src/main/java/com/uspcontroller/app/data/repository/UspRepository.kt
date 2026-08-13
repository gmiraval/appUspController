package com.uspcontroller.app.data.repository

import com.uspcontroller.app.data.protobuf.UspMessageBuilder
import com.uspcontroller.app.data.protobuf.UspRecordWrapper
import com.uspcontroller.app.data.protobuf.UspResponseParser
import com.uspcontroller.app.data.transport.WebSocketMtpClient
import com.uspcontroller.app.domain.model.UspException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that orchestrates USP message construction, transport, and response parsing.
 *
 * Sits between the use cases and the lower-level transport/protobuf layers,
 * providing a clean suspend API for parameter get/set operations.
 *
 * @param mtpClient The WebSocket MTP client for sending/receiving USP Records.
 * @param controllerEid The Endpoint ID of this Controller (e.g., "os::usp-controller-android").
 */
@Singleton
class UspRepository @Inject constructor(
    private val mtpClient: WebSocketMtpClient,
    private val controllerEid: String
) {

    /**
     * Sends a USP Get request for one or more TR-181 parameter paths.
     *
     * @param agentEid The Endpoint ID of the target USP Agent.
     * @param paths List of full parameter paths to retrieve.
     * @return [Result.success] with a map of path -> value, or [Result.failure] with [UspException].
     */
    suspend fun getParameters(agentEid: String, paths: List<String>): Result<Map<String, String>> {
        return try {
            val msg = UspMessageBuilder.buildGetRequest(paths)
            val msgId = msg.header.msgId
            val record = UspRecordWrapper.wrapMessage(msg, controllerEid, agentEid)

            val response = mtpClient.sendAndAwait(record, msgId)

            when (val parsed = UspResponseParser.parse(response)) {
                is UspResponseParser.ParsedResponse.GetResponse ->
                    Result.success(parsed.params)
                is UspResponseParser.ParsedResponse.UspErrorResponse ->
                    Result.failure(UspException(parsed.code, parsed.message))
                is UspResponseParser.ParsedResponse.SetResponse ->
                    Result.failure(UspException(-1, "Unexpected SetResponse for Get request"))
            }
        } catch (e: UspException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(UspException(-1, e.message ?: "Unknown error during Get"))
        }
    }

    /**
     * Sends a USP Set request to update a single parameter.
     *
     * @param agentEid The Endpoint ID of the target USP Agent.
     * @param path The full TR-181 parameter path to update.
     * @param value The new value to set.
     * @return [Result.success] on successful update, or [Result.failure] with [UspException].
     */
    suspend fun setParameter(agentEid: String, path: String, value: String): Result<Unit> {
        return try {
            val msg = UspMessageBuilder.buildSetRequest(mapOf(path to value))
            val msgId = msg.header.msgId
            val record = UspRecordWrapper.wrapMessage(msg, controllerEid, agentEid)

            val response = mtpClient.sendAndAwait(record, msgId)

            when (val parsed = UspResponseParser.parse(response)) {
                is UspResponseParser.ParsedResponse.SetResponse ->
                    Result.success(Unit)
                is UspResponseParser.ParsedResponse.UspErrorResponse ->
                    Result.failure(UspException(parsed.code, parsed.message))
                is UspResponseParser.ParsedResponse.GetResponse ->
                    Result.failure(UspException(-1, "Unexpected GetResponse for Set request"))
            }
        } catch (e: UspException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(UspException(-1, e.message ?: "Unknown error during Set"))
        }
    }
}
