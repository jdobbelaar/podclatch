package com.podclatch

import com.podclatch.domain.HostResolver
import com.podclatch.domain.LocalHostClassifier
import com.podclatch.domain.LocalNetworkAccess
import com.podclatch.domain.LocalNetworkFlow
import com.podclatch.domain.LocalNetworkPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LocalNetworkFlowTest {
    private class FakeAccess(override val sdkInt: Int, val granted: Boolean = false) : LocalNetworkAccess {
        override fun isGranted() = granted
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tests = AtomicInteger()
    private val decided = AtomicInteger()

    @After fun tearDown() = scope.cancel()

    private fun flow(sdk: Int = 37, granted: Boolean = false) =
        LocalNetworkFlow(scope, FakeAccess(sdk, granted), LocalHostClassifier(HostResolver { emptyList() }), proceed = { tests.incrementAndGet() })

    /** Begins and waits until the flow has decided (asked or ran the test). */
    private fun LocalNetworkFlow.start(host: String?, hostPort: String? = host) {
        val before = decided.get()
        begin(host, hostPort) { decided.incrementAndGet() }
        awaitCondition("decision made") { decided.get() > before }
    }

    private fun LocalNetworkFlow.await(what: String, c: (LocalNetworkPhase) -> Boolean) = awaitState(phase, what, condition = c)

    @Test fun localHostWithoutPermissionAsksFirstAndTestsOnlyAfterAGrant() {
        val f = flow()
        f.start("10.0.2.2", "10.0.2.2:8180")
        f.await("rationale") { it == LocalNetworkPhase.Rationale("10.0.2.2:8180") }
        assertEquals(0, tests.get())
        f.continueToRequest()
        f.await("requesting") { it == LocalNetworkPhase.Requesting(launched = false) }
        f.requestLaunched()
        f.await("launched") { it == LocalNetworkPhase.Requesting(launched = true) }
        f.onResult(granted = true, canAskAgain = false)
        f.await("idle") { it == LocalNetworkPhase.Idle }
        awaitCondition("test ran once") { tests.get() == 1 }
    }

    @Test fun cancelRunsNothingAndLeavesTheFlowIdle() {
        val f = flow()
        f.start("192.168.1.20", "192.168.1.20:8180")
        f.await("rationale") { it is LocalNetworkPhase.Rationale }
        f.cancel()
        f.await("idle") { it == LocalNetworkPhase.Idle }
        assertEquals(0, tests.get())
    }

    @Test fun refusalOffersTryAgainWhichAsksAgainAndASecondRefusalLeadsToSettings() {
        val f = flow()
        f.start("10.0.2.2")
        f.continueToRequest()
        f.onResult(granted = false, canAskAgain = true)
        f.await("refused, can ask again") { it == LocalNetworkPhase.Refused(canAskAgain = true) }
        f.retry()
        f.await("requesting again") { it == LocalNetworkPhase.Requesting(launched = false) }
        f.onResult(granted = false, canAskAgain = false)
        f.await("refused for good") { it == LocalNetworkPhase.Refused(canAskAgain = false) }
        f.retry()
        // Try again opens the settings page instead of asking, and stays refused.
        runBlocking<Unit> { withTimeout(AWAIT_TIMEOUT_MS) { f.openSettings.first() } }
        assertEquals(LocalNetworkPhase.Refused(canAskAgain = false), f.phase.value)
        assertEquals(0, tests.get())
    }

    @Test fun openAppSettingsEmitsFromARefusal() {
        val f = flow()
        f.start("10.0.2.2")
        f.continueToRequest()
        f.onResult(granted = false, canAskAgain = true)
        f.openAppSettings()
        runBlocking<Unit> { withTimeout(AWAIT_TIMEOUT_MS) { f.openSettings.first() } }
    }

    @Test fun anEditAfterARefusalClearsIt() {
        val f = flow()
        f.start("10.0.2.2")
        f.continueToRequest()
        f.onResult(granted = false, canAskAgain = true)
        f.reset()
        f.await("idle") { it == LocalNetworkPhase.Idle }
    }

    @Test fun aResultWithoutARequestIsIgnored() {
        val f = flow()
        f.onResult(granted = true, canAskAgain = false)
        assertEquals(LocalNetworkPhase.Idle, f.phase.value)
        assertEquals(0, tests.get())
    }

    private fun assertRunsAtOnce(f: LocalNetworkFlow, host: String?) {
        val before = tests.get()
        f.start(host)
        awaitCondition("test ran for $host") { tests.get() == before + 1 }
        assertEquals(LocalNetworkPhase.Idle, f.phase.value)
    }

    @Test fun loopbackPublicAndInvalidHostsNeverAsk() {
        val f = flow()
        for (h in listOf("127.0.0.1", "localhost", "[::1]", "8.8.8.8", "example.com", null)) assertRunsAtOnce(f, h)
    }

    @Test fun apiLevel36NeverAsksEvenForALocalHost() = assertRunsAtOnce(flow(sdk = 36), "10.0.2.2")

    @Test fun aGrantedPermissionRunsTheTestAtOnce() = assertRunsAtOnce(flow(granted = true), "192.168.1.20")
}
