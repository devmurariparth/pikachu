package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureSecretStoreInstrumentedTest {
    @Test
    fun secret_round_trip_is_encrypted_at_rest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SecureSecretStore(context)
        val alias = "phase1-test-secret"

        store.put(alias, "test-secret-value")
        assertEquals("test-secret-value", store.get(alias))

        val prefs = context.getSharedPreferences("mj_secure_secrets", Context.MODE_PRIVATE)
        assertNotEquals("test-secret-value", prefs.getString(alias, null))

        store.remove(alias)
        assertEquals("", store.get(alias))
    }
}
