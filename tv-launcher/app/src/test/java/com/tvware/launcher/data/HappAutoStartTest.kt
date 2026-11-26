package com.tvware.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HappAutoStartTest {
    @Test
    fun selectHappConnectUriPrefersSilentConnectThenUiConnect() {
        assertEquals(
            "happ://connect_without_ui",
            selectHappConnectUri(canConnectWithoutUi = true, canConnectWithUi = true),
        )
        assertEquals(
            "happ://connect",
            selectHappConnectUri(canConnectWithoutUi = false, canConnectWithUi = true),
        )
        assertNull(selectHappConnectUri(canConnectWithoutUi = false, canConnectWithUi = false))
    }

    @Test
    fun shouldAutoStartHappRequiresEnabledFreshAttemptInactiveVpnAndInstalledApp() {
        assertTrue(
            shouldAutoStartHapp(
                enabled = true,
                alreadyAttempted = false,
                isVpnActive = false,
                isHappInstalled = true,
            ),
        )
        assertFalse(
            shouldAutoStartHapp(
                enabled = false,
                alreadyAttempted = false,
                isVpnActive = false,
                isHappInstalled = true,
            ),
        )
        assertFalse(
            shouldAutoStartHapp(
                enabled = true,
                alreadyAttempted = true,
                isVpnActive = false,
                isHappInstalled = true,
            ),
        )
        assertFalse(
            shouldAutoStartHapp(
                enabled = true,
                alreadyAttempted = false,
                isVpnActive = true,
                isHappInstalled = true,
            ),
        )
        assertFalse(
            shouldAutoStartHapp(
                enabled = true,
                alreadyAttempted = false,
                isVpnActive = false,
                isHappInstalled = false,
            ),
        )
    }
}
