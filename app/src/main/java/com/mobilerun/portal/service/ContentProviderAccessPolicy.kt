package com.mobilerun.portal.service

internal object ContentProviderAccessPolicy {
    private const val ROOT_UID = 0
    // `adb shell content ...` calls come from the shell user on all supported versions.
    private const val SHELL_UID = 2000

    fun isUidAllowed(callingUid: Int, appUid: Int): Boolean {
        return callingUid == appUid || callingUid == SHELL_UID || callingUid == ROOT_UID
    }
}
