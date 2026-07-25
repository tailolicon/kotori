package mihon.feature.novelreader

import android.content.Context
import coil3.network.httpHeaders
import eu.kanade.tachiyomi.source.novel.NovelImagePolicy
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelImagePolicyTest {

    @Test
    fun `accepts any public https image host`() {
        listOf(
            // Hosts the old hardcoded allowlist knew about.
            "https://docln.net/image.jpg",
            "https://1.bp.blogspot.com/image.jpg",
            "https://blogger.googleusercontent.com/image.jpg",
            "https://i2.hako.vip/image.jpg",
            "https://i.ibb.co/image.jpg",
            "https://i.postimg.cc/image.jpg",
            "https://cdn.phototourl.com/image.jpg",
            "https://headcanontl.wordpress.com/image.jpg",
            "https://img.wattpad.com/image.jpg",
            "https://images2.imgbox.com/image.jpg",
            // Hosts it did not, which is the whole reason the allowlist had to go: each of these
            // silently dropped its illustration before.
            "https://i.imgur.com/image.jpg",
            "https://res.cloudinary.com/demo/image.jpg",
            "https://cdn.discordapp.com/attachments/1/2/image.png",
            "https://lh3.googleusercontent.com/image=w800",
            "https://media.discordapp.net/attachments/1/2/image.png",
            "https://raw.githubusercontent.com/user/repo/main/image.png",
            "https://some-brand-new-cdn.example.org/image.webp",
        ).forEach { url ->
            assertTrue(NovelImagePolicy.isTrusted(url), url)
        }
    }

    @Test
    fun `rejects non-https, unroutable and unparseable sources`() {
        listOf(
            // Plaintext is refused whatever the host, so a chapter cannot downgrade a request.
            "http://i2.hako.vip/image.jpg",
            "http://i.imgur.com/image.jpg",
            // IP literals are how SSRF probes address loopback, private ranges and metadata.
            "https://127.0.0.1/image.jpg",
            "https://192.168.1.1/image.jpg",
            "https://10.0.0.5/image.jpg",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/image.jpg",
            "https://[fd00::1]/image.jpg",
            // Names that cannot resolve on the public internet.
            "https://localhost/image.jpg",
            "https://intranet/image.jpg",
            "https://router.local/image.jpg",
            "https://metadata.internal/image.jpg",
            "https://something.onion/image.jpg",
            "not a URL",
            "",
        ).forEach { url ->
            assertFalse(NovelImagePolicy.isTrusted(url), url)
        }
    }

    @Test
    fun `attaches a referer only to hosts that gate on it`() {
        val context = mockk<Context>(relaxed = true)
        val hako = novelImageRequest(context, "https://i2.hako.vip/image.jpg")
        val wattpad = novelImageRequest(context, "https://img.wattpad.com/image.jpg")
        val imgur = novelImageRequest(context, "https://i.imgur.com/image.jpg")

        assertEquals("https://docln.net/", hako.httpHeaders["Referer"])
        assertEquals("https://www.wattpad.com/", wattpad.httpHeaders["Referer"])
        assertNull(imgur.httpHeaders["Referer"])
    }

    @Test
    fun `keeps illustrations and prose in order and drops untrusted picture lines`() {
        val sentinel = ""
        val content = listOf(
            "Mở đầu chương.",
            sentinel + "https://i.imgur.com/one.png",
            "Đoạn tiếp theo.",
            sentinel + "http://127.0.0.1/evil.png",
            sentinel + "https://img.wattpad.com/two.png",
        ).joinToString("\n\n")

        val blocks = content.toNovelBlocks()

        assertEquals(
            listOf(
                NovelBlock.Prose("Mở đầu chương."),
                NovelBlock.Illustration("https://i.imgur.com/one.png"),
                NovelBlock.Prose("Đoạn tiếp theo."),
                NovelBlock.Illustration("https://img.wattpad.com/two.png"),
            ),
            blocks,
        )
    }
}
