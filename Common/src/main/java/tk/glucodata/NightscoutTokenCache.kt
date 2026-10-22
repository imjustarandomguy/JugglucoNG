package tk.glucodata

/**
 * The bearer header the uploader sends with v3 requests, and until when it may be sent.
 *
 * A token is only good until the server stops taking it, and that can come before its
 * expiry: a token sent in the last second by a phone a little behind the server's clock, or
 * every token once the server stops accepting what it handed out. The uploader kept sending
 * such a token until its own expiry, up to eight hours of 401s with nothing uploaded, unless
 * the user forced a new one in the settings. Now the token is renewed a little early, and a
 * refused one is dropped so the next request exchanges the access token again.
 */
class NightscoutTokenCache {
    private var header = ""
    private var expiresAtMillis = 0L

    /** The header to send at [nowMillis], or "" when a new exchange is due. */
    @Synchronized
    fun current(nowMillis: Long): String =
        if (header.isNotEmpty() && nowMillis < expiresAtMillis - EXPIRY_MARGIN_MILLIS) header else ""

    /** Keeps the token [grant] carries and returns its header. */
    @Synchronized
    fun store(grant: NightscoutTokenGrant): String {
        header = "Bearer " + grant.token
        expiresAtMillis = grant.expiresAtMillis
        return header
    }

    @Synchronized
    fun clear() {
        header = ""
        expiresAtMillis = 0L
    }

    /**
     * The server answered 401 to a request sent with [sentHeader]. Drops the token when it is
     * still the cached one, and says so; one exchanged since that request went out is kept.
     */
    @Synchronized
    fun refused(sentHeader: String?): Boolean {
        if (sentHeader.isNullOrEmpty() || sentHeader != header) return false
        clear()
        return true
    }

    companion object {
        /** Clock skew guard, as the follower's: stop trusting the token slightly before the server does. */
        const val EXPIRY_MARGIN_MILLIS = 60_000L
    }
}
