package com.podclatch.domain

import com.podclatch.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/** The pure rules of the `ACCESS_LOCAL_NETWORK` permission (Android 17, API 37). */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The permission exists, and the OS drops connections to the local network without it, from this API level. */
    const val MIN_SDK = 37

    /** The one rule for asking: API 37 or later, a local host, and the permission not yet granted. */
    fun shouldAsk(sdkInt: Int, hostIsLocal: Boolean, permissionGranted: Boolean): Boolean =
        sdkInt >= MIN_SDK && hostIsLocal && !permissionGranted

    /**
     * The address of a literal host ([host] may be in brackets for IPv6) as bytes, or null when it is a name.
     * Only literals are parsed, so this never does a lookup.
     */
    fun literalAddress(host: String): ByteArray? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }) {
            val numbers = parts.map { it.toInt() }
            if (numbers.any { it > 255 }) return null
            return ByteArray(4) { numbers[it].toByte() }
        }
        if (':' in h && h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return try {
                InetAddress.getByName(h).address // a literal: no lookup
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /** True for private, link-local, unique-local, and carrier-grade NAT addresses; loopback is not local. */
    fun isLocalAddress(address: ByteArray): Boolean {
        val b = address.map { it.toInt() and 0xFF }
        return when (b.size) {
            4 -> b[0] == 10 ||
                (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254) ||
                (b[0] == 100 && b[1] in 64..127)
            16 -> (b[0] == 0xFE && (b[1] and 0xC0) == 0x80) || (b[0] and 0xFE) == 0xFC
            else -> false
        }
    }
}

/** Looks a name up. Injected so tests need no network; may suspend, and a lookup is abandoned after the timeout. */
fun interface HostResolver {
    suspend fun resolve(host: String): List<ByteArray>
}

/** The system resolver, run off the calling thread; abandoning the wait does not block the caller. */
object SystemHostResolver : HostResolver {
    override suspend fun resolve(host: String): List<ByteArray> =
        CoroutineScope(Dispatchers.IO).async { InetAddress.getAllByName(host).map { it.address } }.await()
}

/** Decides whether a host is on the local network (decision 2 of #55). */
class LocalHostClassifier(
    private val resolver: HostResolver,
    private val timeoutMs: Long = Config.LOCAL_NETWORK_LOOKUP_TIMEOUT_MS,
) {
    suspend fun isLocal(host: String): Boolean {
        val name = host.trim().trimEnd('.').lowercase()
        if (name.isEmpty()) return false
        LocalNetwork.literalAddress(name)?.let { return LocalNetwork.isLocalAddress(it) }
        if (name.endsWith(".local")) return true
        if (name == "localhost") return false
        val addresses = try {
            withTimeoutOrNull(timeoutMs) { resolver.resolve(name) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false
        return addresses.any { LocalNetwork.isLocalAddress(it) }
    }
}

/** What the platform says about the permission; a thin Android wrapper in the app, a fake in tests. */
interface LocalNetworkAccess {
    val sdkInt: Int
    fun isGranted(): Boolean
}

/** Where the permission flow of one connection form is. */
sealed interface LocalNetworkPhase {
    data object Idle : LocalNetworkPhase

    /** The rationale dialog is showing for [hostPort]. */
    data class Rationale(val hostPort: String) : LocalNetworkPhase

    /** The system request is wanted; [launched] turns true once the screen has shown it. */
    data class Requesting(val launched: Boolean) : LocalNetworkPhase

    /** The user refused. [canAskAgain] is false when the system will not show the request any more. */
    data class Refused(val canAskAgain: Boolean) : LocalNetworkPhase
}

/**
 * The permission flow of Test connection as a state machine, free of Android types. [proceed] runs the
 * actual connection test; it runs right away when nothing is to be asked, and after a grant.
 */
class LocalNetworkFlow(
    private val scope: CoroutineScope,
    private val access: LocalNetworkAccess,
    private val classifier: LocalHostClassifier,
    private val proceed: () -> Unit,
) {
    private val _phase = MutableStateFlow<LocalNetworkPhase>(LocalNetworkPhase.Idle)
    val phase: StateFlow<LocalNetworkPhase> = _phase.asStateFlow()

    private val settingsRequests = Channel<Unit>(Channel.UNLIMITED)

    /** Emits once each time the screen should open the app's settings page. */
    val openSettings: Flow<Unit> = settingsRequests.receiveAsFlow()

    /**
     * Test connection was tapped for the (valid) server [host] shown as [hostPort]. Asks first when the rules
     * say so; otherwise runs the test. A null [host] (not a valid address) just runs the test, which says why.
     * [onDecided] runs just before either outcome takes effect, with whether the user is to be asked.
     */
    fun begin(host: String?, hostPort: String?, onDecided: (asked: Boolean) -> Unit = {}) {
        scope.launch {
            // The cheap checks come first, so no lookup happens when nothing could be asked anyway.
            val ask = host != null && hostPort != null &&
                access.sdkInt >= LocalNetwork.MIN_SDK && !access.isGranted() &&
                LocalNetwork.shouldAsk(access.sdkInt, classifier.isLocal(host), access.isGranted())
            onDecided(ask)
            if (ask) _phase.value = LocalNetworkPhase.Rationale(hostPort!!) else proceed()
        }
    }

    fun cancel() = _phase.update { LocalNetworkPhase.Idle }

    fun continueToRequest() = _phase.update { LocalNetworkPhase.Requesting(launched = false) }

    fun requestLaunched() = _phase.update { if (it is LocalNetworkPhase.Requesting) it.copy(launched = true) else it }

    /** The system answered. [canAskAgain] is whether the system would show the request once more. */
    fun onResult(granted: Boolean, canAskAgain: Boolean) {
        if (_phase.value !is LocalNetworkPhase.Requesting) return
        if (granted) {
            _phase.value = LocalNetworkPhase.Idle
            proceed()
        } else {
            _phase.value = LocalNetworkPhase.Refused(canAskAgain)
        }
    }

    /** Asks again while the system still shows the request; otherwise opens the app's settings page. */
    fun retry() {
        val refused = _phase.value as? LocalNetworkPhase.Refused ?: return
        if (refused.canAskAgain) {
            _phase.value = LocalNetworkPhase.Requesting(launched = false)
        } else {
            settingsRequests.trySend(Unit)
        }
    }

    fun openAppSettings() {
        settingsRequests.trySend(Unit)
    }

    /** An edit of the form makes a refusal stale. */
    fun reset() = _phase.update { LocalNetworkPhase.Idle }
}
