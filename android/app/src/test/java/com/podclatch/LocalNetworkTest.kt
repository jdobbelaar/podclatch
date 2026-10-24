package com.podclatch

import com.podclatch.domain.HostResolver
import com.podclatch.domain.LocalHostClassifier
import com.podclatch.domain.LocalNetwork
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class LocalNetworkTest {
    private class FakeResolver(private val answers: Map<String, List<String>> = emptyMap()) : HostResolver {
        val calls = AtomicInteger()
        override suspend fun resolve(host: String): List<ByteArray> {
            calls.incrementAndGet()
            val ips = answers[host] ?: throw java.net.UnknownHostException(host)
            return ips.map { InetAddress.getByName(it).address }
        }
    }

    private val resolver = FakeResolver(
        mapOf(
            "home.example" to listOf("192.168.1.20"),
            "example.com" to listOf("93.184.216.34"),
            "both.example" to listOf("93.184.216.34", "10.0.0.5"),
        ),
    )
    private val classifier = LocalHostClassifier(resolver, timeoutMs = 3_000)

    private fun check(host: String, expected: Boolean) = runTest {
        assertEquals("is $host local", expected, classifier.isLocal(host))
    }

    @Test fun local10_2_2() = check("10.0.2.2", true)
    @Test fun local10_1_2_3() = check("10.1.2.3", true)
    @Test fun local172_16() = check("172.16.0.1", true)
    @Test fun local172_31() = check("172.31.255.255", true)
    @Test fun local192_168() = check("192.168.1.20", true)
    @Test fun localLinkLocal() = check("169.254.1.1", true)
    @Test fun localIpv6LinkLocal() = check("[fe80::1]", true)
    @Test fun localIpv6UniqueLocal() = check("[fd12:3456::1]", true)
    @Test fun localCgnatLow() = check("100.64.0.1", true)
    @Test fun localCgnatHigh() = check("100.127.255.255", true)
    @Test fun localDotLocal() = check("nas.local", true)
    @Test fun localDotLocalUpper() = check("NAS.LOCAL", true)
    @Test fun localResolvedName() = check("home.example", true)
    @Test fun localMixedResolution() = check("both.example", true)

    @Test fun notLoopback4() = check("127.0.0.1", false)
    @Test fun notLoopbackRange() = check("127.1.2.3", false)
    @Test fun notLocalhost() = check("localhost", false)
    @Test fun notLoopback6() = check("[::1]", false)
    @Test fun notPublic() = check("8.8.8.8", false)
    @Test fun not172_15() = check("172.15.0.1", false)
    @Test fun not172_32() = check("172.32.0.1", false)
    @Test fun notBelowCgnat() = check("100.63.255.255", false)
    @Test fun notAboveCgnat() = check("100.128.0.1", false)
    @Test fun notPublicResolvedName() = check("example.com", false)
    @Test fun notUnresolvableName() = check("missing.example", false)

    @Test fun literalsAndDotLocalAndLocalhostNeverLookUp() = runTest {
        for (h in listOf("10.0.2.2", "8.8.8.8", "[fe80::1]", "nas.local", "localhost")) classifier.isLocal(h)
        assertEquals(0, resolver.calls.get())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aHostnameLookupIsAbandonedAfterTheTimeout() = runTest {
        val never = HostResolver { awaitCancellation() }
        val slow = LocalHostClassifier(never, timeoutMs = 3_000)
        assertFalse(slow.isLocal("slow.example"))
        assertEquals("virtual time spent", 3_000L, testScheduler.currentTime)
    }

    @Test fun askNowForAllEightCombinations() {
        for (sdk in listOf(36, 37)) for (local in listOf(false, true)) for (granted in listOf(false, true)) {
            val expected = sdk >= 37 && local && !granted
            assertEquals("sdk=$sdk local=$local granted=$granted", expected, LocalNetwork.shouldAsk(sdk, local, granted))
        }
        assertFalse(LocalNetwork.shouldAsk(36, true, false))
        assertTrue(LocalNetwork.shouldAsk(37, true, false))
    }
}
