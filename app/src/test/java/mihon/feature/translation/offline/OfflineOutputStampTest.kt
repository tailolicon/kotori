package mihon.feature.translation.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import mihon.feature.translation.TranslationPreferences
import mihon.feature.translation.TranslationProviderType
import mihon.feature.translation.TranslationRenderStyle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.concurrent.ConcurrentHashMap

/**
 * outputStamp must change when the offline model identity changes so rendered-page cache
 * entries from another provider/model are not reused.
 */
class OfflineOutputStampTest {

    @Test
    fun `offline model id is part of outputStamp`() {
        val store = InMemoryPreferenceStore()
        val prefs = TranslationPreferences(store)
        prefs.provider.set(TranslationProviderType.OFFLINE)
        prefs.offlineModelId.set("HY-MT1.5-1.8B-Q4_K_M")
        val a = prefs.outputStamp()
        prefs.offlineModelId.set("HY-MT1.5-1.8B-Q6_K")
        val b = prefs.outputStamp()
        assertTrue(a.contains("OFFLINE"))
        assertTrue(a.contains("HY-MT1.5-1.8B-Q4_K_M"))
        assertTrue(b.contains("HY-MT1.5-1.8B-Q6_K"))
        assertFalse(a == b)
    }

    @Test
    fun `switching to offline changes stamp vs google`() {
        val store = InMemoryPreferenceStore()
        val prefs = TranslationPreferences(store)
        prefs.provider.set(TranslationProviderType.GOOGLE)
        val google = prefs.outputStamp()
        prefs.provider.set(TranslationProviderType.OFFLINE)
        val offline = prefs.outputStamp()
        assertFalse(google == offline)
    }

    @Test
    fun `hasCredentialsFor offline uses offlineModelReady preference`() {
        val store = InMemoryPreferenceStore()
        val prefs = TranslationPreferences(store)
        assertFalse(prefs.hasCredentialsFor(TranslationProviderType.OFFLINE))
        prefs.offlineModelReady.set(true)
        assertTrue(prefs.hasCredentialsFor(TranslationProviderType.OFFLINE))
        assertTrue(prefs.hasCredentialsFor(TranslationProviderType.GOOGLE))
    }

    @Test
    fun `render style is part of outputStamp`() {
        val store = InMemoryPreferenceStore()
        val prefs = TranslationPreferences(store)
        val simple = prefs.outputStamp()
        prefs.setRenderStyle(TranslationRenderStyle.TYPESET)
        val typeset = prefs.outputStamp()
        prefs.setRenderStyle(TranslationRenderStyle.BUBBLE)
        val bubble = prefs.outputStamp()
        assertTrue(simple.contains("simple"))
        assertTrue(typeset.contains("typeset"))
        assertTrue(bubble.contains("bubble"))
        assertFalse(simple == typeset)
        assertFalse(typeset == bubble)
    }

    @Test
    fun `license acceptance is versioned`() {
        val store = InMemoryPreferenceStore()
        val prefs = TranslationPreferences(store)
        assertFalse(prefs.offlineLicenseAccepted())
        prefs.acceptOfflineLicense()
        assertTrue(prefs.offlineLicenseAccepted())
    }

    private class InMemoryPreferenceStore : PreferenceStore {
        private val values = ConcurrentHashMap<String, Any?>()

        override fun getString(key: String, defaultValue: String): Preference<String> =
            mem(key, defaultValue)

        override fun getLong(key: String, defaultValue: Long): Preference<Long> =
            mem(key, defaultValue)

        override fun getInt(key: String, defaultValue: Int): Preference<Int> =
            mem(key, defaultValue)

        override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
            mem(key, defaultValue)

        override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
            mem(key, defaultValue)

        override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
            mem(key, defaultValue)

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
