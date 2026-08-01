package eu.kanade.tachiyomi.animesource

/**
 * Marks a source compiled into the app rather than installed as an extension APK.
 *
 * Lives here rather than beside the built-in sources themselves so the data layer, which
 * maps sources into the domain model, can recognise one without depending on the app module.
 */
interface BuiltInAnimeSource
