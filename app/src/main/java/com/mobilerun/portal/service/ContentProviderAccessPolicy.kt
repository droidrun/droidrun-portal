package com.mobilerun.portal.service

import android.os.Process

internal object ContentProviderAccessPolicy {
    private const val ROOT_UID = 0

    fun isUidAllowed(callingUid: Int, appUid: Int): Boolean {
        return callingUid == appUid || callingUid == Process.SHELL_UID || callingUid == ROOT_UID
    }
}
