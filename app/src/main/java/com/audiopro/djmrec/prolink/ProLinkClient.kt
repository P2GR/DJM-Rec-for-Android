package com.audiopro.djmrec.prolink

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.*
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class LinkNetwork(val id: String, val label: String, val network: Network,
                       val address: Inet4Address, val prefix: Int, val interfaceName: String)

/** Owns selected-LAN sockets only; never rebinds the process away from livestream internet. */
class ProLinkClient(context: Context, private val observer: WireObserver = WireObserver.NONE) : DjLinkSource {
    private val context = context.applicationContext
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(DjLinkState())
    override val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var session = 0L
    private val preferences = context.getSharedPreferences("prolink", Context.MODE_PRIVATE)
    private val mutableOptions = MutableStateFlow(NowPlayingOptions(
        automaticMarkers = preferences.getBoolean("markers", false),
        requireOnAir = preferences.getBoolean("onAir", true),
        bannerEnabled = preferences.getBoolean("banner", false),
        position = if (preferences.getBoolean("top", false)) BannerPosition.TOP else BannerPosition.BOTTOM,
        prefix = preferences.getString("prefix", "Now playing") ?: "Now playing",
        showArtist = preferences.getBoolean("artist", true),
        lightBackground = preferences.getBoolean("light", false)
    ))
    val options = mutableOptions.asStateFlow()
    fun setOptions(value: NowPlayingOptions) {
        val bounded = value.copy(prefix = value.prefix.take(40))
        mutableOptions.value = bounded
        preferences.edit().putBoolean("markers", bounded.automaticMarkers).putBoolean("onAir", bounded.requireOnAir)
            .putBoolean("banner", bounded.bannerEnabled).putBoolean("top", bounded.position == BannerPosition.TOP)
            .putString("prefix", bounded.prefix).putBoolean("artist", bounded.showArtist)
            .putBoolean("light", bounded.lightBackground).apply()
    }

