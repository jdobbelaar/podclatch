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
    fun theApplicationDeclaresAndroidAutoAsAMediaApp() {
        val meta = application.children("meta-data").single { it.attr("name") == "com.google.android.gms.car.application" }
        assertEquals("@xml/automotive_app_desc", meta.attr("resource"))
        val desc = parse("src/main/res/xml/automotive_app_desc.xml")
        assertEquals("automotiveApp", desc.tagName)
        assertEquals("media", desc.children("uses").single().getAttribute("name"))
    }

    @Test
    fun theMediaButtonReceiverIsDeclaredForTheMediaButtonAction() {
        val receiver = application.children("receiver").single()
        assertEquals("androidx.media3.session.MediaButtonReceiver", receiver.attr("name"))
        assertEquals("true", receiver.attr("exported"))
        val actions = receiver.children("intent-filter").single().children("action").map { it.attr("name") }
        assertEquals(listOf("android.intent.action.MEDIA_BUTTON"), actions)
    }

    /** The merged manifests (`processDebugManifest` and `processReleaseManifest` output; the unit-test tasks depend on them). */
    @Test
    fun theMergedDebugAndReleaseManifestsKeepTheAndroidAutoDeclarations() {
        for (variant in listOf("debug", "release")) {
            val name = variant.replaceFirstChar { it.uppercase() }
            val file = File("build/intermediates/merged_manifests/$variant/process${name}Manifest/AndroidManifest.xml")
            assertTrue("$variant merged manifest exists: ${file.path}", file.exists())
            val app = parse(file.path).children("application").single()
            val meta = app.children("meta-data").single { it.attr("name") == "com.google.android.gms.car.application" }
            assertEquals("$variant: car metadata", "@xml/automotive_app_desc", meta.attr("resource"))
            val service = app.children("service").single { it.attr("name") == "com.podclatch.playback.PlaybackService" }
            assertEquals("$variant: exported", "true", service.attr("exported"))
            assertEquals("$variant: foreground type", "mediaPlayback", service.attr("foregroundServiceType"))
            val actions = service.children("intent-filter").flatMap { it.children("action") }.map { it.attr("name") }.toSet()
            assertEquals(
                setOf("androidx.media3.session.MediaLibraryService", "android.media.browse.MediaBrowserService"),
                actions,
            )
            val receiver = app.children("receiver").single { it.attr("name") == "androidx.media3.session.MediaButtonReceiver" }
            val receiverActions = receiver.children("intent-filter").flatMap { it.children("action") }.map { it.attr("name") }
            assertEquals("$variant: media button action", listOf("android.intent.action.MEDIA_BUTTON"), receiverActions)
            assertEquals("$variant: debug receiver only in debug", variant == "debug", app.children("receiver").any { it.attr("name").endsWith("DebugPlaybackReceiver") })
        }
    }

    @Test
    fun theMainManifestHasNoDebugReceiverAndPointsAtTheNetworkConfig() {
        assertTrue(application.children("receiver").none { it.attr("name").contains("debug", ignoreCase = true) })
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
