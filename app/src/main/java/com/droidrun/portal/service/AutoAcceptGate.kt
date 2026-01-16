package com.droidrun.portal.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounds auto-accept behavior to the short window after a portal-initiated action.
 */
object AutoAcceptGate {
    private const val INSTALL_TTL_MS = 90_000L
    private const val MEDIA_PROJECTION_TTL_MS = 60_000L

    private val installAllowedUntilMs = AtomicLong(0L)
    private val mediaProjectionAllowedUntilMs = AtomicLong(0L)
    private val installTreeDumped = AtomicBoolean(false)

    fun armInstall(ttlMs: Long = INSTALL_TTL_MS) {
        installAllowedUntilMs.set(System.currentTimeMillis() + ttlMs)
        installTreeDumped.set(false)
    }

    fun disarmInstall() {
        installAllowedUntilMs.set(0L)
    }

    fun isInstallArmed(now: Long = System.currentTimeMillis()): Boolean {
        val until = installAllowedUntilMs.get()
        if (until == 0L) return false
        if (now <= until) return true
        installAllowedUntilMs.compareAndSet(until, 0L)
        return false
    }

    fun shouldDumpInstallTree(): Boolean {
        return installTreeDumped.compareAndSet(false, true)
    }

    fun armMediaProjection(ttlMs: Long = MEDIA_PROJECTION_TTL_MS) {
        mediaProjectionAllowedUntilMs.set(System.currentTimeMillis() + ttlMs)
    }

    fun disarmMediaProjection() {
        mediaProjectionAllowedUntilMs.set(0L)
    }

    fun isMediaProjectionArmed(now: Long = System.currentTimeMillis()): Boolean {
        val until = mediaProjectionAllowedUntilMs.get()
        if (until == 0L) return false
        if (now <= until) return true
        mediaProjectionAllowedUntilMs.compareAndSet(until, 0L)
        return false
    }
}
