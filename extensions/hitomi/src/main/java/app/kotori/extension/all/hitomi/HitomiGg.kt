package app.kotori.extension.all.hitomi

/**
 * Hitomi shards images via `gg.js`. `s(hash)` is stable (last three hex digits rearranged).
 * `m(g)` and `b` change when they reshuffle the CDN; we parse those from the script.
 */
internal object HitomiGg {

    fun pathFromHash(hash: String): String {
        if (hash.length < 3) return "0"
        val last3 = hash.takeLast(3)
        val rearranged = last3.last().toString() + last3.substring(0, 2)
        return rearranged.toIntOrNull(16)?.toString() ?: "0"
    }

    fun thumbnailUrl(hash: String): String {
        if (hash.length < 3) return "https://tn.hitomi.la/webpsmalltn/$hash.webp"
        val last3 = hash.takeLast(3)
        return "https://tn.hitomi.la/webpsmalltn/${last3.last()}/${last3.substring(0, 2)}/$hash.webp"
    }

    fun parse(script: String): Script {
        val base = BASE_REGEX.find(script)?.groupValues?.get(1).orEmpty()
        val body = M_FN_REGEX.find(script)?.groupValues?.get(1).orEmpty()
        val ones = linkedSetOf<Int>()
        for (match in INT_REGEX.findAll(body)) {
            match.value.toIntOrNull()?.let { ones += it }
        }
        return Script(base = base, mappedOnes = ones)
    }

    fun imageUrl(script: Script, hash: String, preferAvif: Boolean): String {
        val ext = if (preferAvif) "avif" else "webp"
        val path = pathFromHash(hash)
        val bucket = path.toIntOrNull() ?: 0
        val mapped = if (script.mappedOnes.isEmpty()) {
            bucket % 2
        } else if (bucket in script.mappedOnes) {
            1
        } else {
            0
        }
        val dir = script.base
        return "https://w${mapped + 1}.hitomi.la/$ext/$dir$path/$hash.$ext"
    }

    data class Script(val base: String, val mappedOnes: Set<Int>)

    private val BASE_REGEX = Regex("""b:\s*"([^"]+)"""")
    private val M_FN_REGEX = Regex("""m\s*:\s*function\s*\(\s*g\s*\)\s*\{(.+?)\}""", RegexOption.DOT_MATCHES_ALL)
    private val INT_REGEX = Regex("""\b([1-9]\d{2,7})\b""")
}
