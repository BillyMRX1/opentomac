package dev.opentomac.android.platform

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.opentomac.shared.pairing.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidKeyValueStore(context: Context) : KeyValueStore {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override suspend fun put(key: String, value: ByteArray) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString(key, Base64.encodeToString(value, Base64.NO_WRAP)).commit()) {
            "Could not persist encrypted value for '$key'"
        }
    }

    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        preferences.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().remove(key).commit()) { "Could not remove encrypted value for '$key'" }
    }

    override suspend fun keys(): List<String> = withContext(Dispatchers.IO) {
        preferences.all.keys.sorted()
    }

    private companion object {
        const val FILE_NAME = "opentomac_secure_store"
    }
}
