package com.danila.hacustomwidgets.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureConnectionStore(context: Context, preferencesName: String = "ha_connection") : AuthorizationStore {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    fun hasUnreadableCredentials(): Boolean = synchronized(sessionLock) {
        (prefs.contains("oauth") || prefs.contains(KEY_TOKEN)) && load() == null
    }

    fun save(baseUrl: String, token: String) = synchronized(sessionLock) {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        require(normalizedUrl.startsWith("https://") || normalizedUrl.startsWith("http://"))
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        val editor = prefs.edit()
            .remove("oauth").remove("oauth_iv")
            .remove("pending_oauth").remove("pending_oauth_iv")
            .putString(KEY_URL, normalizedUrl)
            .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        if (prefs.getString(KEY_URL, null) != normalizedUrl) editor.remove("server_metadata").remove("server_metadata_iv")
        editor.commit().also { check(it) { "Cannot save connection" } }
    }

    override fun load(): HomeAssistantConnection? = synchronized(sessionLock) {
        if (prefs.contains("oauth")) return@synchronized runCatching {
            val json = org.json.JSONObject(readSecret("oauth")!!)
            HomeAssistantConnection(json.getString("url"), json.getString("access"),
                json.getString("refresh"), json.getLong("expires"), json.getString("client"), json.getString("session"),
                ServerMetadata.fromJson(json.optJSONObject("server")))
        }.getOrNull()
        val url = prefs.getString(KEY_URL, null) ?: return null
        val encrypted = prefs.getString(KEY_TOKEN, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
                )
            }
            HomeAssistantConnection(
                baseUrl = url,
                token = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
                    .toString(Charsets.UTF_8),
                server = runCatching { ServerMetadata.fromJson(readSecret("server_metadata")?.let { org.json.JSONObject(it) }) }
                    .getOrDefault(ServerMetadata()),
            )
        }.getOrNull()
    }

    override fun replace(connection: HomeAssistantConnection) = synchronized(sessionLock) {
        require(connection.isOAuth)
        val json = org.json.JSONObject().put("url", connection.baseUrl).put("access", connection.token)
            .put("refresh", connection.refreshToken).put("expires", connection.expiresAt)
            .put("client", connection.clientId).put("session", connection.sessionId)
            .put("server", connection.server.toJson())
        writeSecret("oauth", json.toString(), removeLegacy = true)
    }

    override fun updateMetadata(connection: HomeAssistantConnection, metadata: ServerMetadata) = synchronized(sessionLock) {
        val current = load()
        if (current == connection) {
            if (current!!.isOAuth) replace(current.copy(server = metadata))
            else writeSecret("server_metadata", metadata.toJson().toString())
        }
    }

    override fun clear() { synchronized(sessionLock) {
        check(prefs.edit().clear().commit()) { "Cannot clear connection" }
    } }

    override fun readSecret(name: String): String? {
        val encrypted = prefs.getString(name, null) ?: return null
        val iv = prefs.getString(name + "_iv", null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    override fun writeSecret(name: String, value: String, removeLegacy: Boolean) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val editor = prefs.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(name + "_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        if (removeLegacy) editor.remove(KEY_URL).remove(KEY_TOKEN).remove(KEY_IV)
        check(editor.commit()) { "Cannot save credentials" }
    }

    override fun removeSecret(name: String) {
        check(prefs.edit().remove(name).remove(name + "_iv").commit()) { "Cannot clear authorization" }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ha_widget_access_token_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_IV = "token_iv"
    }
}
