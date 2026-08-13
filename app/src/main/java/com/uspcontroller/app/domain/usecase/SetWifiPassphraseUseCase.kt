package com.uspcontroller.app.domain.usecase

import com.uspcontroller.app.data.repository.UspRepository
import com.uspcontroller.app.domain.model.UspException
import com.uspcontroller.app.domain.model.WifiConfig
import javax.inject.Inject

/**
 * Use case that sends a USP Set message to update the Wi-Fi passphrase on the Agent.
 *
 * Validates the passphrase meets WPA2-Personal requirements (8-63 characters)
 * before sending the request.
 *
 * Target parameter: Device.WiFi.AccessPoint.1.Security.KeyPassphrase
 */
class SetWifiPassphraseUseCase @Inject constructor(
    private val repository: UspRepository
) {

    companion object {
        private const val PATH_KEY_PASSPHRASE =
            "Device.WiFi.AccessPoint.1.Security.KeyPassphrase"
    }

    /**
     * Validates and sends a Set request to update the Wi-Fi passphrase.
     *
     * @param agentEid The Endpoint ID of the connected USP Agent.
     * @param passphrase The new passphrase to set (must be 8-63 characters for WPA2).
     * @return [Result.success] if the agent accepted the change,
     *         [Result.failure] with [UspException] if validation fails or the agent rejects it.
     */
    suspend fun execute(agentEid: String, passphrase: String): Result<Unit> {
        // Validate passphrase length per WPA2-Personal spec
        if (passphrase.length < WifiConfig.MIN_PASSPHRASE_LENGTH) {
            return Result.failure(
                UspException(
                    code = -1,
                    uspMessage = "Passphrase too short: minimum ${WifiConfig.MIN_PASSPHRASE_LENGTH} characters required"
                )
            )
        }

        if (passphrase.length > WifiConfig.MAX_PASSPHRASE_LENGTH) {
            return Result.failure(
                UspException(
                    code = -1,
                    uspMessage = "Passphrase too long: maximum ${WifiConfig.MAX_PASSPHRASE_LENGTH} characters allowed"
                )
            )
        }

        return repository.setParameter(agentEid, PATH_KEY_PASSPHRASE, passphrase)
    }
}
