package com.podclatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Build-time inspection of the source manifests and the network security config (the unit tests run with
 * the module folder as the working directory). The merged and compiled copies are checked in the PR with
 * the Android tools.
 */
class ManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    private val main get() = parse("src/main/AndroidManifest.xml")
    private val application get() = main.children("application").single()

    @Test
    fun requestsExactlyTheDocumentedPermissions() {
        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.WAKE_LOCK",
            ),
            main.children("uses-permission").map { it.attr("name") }.toSet(),
        )
    }

    @Test
    fun theServiceIsExportedAsAMediaPlaybackForegroundServiceWithBothActions() {
        val service = application.children("service").single()
        assertEquals(".playback.PlaybackService", service.attr("name"))
        assertEquals("true", service.attr("exported"))
        assertEquals("mediaPlayback", service.attr("foregroundServiceType"))
        val actions = service.children("intent-filter").single().children("action").map { it.attr("name") }
        assertEquals(setOf("androidx.media3.session.MediaLibraryService", "android.media.browse.MediaBrowserService"), actions.toSet())
    }

    @Test
    fun mainActivityIsSingleTop() {
        val activity = application.children("activity").single()
        assertEquals(".MainActivity", activity.attr("name"))
        assertEquals("singleTop", activity.attr("launchMode"))
    }

    @Test
    fun theMainManifestHasNoDebugReceiverAndPointsAtTheNetworkConfig() {
        assertTrue(application.children("receiver").isEmpty())
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("debug", ignoreCase = true))
        assertEquals("@xml/network_security_config", application.attr("networkSecurityConfig"))
    }

    @Test
    fun onlyTheDebugManifestDeclaresTheReceiver() {
        val receiver = parse("src/debug/AndroidManifest.xml").children("application").single().children("receiver").single()
        assertEquals(".debug.DebugPlaybackReceiver", receiver.attr("name"))
        assertEquals("true", receiver.attr("exported"))
    }

    @Test
    fun cleartextIsPermittedByTheBaseConfigAndTheDebugOverrideIsGone() {
        val config = parse("src/main/res/xml/network_security_config.xml")
        assertTrue(config.children("domain-config").isEmpty())
        assertEquals("true", config.children("base-config").single().getAttribute("cleartextTrafficPermitted"))
        assertFalse(File("src/debug/res/xml/network_security_config.xml").exists())
        val text = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(text.contains("ServerUrlValidator"))
        assertTrue(text.contains("InsecureBaseConfiguration"))
    }
}
