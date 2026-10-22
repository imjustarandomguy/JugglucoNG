package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutTokenCacheTests {
    private val expiresAt = 1_708_882_956_000L
    private fun grant(token: String = "t1") = NightscoutTokenGrant(token, expiresAt, emptyList())

    @Test
    fun theStoredTokenIsSentUntilAMinuteBeforeItsExpiry() {
        val cache = NightscoutTokenCache()
        assertEquals("Bearer t1", cache.store(grant()))
        assertEquals("Bearer t1", cache.current(expiresAt - 8 * 3_600_000L))
        assertEquals("Bearer t1", cache.current(expiresAt - NightscoutTokenCache.EXPIRY_MARGIN_MILLIS - 1))
        // The expiry is the server's clock: the last minute is left to a new exchange.
        assertEquals("", cache.current(expiresAt - NightscoutTokenCache.EXPIRY_MARGIN_MILLIS))
        assertEquals("", cache.current(expiresAt))
    }

    @Test
    fun nothingIsSentBeforeTheFirstExchange() {
        assertEquals("", NightscoutTokenCache().current(0L))
    }

    @Test
    fun aRefusedTokenIsDroppedLongBeforeItsExpiry() {
        val cache = NightscoutTokenCache()
        val sent = cache.store(grant())
        assertTrue(cache.refused(sent))
        assertEquals("", cache.current(expiresAt - 7 * 3_600_000L))
    }

    @Test
    fun aRefusalOfAnOlderTokenKeepsTheOneExchangedSince() {
        val cache = NightscoutTokenCache()
        val old = cache.store(grant("old"))
        cache.store(grant("new"))
        assertFalse(cache.refused(old))
        assertEquals("Bearer new", cache.current(expiresAt - 3_600_000L))
    }

    @Test
    fun aRequestThatCarriedNoTokenDropsNothing() {
        val cache = NightscoutTokenCache()
        cache.store(grant())
        assertFalse(cache.refused(null))
        assertFalse(cache.refused(""))
        assertEquals("Bearer t1", cache.current(expiresAt - 3_600_000L))
    }
}
