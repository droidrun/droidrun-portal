package com.mobilerun.portal.service

import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentProviderAccessPolicyTest {

    @Test
    fun allowsAppShellAndRootUids() {
        assertTrue(ContentProviderAccessPolicy.isUidAllowed(callingUid = 12345, appUid = 12345))
        assertTrue(
            ContentProviderAccessPolicy.isUidAllowed(
                callingUid = Process.SHELL_UID,
                appUid = 12345,
            ),
        )
        assertTrue(ContentProviderAccessPolicy.isUidAllowed(callingUid = 0, appUid = 12345))
    }

    @Test
    fun rejectsUntrustedThirdPartyUid() {
        assertFalse(ContentProviderAccessPolicy.isUidAllowed(callingUid = 23456, appUid = 12345))
    }
}
