package com.uspcontroller.app.data.protobuf

import usp_msg.Usp
import java.util.UUID

/**
 * Builds USP (TR-369) request messages for Get and Set operations.
 *
 * Constructs valid `Usp.Msg` Protocol Buffer messages per the BBF specification,
 * wrapping them with the appropriate Header and Body structure.
 */
object UspMessageBuilder {

    /**
     * Builds a USP Get request message for one or more parameter paths.
     *
     * @param paths List of TR-181 parameter paths to retrieve
     *              (e.g., "Device.DeviceInfo.ProcessStatus.CPUUsage").
     * @param msgId Unique message identifier for request-response correlation.
     *              Defaults to a new UUID v4.
     * @return A fully constructed [Usp.Msg] ready to be wrapped in a USP Record.
     */
    fun buildGetRequest(
        paths: List<String>,
        msgId: String = UUID.randomUUID().toString()
    ): Usp.Msg {
        val getMsg = Usp.Get.newBuilder().apply {
            paths.forEach { path ->
                addParamPaths(path)
            }
        }.build()

        return Usp.Msg.newBuilder()
            .setHeader(
                Usp.Header.newBuilder()
                    .setMsgId(msgId)
                    .setMsgType(Usp.Header.MsgType.GET)
                    .build()
            )
            .setBody(
                Usp.Body.newBuilder()
                    .setRequest(
                        Usp.Request.newBuilder()
                            .setGet(getMsg)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /**
     * Builds a USP Set request message to update one or more parameters.
     *
     * Each entry in [params] is split into an object path and parameter name.
     * For example, "Device.WiFi.AccessPoint.1.Security.KeyPassphrase" becomes:
     * - objPath: "Device.WiFi.AccessPoint.1.Security."
     * - param: "KeyPassphrase"
     *
     * @param params Map of full parameter paths to their new values.
     * @param msgId Unique message identifier for request-response correlation.
     *              Defaults to a new UUID v4.
     * @return A fully constructed [Usp.Msg] ready to be wrapped in a USP Record.
     */
    fun buildSetRequest(
        params: Map<String, String>,
        msgId: String = UUID.randomUUID().toString()
    ): Usp.Msg {
        val updateObjs = params.map { (fullPath, value) ->
            val lastDotIndex = fullPath.lastIndexOf('.')
            val objPath = if (lastDotIndex >= 0) {
                fullPath.substring(0, lastDotIndex + 1)
            } else {
                "$fullPath."
            }
            val paramName = if (lastDotIndex >= 0) {
                fullPath.substring(lastDotIndex + 1)
            } else {
                fullPath
            }

            Usp.Set.UpdateObject.newBuilder()
                .setObjPath(objPath)
                .addParamSettings(
                    Usp.Set.UpdateParamSetting.newBuilder()
                        .setParam(paramName)
                        .setValue(value)
                        .setRequired(true)
                        .build()
                )
                .build()
        }

        val setMsg = Usp.Set.newBuilder()
            .setAllowPartial(false)
            .apply {
                updateObjs.forEach { addUpdateObjs(it) }
            }
            .build()

        return Usp.Msg.newBuilder()
            .setHeader(
                Usp.Header.newBuilder()
                    .setMsgId(msgId)
                    .setMsgType(Usp.Header.MsgType.SET)
                    .build()
            )
            .setBody(
                Usp.Body.newBuilder()
                    .setRequest(
                        Usp.Request.newBuilder()
                            .setSet(setMsg)
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
