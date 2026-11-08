package com.ztec.cplay

import android.content.Context
import android.provider.Settings

internal object NexusCpBluetooth {
    fun localAddress(context: Context): String? {
        // BluetoothAdapter.address requires the privileged LOCAL_MAC_ADDRESS permission,
        // which is not grantable to ordinary applications. Prefer the vendor-exposed setting.
        val setting = runCatching {
            Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        return listOfNotNull(setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }
}
