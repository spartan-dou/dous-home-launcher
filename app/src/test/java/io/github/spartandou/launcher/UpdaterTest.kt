package io.github.spartandou.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test
    fun tagGivesTheVersionCode() {
        assertEquals(42, Updater.parseTag("v1.42"))
        assertEquals(7, Updater.parseTag("1.7"))
        assertNull(Updater.parseTag("build-d6b8570"))
    }

    @Test
    fun releaseNeedsAnApk() {
        val json = """{"tag_name":"v1.9","assets":[{"name":"notes.txt","browser_download_url":"x"},
            {"name":"DousHomeLauncher-v1.9.apk","browser_download_url":"https://example.com/a.apk","digest":"sha256:$HASH"}]}"""
        val release = Updater.parseRelease(json)!!
        assertEquals(9, release.code)
        assertEquals("https://example.com/a.apk", release.apkUrl)
        assertEquals(HASH, release.sha256)
        assertNull(Updater.parseRelease("""{"tag_name":"v1.9","assets":[]}"""))
    }

    @Test
    fun releaseWithoutValidDigestIsRefused() {
        val asset = """{"name":"a.apk","browser_download_url":"https://example.com/a.apk"%s}"""
        assertNull(Updater.parseRelease("""{"tag_name":"v1.9","assets":[${asset.format("")}]}"""))
        assertNull(Updater.parseRelease("""{"tag_name":"v1.9","assets":[${asset.format(""","digest":"sha256:abc"""")}]}"""))
    }

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
