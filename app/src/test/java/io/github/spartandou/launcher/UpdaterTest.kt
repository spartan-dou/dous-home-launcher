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
            {"name":"DousHomeLauncher-v1.9.apk","browser_download_url":"https://example.com/a.apk"}]}"""
        val release = Updater.parseRelease(json)!!
        assertEquals(9, release.code)
        assertEquals("https://example.com/a.apk", release.apkUrl)
        assertNull(Updater.parseRelease("""{"tag_name":"v1.9","assets":[]}"""))
    }
}
