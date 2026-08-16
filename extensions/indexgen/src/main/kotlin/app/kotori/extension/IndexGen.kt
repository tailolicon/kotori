package app.kotori.extension

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * Builds the Kotori extension stores from the signed APKs.
 *
 * Two stores, because the app reads them with two different managers: manga and novel extensions
 * come from `extensions/repo`, anime ones from `extensions/repo-anime`. Listing an anime extension
 * in the manga index would offer it for install in the manga list, where nothing can load it.
 *
 * Manga store emits the three documents Keiyoushi publishes — `index.pb` (the tachiyomix protobuf
 * Kotori's add-store dialog expects) plus `repo.json`/`index.min.json` for other forks. The anime
 * store emits the legacy pair only, which is all `AnimeExtensionManager` reads.
 */
@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val root = File(".").canonicalFile
    val fingerprint = readSigningFingerprint(root)

    MODULES.groupBy { it.kind }.forEach { (kind, modules) ->
        writeStore(root, kind, modules, fingerprint)
    }

    println("Signing SHA-256: $fingerprint")
}

private enum class Kind(val repoDir: String) {
    MANGA("extensions/repo"),
    ANIME("extensions/repo-anime"),
}

private data class Source(
    /** The name the source's `id` is derived from — not always what it calls itself now. */
    val idName: String,
    val displayName: String,
    val lang: String,
    val homeUrl: String,
)

private data class Module(
    val dir: String,
    val pkg: String,
    val label: String,
    val kind: Kind,
    val nsfw: Boolean,
    val sources: List<Source>,
)

private fun source(name: String, lang: String, homeUrl: String, idName: String = name) =
    Source(idName = idName, displayName = name, lang = lang, homeUrl = homeUrl)

private val HITOMI_LANGS = listOf(
    "all", "en", "id", "jv", "ca", "ceb", "cs", "da", "de", "et", "es", "eo", "fr", "it", "hi",
    "hu", "pl", "pt", "vi", "tr", "ru", "uk", "ar", "ko", "zh", "ja",
)

private val MODULES = listOf(
    Module(
        dir = "hitomi",
        pkg = "app.kotori.extension.all.hitomi",
        label = "Hitomi",
        kind = Kind.MANGA,
        nsfw = true,
        sources = HITOMI_LANGS.map { source("Hitomi", it, "https://hitomi.la") },
    ),
    Module(
        dir = "wattpad",
        pkg = "app.kotori.extension.vi.wattpad",
        label = "Wattpad",
        kind = Kind.MANGA,
        nsfw = false,
        sources = listOf(source("Wattpad", "all", "https://www.wattpad.com")),
    ),
    Module(
        dir = "novelfever",
        pkg = "app.kotori.extension.vi.novelfever",
        label = "Novel Fever",
        kind = Kind.MANGA,
        nsfw = false,
        sources = listOf(
            // The id still comes from the name the source shipped under; renaming it must not
            // orphan the libraries built on the old one.
            source("Novel Fever", "vi", "https://android.lonoapp.net", idName = "Nôvel Fever (MeTruyenChu)"),
        ),
    ),
    Module(
        dir = "docln",
        pkg = "app.kotori.extension.vi.docln",
        label = "DocLN",
        kind = Kind.MANGA,
        nsfw = false,
        sources = listOf(source("DocLN", "vi", "https://docln.sbs")),
    ),
    Module(
        dir = "animehay",
        pkg = "app.kotori.extension.vi.animehay",
        label = "AnimeHay",
        kind = Kind.ANIME,
        nsfw = false,
        sources = listOf(source("AnimeHay", "vi", "https://animehay08.site")),
    ),
    Module(
        dir = "animevietsub",
        pkg = "app.kotori.extension.vi.animevietsub",
        label = "AnimeVietsub",
        kind = Kind.ANIME,
        nsfw = false,
        sources = listOf(source("AnimeVietsub", "vi", "https://animevietsub.beauty")),
    ),
)

private const val BASE = "https://raw.githubusercontent.com/tailolicon/kotori/main"

