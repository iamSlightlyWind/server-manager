package dev.themajorones.servermanager.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {
    private val masterKey =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private val prefs =
        EncryptedSharedPreferences.create(
            context,
            "credentials.secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    fun savePassword(machineId: Long, password: String) {
        prefs.edit().putString(passwordKey(machineId), password).apply()
    }

    fun savePrivateKey(machineId: Long, keyText: String) {
        prefs.edit().putString(privateKeyKey(machineId), keyText).apply()
    }

    fun getPassword(machineId: Long): String? = prefs.getString(passwordKey(machineId), null)

    fun getPrivateKey(machineId: Long): String? = prefs.getString(privateKeyKey(machineId), null)

    fun clearCredentials(machineId: Long) {
        prefs.edit().remove(passwordKey(machineId)).remove(privateKeyKey(machineId)).apply()
    }

    private fun passwordKey(machineId: Long): String = "machine.$machineId.password"

    private fun privateKeyKey(machineId: Long): String = "machine.$machineId.privateKey"
}