    fun networks(): List<LinkNetwork> = connectivity.allNetworks.flatMap { network ->
        val capabilities = connectivity.getNetworkCapabilities(network)
        val properties = connectivity.getLinkProperties(network)
        if (capabilities == null || properties == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))) emptyList()
        else properties.linkAddresses.mapNotNull { link ->
            val ip = link.address as? Inet4Address ?: return@mapNotNull null
            val name = properties.interfaceName ?: return@mapNotNull null
            LinkNetwork("$network/${ip.hostAddress}", "$name · ${ip.hostAddress}", network, ip, link.prefixLength, name)
        }
    }

    @Synchronized override fun disconnect() {
        session++
        job?.cancel(); job = null
        mutableState.value = DjLinkState()
    }

    @Synchronized fun connect(selection: LinkNetwork, macOverride: String = "") {
        val previous = job
        disconnect()
        val generation = session
        mutableState.value = DjLinkState(LinkStatus.DISCOVERING, "Listening for players on ${selection.label}…")
        job = scope.launch {
            previous?.join()
            fun publish(value: DjLinkState) = synchronized(this@ProLinkClient) {
                if (generation == session) mutableState.value = value
            }
            val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
            val udp = mutableListOf<DatagramSocket>()
            val metadataExecutor = Executors.newSingleThreadExecutor()
            val deadlines = Executors.newSingleThreadScheduledExecutor()
            var wifiLock: WifiManager.MulticastLock? = null
            try {
                val override = macOverride.trim()
                val mac = if (override.isEmpty()) runCatching { NetworkInterface.getByName(selection.interfaceName)?.hardwareAddress }.getOrNull()
                    else parseMac(override)
                // Android often hides adapter MACs. Do not invent a hardware address in an announcement.
                val usableMac = mac?.takeIf { it.size == 6 && it.any { byte -> byte != 0.toByte() } && it[0].toInt() and 1 == 0 &&
                    !it.contentEquals(byteArrayOf(2, 0, 0, 0, 0, 0)) }
                require(override.isEmpty() || usableMac != null) { "Enter the adapter's current unicast MAC address" }
                require(selection.prefix in 1..30) { "Selected LAN has no IPv4 broadcast subnet" }
                wifiLock = context.getSystemService(WifiManager::class.java)?.createMulticastLock("DjmProLink")?.apply {
                    setReferenceCounted(false); acquire()
                }
                for (port in if (com.audiopro.djmrec.BuildConfig.PROTOCOL_RESEARCH) listOf(50000, 50002, 50001) else listOf(50000, 50002)) {
                    val socket = DatagramSocket(null)
                    udp.add(socket)
                    socket.reuseAddress = false
                    selection.network.bindSocket(socket)
                    socket.broadcast = true
                    socket.bind(InetSocketAddress(port))
                    socket.soTimeout = 25
                }
                val broadcast = broadcast(selection.address, selection.prefix)
                val localAddresses = connectivity.getLinkProperties(selection.network)?.linkAddresses
                    ?.map { it.address }?.toSet().orEmpty() + selection.address
                val devices = mutableMapOf<Int, LinkDevice>()
                val decks = mutableMapOf<Int, DeckState>()
                val loadedAt = mutableMapOf<Int, Long>()
                val retryAt = mutableMapOf<Int, Long>()
                var loadSequence = 0L
                var ourNumber: Int? = null
                val started = SystemClock.elapsedRealtime()
                var announced = 0L
                data class Pending(val deck: Int, val key: TrackKey, val load: Long, val result: Future<TrackMetadata?>)
                var pending: Pending? = null
                val db = RemoteDbClient(observer) { address, port ->
                    Socket().also { socket ->
                        sockets.add(socket)
                        try {
                            selection.network.bindSocket(socket)
                            socket.bind(InetSocketAddress(selection.address, 0))
                            socket.soTimeout = 1_000
                            deadlines.schedule({ runCatching { socket.close() }; sockets.remove(socket) }, 4, TimeUnit.SECONDS)
                            socket.connect(InetSocketAddress(address, port), 1_000)
                        } catch (error: Exception) { socket.close(); sockets.remove(socket); throw error }
                    }
                }
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    check(connectivity.getLinkProperties(selection.network)?.linkAddresses?.any { it.address == selection.address } == true) {
                        "Selected LAN disconnected. Reconnect, refresh networks, then connect again."
                    }
                    udp.forEachIndexed { index, socket ->
                        val capacity = if (com.audiopro.djmrec.BuildConfig.PROTOCOL_RESEARCH) 65535 else 2048
                        val packet = DatagramPacket(ByteArray(capacity), capacity)
                        try { socket.receive(packet) } catch (_: SocketTimeoutException) { return@forEachIndexed }
                        if (packet.address in localAddresses || !sameSubnet(selection.address, packet.address, selection.prefix)) return@forEachIndexed
                        val data = packet.data.copyOf(packet.length)
                        observer.packet("udp", "in", packet.address.hostAddress!!, socket.localPort, data)
                        if (index == 2) return@forEachIndexed
                        if (index == 0) {
                            check(ourNumber == null || !ProLinkPackets.conflicts(data, ourNumber)) {
                                "Player number conflict. Link stopped; reconnect after all players finish starting."
                            }
                            ProLinkPackets.device(data, packet.address.hostAddress!!, now)?.let { device ->
                                val old = devices[device.number]
                                if (old != null && old.address != device.address) error("Duplicate player numbers detected. Set unique player numbers and reconnect.")
                                devices[device.number] = device
                            }
                        } else {
                            val deck = ProLinkPackets.deck(data, now) ?: return@forEachIndexed
                            val device = devices[deck.number] ?: return@forEachIndexed
                            if (device.address != packet.address.hostAddress || !device.name.startsWith("CDJ-2000")) return@forEachIndexed
                            val old = decks[deck.number]
                            if (old != null && !ProLinkPackets.newer(deck.packetCounter, old.packetCounter)) return@forEachIndexed
                            val reload = deck.loading && old?.loading != true
                            if (old?.track != deck.track || reload) {
                                loadedAt[deck.number] = ++loadSequence
                                retryAt.remove(deck.number)
                            }
                            decks[deck.number] = if (old != null && old.track == deck.track && !reload) deck.copy(metadata = old.metadata, metadataMessage = old.metadataMessage) else deck
                            // Media IDs can be reused after replacing a USB/SD. Invalidate every deck using that slot.
                            val changedSlots = buildSet {
                                if (old != null && old.usbMounted != deck.usbMounted) add(3)
                                if (old != null && old.sdMounted != deck.sdMounted) add(2)
                            }
                            decks.values.filter { it.track?.sourcePlayer == deck.number && it.track.slot in changedSlots }.forEach { affected ->
                                decks[affected.number] = affected.copy(metadata = null, metadataMessage = "Media changed; refreshing metadata")
                                loadedAt[affected.number] = ++loadSequence
                                retryAt.remove(affected.number)
                            }
                        }
                    }
                    val expired = devices.values.filter { now - it.seenAt > 10_000 }.map { it.number }
                    expired.forEach { source ->
                        devices.remove(source)
                        decks.entries.removeAll { it.key == source || it.value.track?.sourcePlayer == source }
                    }
                    decks.entries.removeAll { now - it.value.seenAt > 3_000 }
                    if (ourNumber == null && usableMac != null && now - started >= 4_000 && devices.isNotEmpty()) {
                        ourNumber = (1..4).firstOrNull { it !in devices } ?: error("All player numbers 1–4 are occupied; metadata needs a free number.")
                    }
                    if (ourNumber != null && now - announced >= 1_500) {
                        val data = ProLinkPackets.keepAlive(ourNumber, selection.address.address, usableMac!!)
                        udp[0].send(DatagramPacket(data, data.size, broadcast, 50000))
                        observer.packet("udp", "out", broadcast.hostAddress!!, 50000, data)
                        announced = now
                    }
                    pending?.takeIf { it.result.isDone }?.let { work ->
                        val current = decks[work.deck]
                        val result = runCatching { work.result.get() }.getOrNull()
                        if (current?.track == work.key && loadedAt[work.deck] == work.load) {
                            decks[work.deck] = current.copy(metadata = result,
                                metadataMessage = if (result == null) "Metadata unavailable; retrying" else "Metadata received")
                            retryAt[work.deck] = now + 10_000
                        }
                        pending = null
                    }
                    if (pending == null && ourNumber != null) {
                        val next = decks.values.firstOrNull { it.track != null && it.metadata == null && now >= (retryAt[it.number] ?: 0) }
                        val key = next?.track
                        if (next != null && key != null) {
                            val source = devices[key.sourcePlayer]
                            if (source != null && key.slot in 1..4 && key.type in setOf(1, 2, 5)) {
                                val number = ourNumber
                                pending = Pending(next.number, key, loadedAt[next.number] ?: 0,
                                    metadataExecutor.submit<TrackMetadata?> { db.query(source.address, number, key) })
                            } else {
                                decks[next.number] = next.copy(metadataMessage = "Source absent or media type unsupported")
                                retryAt[next.number] = now + 10_000
                            }
                        }
                    }
                    publish(DjLinkState(if (decks.isEmpty()) LinkStatus.DISCOVERING else LinkStatus.CONNECTED,
                        if (usableMac == null) "${devices.size} devices detected. Android hides the adapter MAC: disconnect, enter it in Adapter MAC settings, then reconnect."
                        else if (ourNumber == null) "Listening on ${selection.label}; no player slot claimed yet."
                        else "Player $ourNumber · ${devices.size} devices · ${decks.size} deck states",
                        devices.values.sortedBy { it.number }, decks.values.sortedBy { it.number }.map {
                            it.copy(loadGeneration = loadedAt[it.number] ?: 0)
                        }))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { publish(DjLinkState(LinkStatus.ERROR,
                if (error is BindException) "Pro DJ Link ports are in use. Close other Link apps and retry."
                else error.message ?: "Pro DJ Link connection failed")) }
            finally {
                udp.forEach { it.close() }
                synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
                metadataExecutor.shutdownNow(); deadlines.shutdownNow()
                wifiLock?.let { if (it.isHeld) it.release() }
            }
        }
    }

    companion object {
        fun parseMac(value: String): ByteArray {
            require(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(value)) { "Use a MAC such as 12:34:56:78:9A:BC" }
            return value.split(':').map { it.toInt(16).toByte() }.toByteArray()
        }
        private fun ipv4(address: InetAddress): Long = address.address.fold(0L) { n, byte -> (n shl 8) or (byte.toLong() and 255) }
        private fun mask(prefix: Int) = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        internal fun sameSubnet(local: Inet4Address, other: InetAddress, prefix: Int) =
            other is Inet4Address && ipv4(local) and mask(prefix) == ipv4(other) and mask(prefix)
        internal fun broadcast(local: Inet4Address, prefix: Int): InetAddress {
            val value = ipv4(local) or (mask(prefix) xor 0xffffffffL)
            return InetAddress.getByAddress(ByteArray(4) { (value shr ((3 - it) * 8)).toByte() })
        }
    }
}
