package com.mobilerun.portal.service

/**
 * Caches a value derived from a service instance while invalidating it when
 * Android replaces that service with a new object.
 */
internal class ServiceInstanceCache<S : Any, V : Any> {
    private var cachedService: S? = null
    private var cachedValue: V? = null

    @Synchronized
    fun get(currentService: S?, create: (S) -> V): V? {
        if (currentService == null) {
            clear()
            return null
        }

        val value = cachedValue
        if (value != null && cachedService === currentService) {
            return value
        }

        return create(currentService).also {
            cachedService = currentService
            cachedValue = it
        }
    }

    @Synchronized
    fun clear() {
        cachedService = null
        cachedValue = null
    }
}
