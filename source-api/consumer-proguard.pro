-keep class eu.kanade.tachiyomi.source.model.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.online.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.** extends eu.kanade.tachiyomi.source.Source { public protected *; }

-keep,allowoptimization class eu.kanade.tachiyomi.util.JsoupExtensionsKt { public protected *; }

# Extension APKs link against these by name at runtime, so R8 must leave them alone. The rules
# above cover `source.model`, `source.online` and anything extending Source; the anime API, the
# mirror resolver and the novel/reader agreements are reached from an extension just as directly
# and were kept only by accident of the app happening to call them.
-keep class eu.kanade.tachiyomi.animesource.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.MirrorResolver { public protected *; }
-keep class eu.kanade.tachiyomi.source.MirrorResolverKt { public protected *; }
-keep class eu.kanade.tachiyomi.source.novel.** { public protected *; }
