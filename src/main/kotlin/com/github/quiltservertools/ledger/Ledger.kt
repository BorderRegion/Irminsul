package com.github.quiltservertools.ledger

import com.github.quiltservertools.ledger.actionutils.ActionSearchParams
import com.github.quiltservertools.ledger.actionutils.Preview
import com.github.quiltservertools.ledger.actionutils.SearchResults
import com.github.quiltservertools.ledger.api.ExtensionManager
import com.github.quiltservertools.ledger.api.LedgerApi
import com.github.quiltservertools.ledger.api.LedgerApiImpl
import com.github.quiltservertools.ledger.commands.registerCommands
import com.github.quiltservertools.ledger.config.CONFIG_PATH
import com.github.quiltservertools.ledger.config.DatabaseSpec
import com.github.quiltservertools.ledger.database.ActionQueueService
import com.github.quiltservertools.ledger.database.DatabaseManager
import com.github.quiltservertools.ledger.listeners.registerBlockListeners
import com.github.quiltservertools.ledger.listeners.registerEntityListeners
import com.github.quiltservertools.ledger.listeners.registerPlayerListeners
import com.github.quiltservertools.ledger.listeners.registerWorldEventListeners
import com.github.quiltservertools.ledger.network.Networking
import com.github.quiltservertools.ledger.network.packet.action.ActionS2CPacket
import com.github.quiltservertools.ledger.network.packet.handshake.HandshakeS2CPacket
import com.github.quiltservertools.ledger.network.packet.response.ResponseS2CPacket
import com.github.quiltservertools.ledger.registry.ActionRegistry
import com.uchuhimo.konf.Config
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.sql.vendors.SQLiteDialect
import java.nio.file.Files
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import com.github.quiltservertools.ledger.config.config as realConfig

object Ledger : DedicatedServerModInitializer, CoroutineScope {
    const val MOD_ID = "ledger"
    const val SEARCH_RESULT_PREFETCH_PAGES = 10
    private const val SEARCH_RESULT_CACHE_PAGES = SEARCH_RESULT_PREFETCH_PAGES * 2
    private const val SEARCH_RESULT_CACHE_TTL_NANOS = 30_000_000_000L
    val DEFAULT_DATABASE = SQLiteDialect.dialectName

    @JvmStatic
    val api: LedgerApi = LedgerApiImpl

    val logger: Logger = LogManager.getLogger("Ledger")
    lateinit var config: Config
    lateinit var server: MinecraftServer
    val searchCache = ConcurrentHashMap<String, ActionSearchParams>()
    private val searchResultsCache = ConcurrentHashMap<String, CachedSearchResults>()

    @JvmField // Required for mixin access
    val previewCache = ConcurrentHashMap<UUID, Preview>()
    override val coroutineContext: CoroutineContext = Dispatchers.Default + CoroutineName("Ledger")

    private data class CachedSearchResults(
        val params: ActionSearchParams,
        val pages: Map<Int, SearchResults>,
        val cachedAtNanos: Long
    ) {
        fun isFresh(): Boolean = System.nanoTime() - cachedAtNanos < SEARCH_RESULT_CACHE_TTL_NANOS
    }

    fun clearSearchResults(sourceName: String) {
        searchResultsCache.remove(sourceName)
    }

    fun clearAllSearchResults() {
        searchResultsCache.clear()
    }

    fun cacheSearchResults(sourceName: String, params: ActionSearchParams, results: Collection<SearchResults>) {
        if (results.isEmpty()) return
        searchResultsCache.compute(sourceName) { _, previous ->
            val pages = if (previous?.params === params && previous.isFresh()) {
                previous.pages.toMutableMap()
            } else {
                mutableMapOf()
            }
            results.forEach { result ->
                pages.remove(result.page)
                pages[result.page] = result
            }
            while (pages.size > SEARCH_RESULT_CACHE_PAGES) {
                pages.remove(pages.keys.first())
            }
            CachedSearchResults(params, pages, System.nanoTime())
        }
    }

