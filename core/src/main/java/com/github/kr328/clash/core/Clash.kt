package com.github.kr328.clash.core

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.core.util.parseInetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress

object Clash {
    enum class OverrideSlot {
        Persist, Session
    }

    private val ConfigurationOverrideJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val ProbTestJson = Json {
        ignoreUnknownKeys = true
    }

    private val StringListSerializer = ListSerializer(String.serializer())

    fun reset() {
        Bridge.nativeReset()
    }

    fun forceGc() {
        Bridge.nativeForceGc()
    }

    fun suspendCore(suspended: Boolean) {
        Bridge.nativeSuspend(suspended)
    }

    fun queryTunnelState(): TunnelState {
        val json = Bridge.nativeQueryTunnelState()

        return Json.decodeFromString(TunnelState.serializer(), json)
    }

    fun queryTrafficNow(): Traffic {
        return Bridge.nativeQueryTrafficNow()
    }

    fun queryTrafficTotal(): Traffic {
        return Bridge.nativeQueryTrafficTotal()
    }

    fun notifyDnsChanged(dns: List<String>) {
        Bridge.nativeNotifyDnsChanged(dns.toSet().joinToString(separator = ","))
    }

    fun notifyTimeZoneChanged(name: String, offset: Int) {
        Bridge.nativeNotifyTimeZoneChanged(name, offset)
    }

    fun notifyInstalledAppsChanged(uids: List<Pair<Int, String>>) {
        val uidList = uids.joinToString(separator = ",") { "${it.first}:${it.second}" }

        Bridge.nativeNotifyInstalledAppChanged(uidList)
    }

    fun startTun(
        fd: Int,
        stack: String,
        gateway: String,
        portal: String,
        dns: String,
        markSocket: (Int) -> Boolean,
        querySocketUid: (protocol: Int, source: InetSocketAddress, target: InetSocketAddress) -> Int
    ) {
        Bridge.nativeStartTun(fd, stack, gateway, portal, dns, object : TunInterface {
            override fun markSocket(fd: Int) {
                markSocket(fd)
            }

            override fun querySocketUid(protocol: Int, source: String, target: String): Int {
                return querySocketUid(
                    protocol,
                    parseInetSocketAddress(source),
                    parseInetSocketAddress(target)
                )
            }
        })
    }

    fun stopTun() {
        Bridge.nativeStopTun()
    }

    fun startHttp(listenAt: String): String? {
        return Bridge.nativeStartHttp(listenAt)
    }

    fun stopHttp() {
        Bridge.nativeStopHttp()
    }

    fun queryGroupNames(excludeNotSelectable: Boolean): List<String> {
        val names = Json.Default.decodeFromString(
            JsonArray.serializer(),
            Bridge.nativeQueryGroupNames(excludeNotSelectable)
        )

        return names.map {
            require(it.jsonPrimitive.isString)

            it.jsonPrimitive.content
        }
    }

    fun queryGroup(name: String, sort: ProxySort): ProxyGroup {
        return Bridge.nativeQueryGroup(name, sort.name)
            ?.let { Json.Default.decodeFromString(ProxyGroup.serializer(), it) }
            ?: ProxyGroup("Unknown", emptyList(), "")
    }

