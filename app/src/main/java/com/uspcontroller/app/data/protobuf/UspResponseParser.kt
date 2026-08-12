package com.uspcontroller.app.data.protobuf

import usp_msg.Usp

/**
 * Parses USP response messages into domain-friendly sealed class results.
 *
 * Handles GetResp, SetResp, and Error message types from USP Agent responses.
 */
object UspResponseParser {

    /**
     * Represents a parsed USP response in a type-safe manner.
     */
    sealed class ParsedResponse {

        /**
         * Successful Get response containing resolved parameter key-value pairs.
         *
         * Keys are fully qualified TR-181 paths (e.g., "Device.DeviceInfo.ProcessStatus.CPUUsage").
         */
        data class GetResponse(val params: Map<String, String>) : ParsedResponse()

        /**
         * Successful Set response containing the paths that were updated.
         */
        data class SetResponse(val updatedPaths: List<String>) : ParsedResponse()

        /**
         * Error response from the USP Agent.
         *
         * @param code USP error code (e.g., 7004 = Invalid arguments, 7012 = Invalid path).
         * @param message Human-readable error description.
         */
        data class UspErrorResponse(val code: Int, val message: String) : ParsedResponse()
    }

    /**
     * Parses a [Usp.Msg] response into a [ParsedResponse].
     *
     * Routing is based on the message type in the header:
     * - GET_RESP -> [ParsedResponse.GetResponse]
     * - SET_RESP -> [ParsedResponse.SetResponse]
     * - ERROR -> [ParsedResponse.UspErrorResponse]
     * - Others -> [ParsedResponse.UspErrorResponse] with code -1
     *
     * @param msg The USP message received from the Agent.
     * @return A [ParsedResponse] representing the parsed result.
     */
    fun parse(msg: Usp.Msg): ParsedResponse {
        // Check for body-level error first (USP Error message type)
        if (msg.body.msgBodyCase == Usp.Body.MsgBodyCase.ERROR) {
            val error = msg.body.error
            return ParsedResponse.UspErrorResponse(
                code = error.errCode,
                message = error.errMsg
            )
        }

        return when (msg.header.msgType) {
            Usp.Header.MsgType.GET_RESP -> parseGetResp(msg)
            Usp.Header.MsgType.SET_RESP -> parseSetResp(msg)
            Usp.Header.MsgType.ERROR -> {
                // Header says ERROR but body might be structured differently
                if (msg.body.hasError()) {
                    val error = msg.body.error
                    ParsedResponse.UspErrorResponse(
                        code = error.errCode,
                        message = error.errMsg
                    )
                } else {
                    ParsedResponse.UspErrorResponse(-1, "Error message with no error body")
                }
            }
            else -> ParsedResponse.UspErrorResponse(
                code = -1,
                message = "Unexpected message type: ${msg.header.msgType}"
            )
        }
    }

    /**
     * Parses a GetResp message, extracting all resolved parameter key-value pairs.
     *
     * The GetResp structure is:
     * - reqPathResults[] (one per requested path)
     *   - resolvedPathResults[] (one per resolved object instance)
     *     - resultParams: Map<String, String> (param name -> value)
     *
     * Full paths are reconstructed as: resolvedPath + paramKey
     */
    private fun parseGetResp(msg: Usp.Msg): ParsedResponse {
        val response = msg.body.response
        if (!response.hasGetResp()) {
            return ParsedResponse.UspErrorResponse(-1, "GET_RESP header but no get_resp body")
        }

        val getResp = response.getResp
        val params = mutableMapOf<String, String>()

        for (reqResult in getResp.reqPathResultsList) {
            // Check for per-path errors
            if (reqResult.errCode != 0) {
                return ParsedResponse.UspErrorResponse(
                    code = reqResult.errCode,
                    message = reqResult.errMsg.ifEmpty { "Error for path: ${reqResult.requestedPath}" }
                )
            }

            for (resolved in reqResult.resolvedPathResultsList) {
                val basePath = resolved.resolvedPath
                for ((key, value) in resolved.resultParamsMap) {
                    // Reconstruct full path: resolvedPath already ends with "."
                    // and key is the parameter name
                    val fullPath = if (basePath.endsWith(".")) {
                        "$basePath$key"
                    } else {
                        "$basePath.$key"
                    }
                    params[fullPath] = value
                }
            }
        }

        return ParsedResponse.GetResponse(params)
    }

    /**
     * Parses a SetResp message, extracting the affected paths from successful updates.
     *
     * The SetResp structure is:
     * - updatedObjResults[] (one per UpdateObject in the request)
     *   - operStatus: OperationSuccess or OperationFailure
     *     - OperationSuccess.updatedInstResults[].affectedPath
     */
    private fun parseSetResp(msg: Usp.Msg): ParsedResponse {
        val response = msg.body.response
        if (!response.hasSetResp()) {
            return ParsedResponse.UspErrorResponse(-1, "SET_RESP header but no set_resp body")
        }

        val setResp = response.setResp
        val updatedPaths = mutableListOf<String>()

        for (objResult in setResp.updatedObjResultsList) {
            val operStatus = objResult.operStatus

            when (operStatus.operStatusCase) {
                Usp.SetResp.UpdatedObjectResult.OperationStatus.OperStatusCase.OPER_FAILURE -> {
                    val failure = operStatus.operFailure
                    return ParsedResponse.UspErrorResponse(
                        code = failure.errCode,
                        message = failure.errMsg.ifEmpty { "Set operation failed for: ${objResult.requestedPath}" }
                    )
                }
                Usp.SetResp.UpdatedObjectResult.OperationStatus.OperStatusCase.OPER_SUCCESS -> {
                    val success = operStatus.operSuccess
                    for (instResult in success.updatedInstResultsList) {
                        updatedPaths.add(instResult.affectedPath)
                    }
                }
                else -> {
                    // OPERSTATUS_NOT_SET - treat as success with the requested path
                    updatedPaths.add(objResult.requestedPath)
                }
            }
        }

        return ParsedResponse.SetResponse(updatedPaths)
    }
}