    fun getCachedSearchResult(sourceName: String, params: ActionSearchParams, page: Int): SearchResults? =
        searchResultsCache[sourceName]
            ?.takeIf { it.params === params && it.isFresh() }
            ?.pages
            ?.get(page)

    fun getCachedSearchTotalPages(sourceName: String, params: ActionSearchParams): Int? =
        searchResultsCache[sourceName]
            ?.takeIf { it.params === params && it.isFresh() }
            ?.pages
            ?.values
            ?.firstOrNull()
            ?.pages

    override fun onInitializeServer() {
        val version = FabricLoader.getInstance().getModContainer(MOD_ID).get().metadata.version
        logInfo("Initializing Ledger ${version.friendlyString}")

        if (!Files.exists(FabricLoader.getInstance().configDir.resolve(CONFIG_PATH))) {
            logInfo("No config file, Creating")
            Files.copy(
                FabricLoader.getInstance().getModContainer(MOD_ID).get().getPath(CONFIG_PATH),
                FabricLoader.getInstance().configDir.resolve(CONFIG_PATH)
            )
        }
        realConfig.validateRequired()
        config = realConfig

        ServerLifecycleEvents.SERVER_STARTING.register(::serverStarting)
        ServerLifecycleEvents.SERVER_STOPPED.register(::serverStopped)
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> registerCommands(dispatcher) }
        PayloadTypeRegistry.playS2C().register(ActionS2CPacket.ID, ActionS2CPacket.CODEC)
        PayloadTypeRegistry.playS2C().register(HandshakeS2CPacket.ID, HandshakeS2CPacket.CODEC)
        PayloadTypeRegistry.playS2C().register(ResponseS2CPacket.ID, ResponseS2CPacket.CODEC)
    }

    private fun serverStarting(server: MinecraftServer) {
        this.server = server
        ExtensionManager.serverStarting(server)
        DatabaseManager.setup(ExtensionManager.getDataSource())
        DatabaseManager.ensureTables()

        ActionRegistry.registerDefaultTypes()
        initListeners()
        Networking

        Ledger.launch {
            val idSet = setOf<Identifier>()
                .plus(Registries.BLOCK.ids)
                .plus(Registries.ITEM.ids)
                .plus(Registries.ENTITY_TYPE.ids)

            logInfo("Inserting ${idSet.size} registry keys into the database...")
            DatabaseManager.insertIdentifiers(idSet)
            logInfo("Registry insert complete")

            DatabaseManager.setupCache()
            DatabaseManager.autoPurge()
        }.invokeOnCompletion {
            ActionQueueService.start()
        }
    }

    private fun serverStopped(server: MinecraftServer) {
        runBlocking {
            try {
                withTimeout(config[DatabaseSpec.queueTimeoutMin].minutes) {
                    Ledger.launch {
                        while (ActionQueueService.pending > 0) {
                            logInfo(
                                "Database is still busy. If you exit now data WILL be lost. " +
                                        "Actions in queue: ${ActionQueueService.pending}"
                            )

                            delay(config[DatabaseSpec.queueCheckDelaySec].seconds)
                        }
                    }
                    ActionQueueService.drainAll()
                    DatabaseManager.close()
                    logInfo("Successfully drained database queue")
                }
            } catch (e: TimeoutCancellationException) {
                logWarn(
                    "Database drain timed out. ${ActionQueueService.pending} actions still in queue. Data may be lost."
                )
            }
        }
    }

    private fun initListeners() {
        registerWorldEventListeners()
        registerPlayerListeners()
        registerBlockListeners()
        registerEntityListeners()
    }

    fun identifier(path: String) = Identifier.of(MOD_ID, path)
}

fun logDebug(message: String) = Ledger.logger.debug(message)
fun logInfo(message: String) = Ledger.logger.info(message)
fun logWarn(message: String) = Ledger.logger.warn(message)
fun logWarn(message: String, throwable: Throwable) = Ledger.logger.warn(message, throwable)
fun logFatal(message: String) = Ledger.logger.warn(message)
