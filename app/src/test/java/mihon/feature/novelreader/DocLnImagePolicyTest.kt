package mihon.feature.novelreader

import android.content.Context
import coil3.network.httpHeaders
import eu.kanade.tachiyomi.source.novel.builtin.DocLnImagePolicy
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocLnImagePolicyTest {

    @Test
    fun `accepts observed DocLN image hosts`() {
        listOf(
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
        ).forEach { url ->
            assertTrue(DocLnImagePolicy.isTrusted(url), url)
        }
    }

    @Test
    fun `rejects unsafe and lookalike hosts`() {
        listOf(
            "http://i2.hako.vip/image.jpg",
            "https://evilhako.vip/image.jpg",
            "https://hako.vip.evil.example/image.jpg",
            "https://sub.i.ibb.co/image.jpg",
            "https://localhost/image.jpg",
            "https://127.0.0.1/image.jpg",
            "https://192.168.1.1/image.jpg",
            "https://169.254.169.254/latest/meta-data",
            "not a URL",
        ).forEach { url ->
            assertFalse(DocLnImagePolicy.isTrusted(url), url)
        }
    }

    @Test
    fun `adds DocLN referer only to Hako requests`() {
        val context = mockk<Context>(relaxed = true)
        val hako = novelImageRequest(context, "https://i2.hako.vip/image.jpg")
        val blogspot = novelImageRequest(context, "https://1.bp.blogspot.com/image.jpg")

        assertEquals(DocLnImagePolicy.REFERER, hako.httpHeaders["Referer"])
        assertNull(blogspot.httpHeaders["Referer"])
    }
}
