package com.uspcontroller.app.domain.model

/**
 * Represents Wi-Fi configuration parameters from the USP Agent.
 *
 * Maps to TR-181 parameters:
 * - [ssid]: Device.WiFi.SSID.{i}.SSID
 * - [passphrase]: Device.WiFi.AccessPoint.{i}.Security.KeyPassphrase
 */
data class WifiConfig(
    val ssid: String,
    val passphrase: String
) {
    companion object {
        /** Minimum passphrase length for WPA2-Personal. */
        const val MIN_PASSPHRASE_LENGTH = 8

        /** Maximum passphrase length for WPA2-Personal. */
        const val MAX_PASSPHRASE_LENGTH = 63
    }

    /**
     * Validates whether the passphrase meets WPA2-Personal length requirements.
     */
    fun isPassphraseValid(): Boolean =
        passphrase.length in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH
}