    fun healthCheck(name: String): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeHealthCheck(this, name)
        }
    }

    fun healthCheckAll() {
        Bridge.nativeHealthCheckAll()
    }

    fun patchSelector(selector: String, name: String): Boolean {
        return Bridge.nativePatchSelector(selector, name)
    }

    fun fetchAndValid(
        path: File,
        url: String,
        force: Boolean,
        reportStatus: (FetchStatus) -> Unit
    ): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeFetchAndValid(
                object : FetchCallback {
                    override fun report(statusJson: String) {
                        reportStatus(
                            Json.Default.decodeFromString(
                                FetchStatus.serializer(),
                                statusJson
                            )
                        )
                    }

                    override fun complete(error: String?) {
                        if (error != null)
                            completeExceptionally(ClashException(error))
                        else
                            complete(Unit)
                    }
                },
                path.absolutePath,
                url,
                force
            )
        }
    }

    fun load(path: File): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeLoad(this, path.absolutePath)
        }
    }

    /**
     * Probe candidate subscriptions and keep only the nodes that passed every round.
     *
     * The candidates are merged natively before probing: their `proxies` lists are
     * concatenated, colliding names are renamed so nothing is lost, and the first
     * candidate that carries `proxy-groups`/`rules` supplies the routing skeleton of
     * the published configuration.
     *
     * Proxies are constructed through `adapter.ParseProxy` and `hub/executor` is
     * never called, so the live core is never reconfigured and the currently running
     * proxy is untouched for the whole run. The call blocks inside the core for
     * [ProbTestOptions.rounds] rounds spaced [ProbTestOptions.roundGapMs] apart, which
     * is why it is dispatched to [Dispatchers.IO] rather than allowed to stall the
     * caller. [onProgress] receives one event per round.
     */
    suspend fun probTest(
        candidates: List<String>,
        options: ProbTestOptions = ProbTestOptions(),
        onProgress: (ProbTestProgress) -> Unit = {}
    ): ProbTestEnvelope = withContext(Dispatchers.IO) {
        val json = Bridge.nativeProbTest(
            object : FetchCallback {
                override fun report(statusJson: String) {
                    try {
                        onProgress(ProbTestJson.decodeFromString(ProbTestProgress.serializer(), statusJson))
                    } catch (e: Exception) {
                        Log.w("Invalid probtest progress", e)
                    }
                }

                override fun complete(error: String?) = Unit
            },
            ProbTestJson.encodeToString(StringListSerializer, candidates),
            ProbTestJson.encodeToString(ProbTestOptions.serializer(), options)
        )

        ProbTestJson.decodeFromString(ProbTestEnvelope.serializer(), json)
    }

    /**
     * Measure every leaf node of a live group through the real transfer gate
     * and rank what survived.
     *
     * Unlike [probTest] the candidates are the proxies the core is already
     * running: the same adapters the connection uses are the ones measured, so
     * a node that passes here is one the reader just watched work. The call
     * blocks inside the core for [SpeedTestOptions.rounds] rounds per node,
     * which is why it is dispatched to [Dispatchers.IO]. [onProgress] receives
     * one event per node finished.
     */
    suspend fun speedTestGroup(
        group: String,
        options: SpeedTestOptions = SpeedTestOptions(),
        onProgress: (SpeedTestProgress) -> Unit = {}
    ): SpeedTestEnvelope = withContext(Dispatchers.IO) {
        val json = Bridge.nativeSpeedTestGroup(
            object : FetchCallback {
                override fun report(statusJson: String) {
                    try {
                        onProgress(
                            SpeedTestJson.decodeFromString(
                                SpeedTestProgress.serializer(),
                                statusJson
                            )
                        )
                    } catch (e: Exception) {
                        Log.w("Invalid speed test progress", e)
                    }
                }

                override fun complete(error: String?) = Unit
            },
            group,
            SpeedTestJson.encodeToString(SpeedTestOptions.serializer(), options)
        )

        SpeedTestJson.decodeFromString(SpeedTestEnvelope.serializer(), json)
    }

    /**
     * Snapshot the traffic the core is carrying through the leaf nodes of
     * one live group.
     *
     * Free: the core already counts the bytes of every open connection and
     * already names the chain of nodes behind it, so the watch of the node
     * in use asks a question the core has already paid for. The per-node
     * numbers are cumulative since the core first looked at that node, so
     * subtracting a previous snapshot gives the bytes that moved over the
     * window between the two reads.
     */
    fun queryNodeTraffic(group: String): NodeTrafficEnvelope {
        val json = Bridge.nativeMonitorGroup(group)

        return SpeedTestJson.decodeFromString(NodeTrafficEnvelope.serializer(), json)
    }

    fun queryProviders(): List<Provider> {
        val providers =
            Json.Default.decodeFromString(JsonArray.serializer(), Bridge.nativeQueryProviders())

        return List(providers.size) {
            Json.Default.decodeFromJsonElement(Provider.serializer(), providers[it])
        }
    }

    fun updateProvider(type: Provider.Type, name: String): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeUpdateProvider(this, type.toString(), name)
        }
    }

    fun queryOverride(slot: OverrideSlot): ConfigurationOverride {
        return try {
            ConfigurationOverrideJson.decodeFromString(
                ConfigurationOverride.serializer(),
                Bridge.nativeReadOverride(slot.ordinal)
            )
        } catch (e: Exception) {
            ConfigurationOverride()
        }
    }

    fun patchOverride(slot: OverrideSlot, configuration: ConfigurationOverride) {
        Bridge.nativeWriteOverride(
            slot.ordinal,
            ConfigurationOverrideJson.encodeToString(
                ConfigurationOverride.serializer(),
                configuration
            )
        )
    }

    fun clearOverride(slot: OverrideSlot) {
        Bridge.nativeClearOverride(slot.ordinal)
    }

    fun queryConfiguration(): UiConfiguration {
        return Json.Default.decodeFromString(
            UiConfiguration.serializer(),
            Bridge.nativeQueryConfiguration()
        )
    }

    fun subscribeLogcat(): ReceiveChannel<LogMessage> {
        return Channel<LogMessage>(32).apply {
            Bridge.nativeSubscribeLogcat(object : LogcatInterface {
                override fun received(jsonPayload: String) {
                    trySend(Json.decodeFromString(LogMessage.serializer(), jsonPayload))
                }
            })
        }
    }

    fun setAgeSecretKey(key: String?) {
        Bridge.nativeSetAgeSecretKey(key)
    }

    fun genX25519KeyPair(): AgeKeyPair {
        return parseAgeKeyPair(checkNotNull(Bridge.nativeGenX25519KeyPair()))
    }

    fun genHybridKeyPair(): AgeKeyPair {
        return parseAgeKeyPair(checkNotNull(Bridge.nativeGenHybridKeyPair()))
    }

    fun veritySecretKeys(vararg secretKeys: String): Boolean {
        return Bridge.nativeVeritySecretKeys(secretKeys.firstOrNull() ?: "")
    }

    fun toPublicKeys(vararg secretKeys: String): List<String> {
        return Bridge.nativeToPublicKeys(secretKeys.firstOrNull() ?: "")
            ?.let { Json.Default.decodeFromString(ListSerializer(String.serializer()), it) }
            ?: emptyList()
    }

    fun verityPublicKeys(vararg publicKeys: String): Boolean {
        return Bridge.nativeVerityPublicKeys(publicKeys.firstOrNull() ?: "")
    }

    private fun parseAgeKeyPair(value: String): AgeKeyPair {
        return Json.Default.decodeFromString(AgeKeyPair.serializer(), value)
    }
}