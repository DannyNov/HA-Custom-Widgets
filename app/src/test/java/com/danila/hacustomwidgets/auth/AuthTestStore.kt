package com.danila.hacustomwidgets.auth

import com.danila.hacustomwidgets.data.security.*

internal class AuthTestStore(var value: HomeAssistantConnection? = null) : AuthorizationStore {
    val secrets = mutableMapOf<String, String>()
    override fun load() = value
    override fun replace(connection: HomeAssistantConnection) { value = connection }
    override fun clear() { value = null; secrets.clear() }
    override fun updateMetadata(connection: HomeAssistantConnection, metadata: ServerMetadata) {
        if (value == connection) value = value!!.copy(server = metadata)
    }
    override fun readSecret(name: String) = secrets[name]
    override fun writeSecret(name: String, value: String, removeLegacy: Boolean) { secrets[name] = value }
    override fun removeSecret(name: String) { secrets.remove(name) }
}

internal fun oauthConnection(base: String = "https://ha.example.com") = HomeAssistantConnection(
    base, "access-secret", "refresh-secret", 1_000_000, OAuthPolicy.CLIENT_ID, "session-one",
)
