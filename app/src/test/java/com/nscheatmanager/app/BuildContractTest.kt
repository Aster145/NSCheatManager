package com.nscheatmanager.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildContractTest {
    @Test
    fun productIdentityIsStable() {
        assertEquals("com.nscheatmanager.app", BuildConfig.APPLICATION_ID)
        assertEquals("1.0.0", BuildConfig.VERSION_NAME)
    }
}
