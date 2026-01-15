package com.droidrun.portal.state

object AppVisibilityTracker {
    @Volatile
    private var isForeground: Boolean = false

    fun setForeground(foreground: Boolean) {
        isForeground = foreground
    }

    fun isInForeground(): Boolean {
        return isForeground
    }
}