@OptIn(ExperimentalSerializationApi::class)
private fun writeStore(root: File, kind: Kind, modules: List<Module>, fingerprint: String) {
    val repoDir = File(root, kind.repoDir)
    val apkDir = File(repoDir, "apk").apply { mkdirs() }
    val iconDir = File(repoDir, "icon").apply { mkdirs() }
    val baseUrl = "$BASE/${kind.repoDir}"

    val built = modules.map { module ->
        val apk = File(root, "extensions/${module.dir}/build/outputs/apk")
            .walkTopDown()
            .firstOrNull { it.isFile && it.extension == "apk" }
            ?: error("Build ${module.dir} first (./gradlew :${module.dir}-ext:assembleRelease)")

        apk.copyTo(File(apkDir, "${module.pkg}.apk"), overwrite = true)

        val iconSrc = File(root, "extensions/${module.dir}/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
        if (iconSrc.isFile) iconSrc.copyTo(File(iconDir, "${module.pkg}.png"), overwrite = true)

        module to readVersion(apk)
    }

    val extensions = built.map { (module, version) ->
        val (versionCode, versionName) = version
        NetworkExtensionStore.Extension(
            name = module.label,
            packageName = module.pkg,
            resources = NetworkExtensionStore.Resources(
                apkUrl = "$baseUrl/apk/${module.pkg}.apk",
                iconUrl = "$baseUrl/icon/${module.pkg}.png",
            ),
            // The Aniyomi-side loader takes the lib version from the versionName prefix and
            // only accepts 12–16, so anime extensions cannot use Mihon's "1.4".
            extensionLib = if (kind == Kind.ANIME) "16" else "1.4",
            versionCode = versionCode,
            versionName = versionName,
            contentWarning = if (module.nsfw) 3 else 0,
            sources = module.sources.map {
                NetworkExtensionStore.Source(
                    id = generateId(it.idName, it.lang, 1),
                    name = it.displayName,
                    language = it.lang,
                    homeUrl = it.homeUrl,
                )
            },
        )
    }

    if (kind == Kind.MANGA) {
        val store = NetworkExtensionStore(
            name = "Kotori",
            badgeLabel = "KTR",
            signingKey = fingerprint,
            contact = NetworkExtensionStore.Contact(website = "https://github.com/tailolicon/kotori"),
            extensionList = NetworkExtensionStore.ExtensionList(extensions),
        )
        val proto = ProtoBuf { encodeDefaults = true }
        File(repoDir, "index.pb").writeBytes(proto.encodeToByteArray(NetworkExtensionStore.serializer(), store))
        File(repoDir, "index.json").writeText(
            Json { prettyPrint = true; encodeDefaults = true }
                .encodeToString(NetworkExtensionStore.serializer(), store),
        )
    }

    File(repoDir, "repo.json").writeText(
        """
        {
          "meta": {
            "name": "Kotori${if (kind == Kind.ANIME) " (Anime)" else ""}",
            "shortName": "KTR",
            "website": "https://github.com/tailolicon/kotori",
            "signingKeyFingerprint": "$fingerprint"
          }
        }
        """.trimIndent() + "\n",
    )

    val legacy = built.mapIndexed { index, (module, version) ->
        val extension = extensions[index]
        LegacyExtension(
            name = "Tachiyomi: ${module.label}",
            pkg = module.pkg,
            apk = "${module.pkg}.apk",
            lang = module.sources.map { it.lang }.distinct().singleOrNull() ?: "all",
            code = extension.versionCode,
            version = version.second,
            nsfw = if (module.nsfw) 1 else 0,
            sources = extension.sources.map {
                LegacyExtension.Source(it.id, it.language, it.name, it.homeUrl)
            },
        )
    }
    File(repoDir, "index.min.json").writeText(
        Json { encodeDefaults = true }
            .encodeToString(ListSerializer(LegacyExtension.serializer()), legacy),
    )

    println("Wrote ${kind.name.lowercase()} store to ${repoDir.absolutePath} (${extensions.size} extensions)")
    println("  add in Kotori: $baseUrl/${if (kind == Kind.MANGA) "index.pb" else "index.min.json"}")
}

private fun generateId(name: String, lang: String, versionId: Int): Long {
    val key = "${name.lowercase()}/$lang/$versionId"
    val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
    return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
}

/**
 * The version AGP actually stamped into the apk, from the metadata it writes beside it.
 *
 * Not from the jar manifest: AGP writes no `Implementation-Version`, so reading it there always
 * fell through to a default and every extension in the store claimed to be 1.4.1 — which also made
 * the anime index advertise lib 1.4, a version the anime loader refuses.
 */
private fun readVersion(apk: File): Pair<Long, String> {
    val metadata = File(apk.parentFile, "output-metadata.json")
    require(metadata.isFile) { "No output-metadata.json beside ${apk.name}" }
    val element = Json { ignoreUnknownKeys = true }
        .decodeFromString(OutputMetadata.serializer(), metadata.readText())
        .elements
        .first()
    return element.versionCode to element.versionName
}

@Serializable
private data class OutputMetadata(val elements: List<Element>) {
    @Serializable
    data class Element(val versionCode: Long, val versionName: String)
}

private fun readSigningFingerprint(root: File): String {
    val props = Properties()
    File(root, "extensions/keystore/keystore.properties").inputStream().use(props::load)
    val cert = File(root, props.getProperty("certSha256") ?: "extensions/keystore/cert.sha256")
    if (cert.isFile) return cert.readText().trim().lowercase()
    error("Missing extensions/keystore/cert.sha256 — generate the keystore first")
}

@Serializable
private data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact,
    @ProtoNumber(101) val extensionList: ExtensionList?,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String,
    )

    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension>)

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: Int,
        @ProtoNumber(8) val sources: List<Source>,
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String,
        @ProtoNumber(2) val iconUrl: String,
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
    )
}

@Serializable
private data class LegacyExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<Source>,
) {
    @Serializable
    data class Source(val id: Long, val lang: String, val name: String, val baseUrl: String)
}
