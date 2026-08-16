package app.kotori.extension

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.jar.JarFile

/**
 * Builds the Kotori extension store files next to the signed Hitomi APK.
 *
 * Emits the same three documents Keiyoushi publishes:
 *  - `index.pb` — tachiyomix protobuf store (what Kotori's add-store dialog expects)
 *  - `repo.json` + `index.min.json` — legacy Mihon format, so other forks can add the store too
 */
@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val root = File(".").canonicalFile
    val repoDir = File(root, "extensions/repo")
    val apkDir = File(repoDir, "apk")
    val iconDir = File(repoDir, "icon")
    apkDir.mkdirs()
    iconDir.mkdirs()

    val apk = File(root, "extensions/hitomi/build/outputs/apk")
        .walkTopDown()
        .firstOrNull { it.isFile && it.extension == "apk" }
        ?: error("Build the Hitomi APK first")

    val pkg = "app.kotori.extension.all.hitomi"
    val destApk = File(apkDir, "$pkg.apk")
    apk.copyTo(destApk, overwrite = true)

    val iconSrc = File(root, "extensions/hitomi/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
    if (iconSrc.isFile) {
        iconSrc.copyTo(File(iconDir, "$pkg.png"), overwrite = true)
    }

    val versionName = readVersionName(destApk) ?: "1.4.1"
    val versionCode = versionName.substringAfterLast('.').toLongOrNull() ?: 1L
    val fingerprint = readSigningFingerprint()

    val baseUrl = "https://raw.githubusercontent.com/tailolicon/kotori/main/extensions/repo"
    val sources = hitomiSources()

    val store = NetworkExtensionStore(
        name = "Kotori",
        badgeLabel = "KTR",
        signingKey = fingerprint,
        contact = NetworkExtensionStore.Contact(
            website = "https://github.com/tailolicon/kotori",
        ),
        extensionList = NetworkExtensionStore.ExtensionList(
            extensions = listOf(
                NetworkExtensionStore.Extension(
                    name = "Hitomi.la",
                    packageName = pkg,
                    resources = NetworkExtensionStore.Resources(
                        apkUrl = "$baseUrl/apk/$pkg.apk",
                        iconUrl = "$baseUrl/icon/$pkg.png",
                    ),
                    extensionLib = "1.4",
                    versionCode = versionCode,
                    versionName = versionName,
                    contentWarning = 3, // NSFW
                    sources = sources,
                ),
            ),
        ),
    )

    val proto = ProtoBuf { encodeDefaults = true }
    File(repoDir, "index.pb").writeBytes(proto.encodeToByteArray(NetworkExtensionStore.serializer(), store))

    val pretty = Json { prettyPrint = true; encodeDefaults = true }
    File(repoDir, "index.json").writeText(pretty.encodeToString(NetworkExtensionStore.serializer(), store))

    val legacyRepo = """
        {
          "meta": {
            "name": "Kotori",
            "shortName": "KTR",
            "website": "https://github.com/tailolicon/kotori",
            "signingKeyFingerprint": "$fingerprint"
          }
        }
    """.trimIndent() + "\n"
    File(repoDir, "repo.json").writeText(legacyRepo)

    val legacyIndex = Json { encodeDefaults = true }
    val min = listOf(
        LegacyExtension(
            name = "Tachiyomi: Hitomi.la",
            pkg = pkg,
            apk = "$pkg.apk",
            lang = "all",
            code = versionCode,
            version = versionName,
            nsfw = 1,
            sources = sources.map {
                LegacyExtension.Source(it.id, it.language, it.name, it.homeUrl)
            },
        ),
    )
    File(repoDir, "index.min.json").writeText(
        legacyIndex.encodeToString(kotlinx.serialization.builtins.ListSerializer(LegacyExtension.serializer()), min),
    )

    println("Wrote store to ${repoDir.absolutePath}")
    println("Add in Kotori: $baseUrl/index.pb")
    println("Signing SHA-256: $fingerprint")
}

private fun hitomiSources(): List<NetworkExtensionStore.Source> {
    val langs = listOf(
        "all" to "all",
        "en" to "english",
        "ja" to "japanese",
        "ko" to "korean",
        "zh" to "chinese",
        "vi" to "vietnamese",
        "id" to "indonesian",
        "ru" to "russian",
        "es" to "spanish",
    )
    return langs.map { (lang, _) ->
        NetworkExtensionStore.Source(
            id = generateId("Hitomi.la", lang, 1),
            name = "Hitomi.la",
            language = lang,
            homeUrl = "https://hitomi.la",
        )
    }
}

private fun generateId(name: String, lang: String, versionId: Int): Long {
    val key = "${name.lowercase()}/$lang/$versionId"
    val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
    return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
}

private fun readVersionName(apk: File): String? = runCatching {
    JarFile(apk).use { jar ->
        jar.manifest?.mainAttributes?.getValue("Implementation-Version")
    }
}.getOrNull()

private fun readSigningFingerprint(): String {
    val props = Properties()
    File("extensions/keystore/keystore.properties").inputStream().use(props::load)
    val cert = File(props.getProperty("certSha256") ?: "extensions/keystore/cert.sha256")
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
