package com.mobilerun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ServiceInstanceCacheTest {
    @Test
    fun get_reusesCachedValueForSameServiceInstance() {
        val cache = ServiceInstanceCache<Any, String>()
        val service = Any()
        var buildCount = 0

        val first = cache.get(service) { "value-${++buildCount}" }
        val second = cache.get(service) { "value-${++buildCount}" }

        assertEquals("value-1", first)
        assertSame(first, second)
        assertEquals(1, buildCount)
    }

    @Test
    fun get_rebuildsCachedValueForDifferentServiceInstance() {
        val cache = ServiceInstanceCache<Any, String>()
        var buildCount = 0

        val first = cache.get(Any()) { "value-${++buildCount}" }
        val second = cache.get(Any()) { "value-${++buildCount}" }

        assertEquals("value-1", first)
        assertEquals("value-2", second)
        assertEquals(2, buildCount)
    }

    @Test
    fun get_clearsCacheWhenCurrentServiceIsNull() {
        val cache = ServiceInstanceCache<Any, String>()
        val service = Any()
        var buildCount = 0

        cache.get(service) { "value-${++buildCount}" }
        val nullResult = cache.get(null) { "unused" }
        val rebuilt = cache.get(service) { "value-${++buildCount}" }

        assertNull(nullResult)
        assertEquals("value-2", rebuilt)
        assertEquals(2, buildCount)
    }
}
