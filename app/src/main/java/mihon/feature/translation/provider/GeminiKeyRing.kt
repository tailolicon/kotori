package mihon.feature.translation.provider

import java.util.concurrent.ConcurrentHashMap

/**
 * Several Gemini API keys, used one after another so a spent quota does not stop a chapter.
 *
 * The free tier's daily allowance is the binding constraint on this feature — 500 requests on
 * Flash-Lite, 20 on the headline Flash models — and a reader who has more than one Google account
 * has more than one allowance. Keeping the keys in one preference and rotating between them turns
 * "translation stopped until tomorrow" into "translation carried on".
 *
 * The ring is deliberately in-memory. A daily quota resets at midnight Pacific, which is not a
 * clock this app can reason about, so an exhausted key is parked for [DAILY_PARK_SECONDS] and then
 * tried again; a key that has genuinely run out simply parks itself once more, at the cost of one
 * request. Persisting the parking would instead risk locking out a key whose quota had reset.
 */
internal class GeminiKeyRing(private val now: () -> Long = System::currentTimeMillis) {

    private val parkedUntil = ConcurrentHashMap<String, Long>()

    /** Keys the reader entered, one per line or comma-separated, blanks and duplicates removed. */
    fun parse(raw: String): List<String> = raw
        .split('\n', ',', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /**
     * The keys worth trying right now, in the order they should be tried.
     *
     * Never returns empty while any key exists: when every one of them is parked the caller still
     * has to make a request to find out whether a quota has reset, and the least recently parked is
     * the best candidate. Returning nothing here would mean the feature stayed off until the app
     * was restarted.
     */
    fun available(keys: List<String>): List<String> {
        if (keys.isEmpty()) return emptyList()
        val time = now()
        val ready = keys.filter { (parkedUntil[it] ?: 0L) <= time }
        if (ready.isNotEmpty()) return ready
        return keys.sortedBy { parkedUntil[it] ?: 0L }.take(1)
    }

    /** Sets [key] aside after a quota refusal. */
    fun park(key: String, seconds: Long) {
        parkedUntil[key] = now() + seconds * 1000
    }

    /** [key] worked, so whatever it was parked for is over. */
    fun release(key: String) {
        parkedUntil.remove(key)
    }

    /** True when every key is parked — the caller may want to report a quota failure. */
    fun allParked(keys: List<String>): Boolean {
        if (keys.isEmpty()) return false
        val time = now()
        return keys.all { (parkedUntil[it] ?: 0L) > time }
    }

    companion object {
        /** Park after a daily-quota refusal. Short enough that a real reset is noticed quickly. */
        const val DAILY_PARK_SECONDS = 30L * 60

        /** Park after a per-minute refusal that carried no server-suggested delay. */
        const val MINUTE_PARK_SECONDS = 60L

        /** Park after the key was refused outright; it will not recover on its own. */
        const val REJECTED_PARK_SECONDS = 60L * 60
    }
}
