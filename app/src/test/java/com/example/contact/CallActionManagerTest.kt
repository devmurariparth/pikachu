package com.example.contact

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallActionManagerTest {

    private lateinit var context: Context
    private lateinit var shadowApp: ShadowApplication

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowApp = shadowOf(context as Application)
    }

    @Test
    fun testParseCallCommandVariations() {
        assertEquals("rahul", CallActionManager.parseCallCommand("Call Rahul"))
        assertEquals("mom", CallActionManager.parseCallCommand("Call Mom"))
        assertEquals("mom", CallActionManager.parseCallCommand("Call my Mom"))
        assertEquals("mom", CallActionManager.parseCallCommand("Phone Mom"))
        assertEquals("rahul", CallActionManager.parseCallCommand("Phone Rahul"))
        assertEquals("rahul", CallActionManager.parseCallCommand("Please call Rahul"))
        assertEquals("rahul", CallActionManager.parseCallCommand("Can you please call Rahul?"))
        assertEquals("rahul", CallActionManager.parseCallCommand("Hey MJ, call Rahul"))
        assertEquals("rahul", CallActionManager.parseCallCommand("Make a call to Rahul"))
        assertEquals("rahul", CallActionManager.parseCallCommand("Place a call to Rahul"))
        assertEquals("rahul", CallActionManager.parseCallCommand("Dial Rahul"))
        assertEquals("9876543210", CallActionManager.parseCallCommand("Dial 9876543210"))
        assertEquals("rahul", CallActionManager.parseCallCommand("call my friend Rahul"))

        // Empty target
        assertEquals("", CallActionManager.parseCallCommand("call"))
        assertEquals("", CallActionManager.parseCallCommand("phone"))

        // Non-call queries
        assertNull(CallActionManager.parseCallCommand("What is the time?"))
        assertNull(CallActionManager.parseCallCommand("Send a message to Rahul"))
        assertNull(CallActionManager.parseCallCommand("Open Chrome"))
    }

    @Test
    fun testExecuteCallWithPermissionGranted() {
        shadowApp.grantPermissions(Manifest.permission.CALL_PHONE)

        val result = CallActionManager.executeCall(context, "Rahul", "+15551234567")
        assertTrue(result is CallExecutionResult.Started)

        val nextStartedIntent = shadowApp.nextStartedActivity
        assertNotNull(nextStartedIntent)
        assertEquals(Intent.ACTION_CALL, nextStartedIntent.action)
        assertEquals(Uri.parse("tel:%2B15551234567"), nextStartedIntent.data)
        assertTrue((nextStartedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun testExecuteCallWithoutPermissionRequiresPermission() {
        shadowApp.denyPermissions(Manifest.permission.CALL_PHONE)

        val result = CallActionManager.executeCall(context, "Rahul", "+15551234567")
        assertTrue(result is CallExecutionResult.PermissionRequired)
        val permResult = result as CallExecutionResult.PermissionRequired
        assertEquals("Rahul", permResult.contactName)
    }

    @Test
    fun testOpenDialerFallback() {
        val success = CallActionManager.openDialerFallback(context, "+15559876543")
        assertTrue(success)

        val nextStartedIntent = shadowApp.nextStartedActivity
        assertNotNull(nextStartedIntent)
        assertEquals(Intent.ACTION_DIAL, nextStartedIntent.action)
        assertEquals(Uri.parse("tel:%2B15559876543"), nextStartedIntent.data)
    }
}
