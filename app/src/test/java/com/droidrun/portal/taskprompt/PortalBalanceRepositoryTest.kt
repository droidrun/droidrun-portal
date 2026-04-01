package com.droidrun.portal.taskprompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PortalBalanceRepositoryTest {

    @Before
    fun setUp() {
        PortalBalanceRepository.resetForTest()
    }

    @Test
    fun loadBalance_reusesCachedStateWithoutSecondFetch() {
        val fingerprint = fingerprint()
        var loadCount = 0
        var pendingCallback: ((PortalBalanceResult) -> Unit)? = null
        val states = mutableListOf<PortalBalanceCacheState>()

        PortalBalanceRepository.observeFingerprint(fingerprint)
        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = CLOUD_BASE_URL,
            authToken = AUTH_TOKEN,
            loader = { _, _, callback ->
                loadCount += 1
                pendingCallback = callback
            },
        ) { states.add(it) }

        assertEquals(1, loadCount)
        assertTrue(states.first().isLoading)

        pendingCallback?.invoke(
            PortalBalanceResult.Success(
                PortalBalanceInfo(balance = 440, usage = 60, nextReset = null),
            ),
        )

        val cachedStates = mutableListOf<PortalBalanceCacheState>()
        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = CLOUD_BASE_URL,
            authToken = AUTH_TOKEN,
            loader = { _, _, _ -> loadCount += 1 },
        ) { cachedStates.add(it) }

        assertEquals(1, loadCount)
        assertEquals(1, cachedStates.size)
        assertFalse(cachedStates.single().isLoading)
        assertTrue(cachedStates.single().hasLoaded)
        assertEquals(440, cachedStates.single().info?.balance)
    }

    @Test
    fun loadBalance_forceRefreshBypassesCache() {
        val fingerprint = fingerprint()
        primeCache(fingerprint, PortalBalanceInfo(balance = 440, usage = 60, nextReset = null))

        var loadCount = 0
        var pendingCallback: ((PortalBalanceResult) -> Unit)? = null
        val states = mutableListOf<PortalBalanceCacheState>()

        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = CLOUD_BASE_URL,
            authToken = AUTH_TOKEN,
            force = true,
            loader = { _, _, callback ->
                loadCount += 1
                pendingCallback = callback
            },
        ) { states.add(it) }

        assertEquals(1, loadCount)
        assertTrue(states.first().isLoading)
        assertEquals(440, states.first().info?.balance)

        pendingCallback?.invoke(
            PortalBalanceResult.Success(
                PortalBalanceInfo(balance = 809, usage = 191, nextReset = null),
            ),
        )

        val snapshot = PortalBalanceRepository.snapshot(fingerprint)
        assertEquals(809, snapshot.info?.balance)
    }

    @Test
    fun loadBalance_retryableErrorDoesNotBecomeTerminal() {
        val fingerprint = fingerprint()
        primeCache(fingerprint, PortalBalanceInfo(balance = 440, usage = 60, nextReset = null))

        var loadCount = 0
        var pendingCallback: ((PortalBalanceResult) -> Unit)? = null
        val states = mutableListOf<PortalBalanceCacheState>()

        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = CLOUD_BASE_URL,
            authToken = AUTH_TOKEN,
            force = true,
            loader = { _, _, callback ->
                loadCount += 1
                pendingCallback = callback
            },
        ) { states.add(it) }

        assertEquals(1, loadCount)
        assertTrue(states.first().isLoading)
        assertEquals(440, states.first().info?.balance)

        pendingCallback?.invoke(
            PortalBalanceResult.Error(
                message = "Temporary outage",
                retryable = true,
            ),
        )

        val errorSnapshot = PortalBalanceRepository.snapshot(fingerprint)
        assertFalse(errorSnapshot.isLoading)
        assertFalse(errorSnapshot.hasLoaded)
        assertEquals(440, errorSnapshot.info?.balance)
        assertEquals("Temporary outage", errorSnapshot.message)

        val retryStates = mutableListOf<PortalBalanceCacheState>()
        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = CLOUD_BASE_URL,
            authToken = AUTH_TOKEN,
            loader = { _, _, callback ->
                loadCount += 1
                callback(PortalBalanceResult.Success(PortalBalanceInfo(balance = 441, usage = 61, nextReset = null)))
            },
        ) { retryStates.add(it) }

        assertEquals(2, loadCount)
        assertTrue(retryStates.first().isLoading)
        assertEquals(440, retryStates.first().info?.balance)
        assertEquals(441, PortalBalanceRepository.snapshot(fingerprint).info?.balance)
    }

    @Test
    fun loadBalance_nonRetryableErrorRemainsCached() {
        val fingerprint = fingerprint()
        var loadCount = 0
        var pendingCallback: ((PortalBalanceResult) -> Unit)? = null

        PortalBalanceRepository.observeFingerprint(fingerprint)
        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = CLOUD_BASE_URL,
            authToken = AUTH_TOKEN,
            loader = { _, _, callback ->
                loadCount += 1
                pendingCallback = callback
            },
        ) {}

        pendingCallback?.invoke(
            PortalBalanceResult.Error(
                message = "Rejected token",
                retryable = false,
            ),
        )

        val snapshot = PortalBalanceRepository.snapshot(fingerprint)
        assertTrue(snapshot.hasLoaded)
        assertFalse(snapshot.isLoading)
        assertEquals("Rejected token", snapshot.message)

        val cachedStates = mutableListOf<PortalBalanceCacheState>()
        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = CLOUD_BASE_URL,
            authToken = AUTH_TOKEN,
            loader = { _, _, _ -> loadCount += 1 },
        ) { cachedStates.add(it) }

        assertEquals(1, loadCount)
        assertEquals(1, cachedStates.size)
        assertTrue(cachedStates.single().hasLoaded)
        assertEquals("Rejected token", cachedStates.single().message)
    }

    @Test
    fun loadBalance_unavailableRemainsCached() {
        val fingerprint = fingerprint()
        var loadCount = 0
        var pendingCallback: ((PortalBalanceResult) -> Unit)? = null

        PortalBalanceRepository.observeFingerprint(fingerprint)
        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = CLOUD_BASE_URL,
            authToken = AUTH_TOKEN,
            loader = { _, _, callback ->
                loadCount += 1
                pendingCallback = callback
            },
        ) {}

        pendingCallback?.invoke(
            PortalBalanceResult.Unavailable("Credits unavailable"),
        )

        val snapshot = PortalBalanceRepository.snapshot(fingerprint)
        assertTrue(snapshot.hasLoaded)
        assertFalse(snapshot.isLoading)
        assertEquals("Credits unavailable", snapshot.message)

        val cachedStates = mutableListOf<PortalBalanceCacheState>()
        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = CLOUD_BASE_URL,
            authToken = AUTH_TOKEN,
            loader = { _, _, _ -> loadCount += 1 },
        ) { cachedStates.add(it) }

        assertEquals(1, loadCount)
        assertEquals(1, cachedStates.size)
        assertTrue(cachedStates.single().hasLoaded)
        assertEquals("Credits unavailable", cachedStates.single().message)
    }

    @Test
    fun observeFingerprint_clearsCachedEntryWhenFingerprintChanges() {
        val firstFingerprint = fingerprint("token-one")
        val secondFingerprint = fingerprint("token-two")

        primeCache(firstFingerprint, PortalBalanceInfo(balance = 440, usage = 60, nextReset = null))
        assertNotNull(PortalBalanceRepository.snapshot(firstFingerprint).info)

        PortalBalanceRepository.observeFingerprint(secondFingerprint)

        val clearedState = PortalBalanceRepository.snapshot(firstFingerprint)
        assertFalse(clearedState.hasLoaded)
        assertEquals(null, clearedState.info)
    }

    private fun primeCache(fingerprint: String, info: PortalBalanceInfo) {
        PortalBalanceRepository.observeFingerprint(fingerprint)
        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = CLOUD_BASE_URL,
            authToken = AUTH_TOKEN,
            loader = { _, _, callback ->
                callback(PortalBalanceResult.Success(info))
            },
        ) {}
    }

    private fun fingerprint(authToken: String = AUTH_TOKEN): String {
        return PortalBalanceRepository.buildFingerprint(CLOUD_BASE_URL, authToken)
    }

    companion object {
        private const val CLOUD_BASE_URL = "https://cloud.mobilerun.ai"
        private const val AUTH_TOKEN = "token-abc"
    }
}
