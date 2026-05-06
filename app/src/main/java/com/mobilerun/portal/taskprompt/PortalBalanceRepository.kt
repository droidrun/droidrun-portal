package com.mobilerun.portal.taskprompt

typealias PortalBalanceLoader = (String, String, (PortalBalanceResult) -> Unit) -> Unit

data class PortalBalanceCacheState(
    val info: PortalBalanceInfo? = null,
    val message: String? = null,
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val loadedAtMs: Long = 0L,
) {
    companion object {
        val EMPTY = PortalBalanceCacheState()
    }
}

object PortalBalanceRepository {
    private data class Entry(
        var state: PortalBalanceCacheState = PortalBalanceCacheState.EMPTY,
        val pendingCallbacks: MutableList<(PortalBalanceCacheState) -> Unit> = mutableListOf(),
    )

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private var activeFingerprint: String? = null

    fun buildFingerprint(cloudBaseUrl: String, authToken: String): String {
        return "${cloudBaseUrl.trim()}|${authToken.trim()}"
    }

    fun observeFingerprint(fingerprint: String?) {
        synchronized(lock) {
            if (activeFingerprint == fingerprint) {
                return
            }
            if (activeFingerprint != null && activeFingerprint != fingerprint) {
                entries.clear()
            }
            activeFingerprint = fingerprint
        }
    }

    fun snapshot(fingerprint: String?): PortalBalanceCacheState {
        if (fingerprint == null) {
            return PortalBalanceCacheState.EMPTY
        }
        return synchronized(lock) {
            entries[fingerprint]?.state ?: PortalBalanceCacheState.EMPTY
        }
    }

    fun loadBalance(
        fingerprint: String,
        cloudBaseUrl: String,
        authToken: String,
        force: Boolean = false,
        loader: PortalBalanceLoader,
        callback: (PortalBalanceCacheState) -> Unit,
    ) {
        val immediateState: PortalBalanceCacheState
        var shouldStartLoad = false

        synchronized(lock) {
            val entry = entries.getOrPut(fingerprint) { Entry() }
            when {
                entry.state.hasLoaded && !force -> {
                    immediateState = entry.state
                }

                entry.state.isLoading -> {
                    entry.pendingCallbacks.add(callback)
                    immediateState = entry.state
                }

                else -> {
                    entry.pendingCallbacks.add(callback)
                    entry.state = entry.state.copy(isLoading = true)
                    immediateState = entry.state
                    shouldStartLoad = true
                }
            }
        }

        callback(immediateState)
        if (!shouldStartLoad) {
            return
        }

        loader(cloudBaseUrl, authToken) { result ->
            val resolvedState: PortalBalanceCacheState
            val callbacks: List<(PortalBalanceCacheState) -> Unit>

            synchronized(lock) {
                val entry = entries.getOrPut(fingerprint) { Entry() }
                val previousState = entry.state
                val loadedAtMs = System.currentTimeMillis()
                resolvedState = when (result) {
                    is PortalBalanceResult.Success -> {
                        PortalBalanceCacheState(
                            info = result.value,
                            message = null,
                            isLoading = false,
                            hasLoaded = true,
                            loadedAtMs = loadedAtMs,
                        )
                    }

                    is PortalBalanceResult.Error -> {
                        previousState.copy(
                            message = result.message,
                            isLoading = false,
                            hasLoaded = !result.retryable,
                            loadedAtMs = loadedAtMs,
                        )
                    }

                    is PortalBalanceResult.Unavailable -> {
                        previousState.copy(
                            info = null,
                            message = result.message,
                            isLoading = false,
                            hasLoaded = true,
                            loadedAtMs = loadedAtMs,
                        )
                    }
                }
                entry.state = resolvedState
                callbacks = entry.pendingCallbacks.toList()
                entry.pendingCallbacks.clear()
            }

            callbacks.forEach { it(resolvedState) }
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            entries.clear()
            activeFingerprint = null
        }
    }
}
