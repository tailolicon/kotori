package mihon.feature.translation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class TranslationCacheInvalidationTest {

    @Test
    fun `bumping cache generation changes the on-disk key`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())
        val mangaId = 3257L
        val before = cacheKey(prefs, mangaId)
        prefs.bumpCacheGeneration(mangaId)
        val after = cacheKey(prefs, mangaId)
        assertNotEquals(before, after)
        assertNotEquals(hash(before), hash(after))
    }

    @Test
    fun `generation is per manga`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())
        prefs.bumpCacheGeneration(1L)
        prefs.bumpCacheGeneration(1L)
        assertTrue(prefs.cacheGeneration(1L).get() == 2)
        assertTrue(prefs.cacheGeneration(2L).get() == 0)
        assertNotEquals(cacheKey(prefs, 1L), cacheKey(prefs, 2L))
    }

    @Test
    fun `invalidating in-flight work on the same series rejects the old generation`() {
        val gate = TranslationWorkGate()
        gate.begin(3257L)
        val generation = gate.generation
        assertTrue(gate.allows(3257L, generation))
        gate.invalidateInFlight()
        assertFalse(gate.allows(3257L, generation))
        assertTrue(gate.allows(3257L, gate.generation))
    }

    @Test
    fun `a global clear changes every series' key`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())
        val one = cacheKey(prefs, 1L)
        val two = cacheKey(prefs, 2L)
        prefs.bumpGlobalCacheGeneration()
        assertNotEquals(one, cacheKey(prefs, 1L))
        assertNotEquals(two, cacheKey(prefs, 2L))
    }

    private fun cacheKey(prefs: TranslationPreferences, mangaId: Long): String =
        prefs.outputStamp() +
            "|g${prefs.globalCacheGeneration.get()}.${prefs.cacheGeneration(mangaId).get()}"

    private fun hash(stamp: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(stamp.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private class InMemoryPreferenceStore : PreferenceStore {
        private val values = ConcurrentHashMap<String, Any?>()

        override fun getString(key: String, defaultValue: String): Preference<String> = mem(key, defaultValue)
        override fun getLong(key: String, defaultValue: Long): Preference<Long> = mem(key, defaultValue)
        override fun getInt(key: String, defaultValue: Int): Preference<Int> = mem(key, defaultValue)
        override fun getFloat(key: String, defaultValue: Float): Preference<Float> = mem(key, defaultValue)
        override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = mem(key, defaultValue)
        override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = mem(key, defaultValue)
        override fun <T> getObjectFromString(
            key: String,
            defaultValue: T,
            serializer: (T) -> String,
            deserializer: (String) -> T,
        ): Preference<T> = mem(key, defaultValue)
        override fun <T> getObjectFromInt(
            key: String,
            defaultValue: T,
            serializer: (T) -> Int,
            deserializer: (Int) -> T,
        ): Preference<T> = mem(key, defaultValue)
        override fun <T> getObjectSetFromStringSet(
            key: String,
            defaultValue: Set<T>,
            serializer: (T) -> String,
            deserializer: (String) -> T?,
        ): Preference<Set<T>> = mem(key, defaultValue)
        override fun getAll(): Map<String, *> = values.toMap()

        @Suppress("UNCHECKED_CAST")
        private fun <T> mem(key: String, default: T): Preference<T> = object : Preference<T> {
            override fun key(): String = key
            override fun get(): T = (values[key] as? T) ?: default
            override fun set(value: T) {
                if (value == null) values.remove(key) else values[key] = value
            }
            override fun isSet(): Boolean = values.containsKey(key)
            override fun delete() {
                values.remove(key)
            }
            override fun defaultValue(): T = default
            override fun changes(): Flow<T> = flow { emit(get()) }
            override fun stateIn(scope: CoroutineScope): StateFlow<T> = MutableStateFlow(get())
        }
    }
}
