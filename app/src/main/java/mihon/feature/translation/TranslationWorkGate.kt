package mihon.feature.translation

/**
 * One-at-a-time session for in-flight translation.
 *
 * The manager is a process singleton. Without a generation, turning translation off on series A
 * and opening series B left A's pages occupying the two in-flight slots — B waited on HTTP
 * calls for a series the reader had already left, which is exactly the "still translating the
 * other one" stall.
 *
 * [begin] on a different manga, or [cancel], bumps [generation]. Callers snapshot the generation
 * when they start a page and abort if it no longer matches.
 */
class TranslationWorkGate {

    @Volatile
    var activeMangaId: Long = NONE
        private set

    @Volatile
    var generation: Int = 0
        private set

    fun begin(mangaId: Long) {
        if (activeMangaId != mangaId) {
            generation++
            activeMangaId = mangaId
        }
    }

    fun cancel(mangaId: Long? = null) {
        if (mangaId == null || activeMangaId == mangaId) {
            generation++
            if (mangaId == null || activeMangaId == mangaId) {
                activeMangaId = NONE
            }
        }
    }

    /**
     * Drops every in-flight page of the current series without leaving the session.
     *
     * [begin] does not bump [generation] when the same manga is claimed again, so a clear / Dịch
     * lại that only deleted files left writers holding the old generation free to recreate them.
     */
    fun invalidateInFlight() {
        generation++
    }

    fun allows(mangaId: Long, generationAtStart: Int): Boolean {
        return activeMangaId == mangaId && generation == generationAtStart
    }

    companion object {
        const val NONE = Long.MIN_VALUE
    }
}
