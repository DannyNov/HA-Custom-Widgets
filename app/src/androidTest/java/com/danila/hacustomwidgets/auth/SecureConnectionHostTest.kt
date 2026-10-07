package com.danila.hacustomwidgets.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.data.security.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecureConnectionHostTest {
    private fun isolated(): Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        private val prefix = "oauth-test-${UUID.randomUUID()}-"
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    }
    @Test fun legacyFormatLoadsAndOAuthMigrationEncryptsAllSecretsAndKeepsDashboardBytes() {
        val context = isolated(); val store = SecureConnectionStore(context)
        val dashboards = context.getSharedPreferences("dashboard_widgets", Context.MODE_PRIVATE)
        val config = """{"tabs":["home"],"grouping":"area","cardOrder":["light.a"],"timers":{"timer.a":30},"theme":"dark"}"""
        dashboards.edit().putString("42:config", config).commit()
        store.save("http://ha.local:8123", "legacy-secret")
        assertEquals("legacy-secret", SecureConnectionStore(context).load()!!.token)
        val migrated = HomeAssistantConnection("http://ha.local:8123", "access-secret", "refresh-secret", 1_800_000,
            OAuthPolicy.CLIENT_ID, "session-one", ServerMetadata(instanceId = "instance", routes =
                listOf(ServerRoute("https://home.example.com", RouteKind.EXTERNAL)), webhookId = "webhook-secret"))
        store.replace(migrated)
        val restored = SecureConnectionStore(context).load()!!
        assertEquals(migrated.refreshToken, restored.refreshToken); assertEquals(migrated.server, restored.server)
        val raw = context.getSharedPreferences("ha_connection", Context.MODE_PRIVATE).all.toString()
        listOf("legacy-secret", "access-secret", "refresh-secret", "webhook-secret").forEach { assertFalse(raw.contains(it)) }
        assertFalse(context.getSharedPreferences("ha_connection", Context.MODE_PRIVATE).contains("access_token"))
        assertEquals(config, dashboards.getString("42:config", null))
        store.clear(); assertNull(store.load()); assertEquals(config, dashboards.getString("42:config", null))
    }
    @Test fun pendingAuthorizationSurvivesStoreRecreationEncryptedAndCancelRemovesIt() {
        val context = isolated(); val store = SecureConnectionStore(context)
        store.writeSecret("pending_oauth", "state-secret")
        assertFalse(context.getSharedPreferences("ha_connection", Context.MODE_PRIVATE).all.toString().contains("state-secret"))
        val recreated = SecureConnectionStore(context)
        assertEquals("state-secret", recreated.readSecret("pending_oauth"))
        recreated.removeSecret("pending_oauth"); assertNull(store.readSecret("pending_oauth"))
    }
}
