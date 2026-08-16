package app.termora.plugins.sync

import app.termora.ApplicationScope
import app.termora.Disposable
import app.termora.FrameExtension
import app.termora.TermoraFrame
import app.termora.account.AccountManager
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

@Suppress("DuplicatedCode")
class SyncManager private constructor() : Disposable, FrameExtension {
    companion object {
        private val log = LoggerFactory.getLogger(SyncManager::class.java)

        fun getInstance(): SyncManager {
            return ApplicationScope.forApplicationScope().getOrCreate(SyncManager::class) { SyncManager() }
        }
    }

    private val sync get() = SyncProperties.getInstance()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var onChangeJob: Job? = null
    private var startupJob: Job? = null
    private var periodicJob: Job? = null
    private var disableTrigger = false
    private val automaticSyncStarted = AtomicBoolean(false)
    private val accountManager get() = AccountManager.getInstance()


    private fun trigger() {
        trigger(getSyncConfig())
    }

    fun triggerOnChanged() {
        if (sync.policy == SyncPolicy.OnChange.name) {
            trigger()
        }
    }

    private fun trigger(config: SyncConfig) {
        if (disableTrigger) return

        onChangeJob?.cancel()

        if (accountManager.isLocally().not()) {
            return
        }

        if (log.isInfoEnabled) {
            log.info("Automatic synchronisation is interrupted")
        }

        onChangeJob = coroutineScope.launch {

            // 因为会频繁调用，等待 10 - 30 秒
            val seconds = Random.nextInt(10, 30)
            if (log.isInfoEnabled) {
                log.info("Trigger synchronisation, which will take place after {} seconds", seconds)
            }

            delay(seconds.seconds)


            if (!disableTrigger) {
                try {

                    if (log.isInfoEnabled) {
                        log.info("Automatic synchronisation begin")
                    }

                    // 如果已经开始，设置为 null
                    // 因为同步的时候会修改数据，避免被中断
                    onChangeJob = null

                    sync(config)

                    sync.lastSyncTime = System.currentTimeMillis()

                    if (log.isInfoEnabled) {
                        log.info("Automatic synchronisation end")
                    }

                } catch (e: Exception) {
                    if (log.isErrorEnabled) {
                        log.error(e.message, e)
                    }
                }
            }
        }
    }

    override fun customize(frame: TermoraFrame) {
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                frame.removeWindowListener(this)
                startAutomaticSync()
            }
        })
    }

    private fun startAutomaticSync() {
        if (!automaticSyncStarted.compareAndSet(false, true)) return

        // Window opened means the UI is ready. Leave a little more time for the
        // first paint before starting network and database work in the background.
        startupJob = coroutineScope.launch {
            delay(3.seconds)
            automaticSync("Startup")
        }
        reschedulePeriodicSync()
    }

    fun reschedulePeriodicSync() {
        periodicJob?.cancel()
        periodicJob = null

        if (!automaticSyncStarted.get()) return
        val interval = sync.periodicSyncInterval.duration ?: return

        periodicJob = coroutineScope.launch {
            while (isActive) {
                delay(interval)
                automaticSync("Periodic")
            }
        }
    }

    private fun automaticSync(trigger: String) {
        if (accountManager.isLocally().not()) return

        val config = getSyncConfig()
        if (!isConfigured(config)) {
            if (log.isDebugEnabled) {
                log.debug("{} synchronisation skipped because sync is not configured", trigger)
            }
            return
        }

        try {
            if (log.isInfoEnabled) {
                log.info("{} synchronisation begin", trigger)
            }
            sync(config)
            sync.lastSyncTime = System.currentTimeMillis()
            if (log.isInfoEnabled) {
                log.info("{} synchronisation end", trigger)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (log.isErrorEnabled) {
                log.error("{} synchronisation failed: {}", trigger, e.message, e)
            }
        }
    }

    private fun isConfigured(config: SyncConfig): Boolean {
        if (config.token.isBlank() || config.gistId.isBlank()) return false
        return when (config.type) {
            SyncType.GitHub, SyncType.Gitee -> true
            SyncType.GitLab, SyncType.WebDAV -> config.options["domain"].isNullOrBlank().not()
        }
    }

    fun sync(config: SyncConfig): SyncResponse {
        return syncImmediately(config)
    }


    private fun getSyncConfig(): SyncConfig {
        val range = mutableSetOf<SyncRange>()
        if (sync.rangeHosts) {
            range.add(SyncRange.Hosts)
        }
        if (sync.rangeKeyPairs) {
            range.add(SyncRange.KeyPairs)
        }
        if (sync.rangeKeywordHighlights) {
            range.add(SyncRange.KeywordHighlights)
        }
        if (sync.rangeMacros) {
            range.add(SyncRange.Macros)
        }
        if (sync.rangeKeymap) {
            range.add(SyncRange.Keymap)
        }
        if (sync.rangeSnippets) {
            range.add(SyncRange.Snippets)
        }
        return SyncConfig(
            type = sync.type,
            token = sync.token,
            gistId = sync.gist,
            options = mapOf("domain" to sync.domain),
            ranges = range
        )
    }


    private fun syncImmediately(config: SyncConfig): SyncResponse {
        synchronized(this) {
            val pull = pull(config)

            // Pull decoders enqueue database updates on the EDT. Wait until those
            // updates are applied before encoding the local state for push.
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeAndWait {}
            }

            return SyncResponse(pull, push(config))
        }
    }


    fun pull(config: SyncConfig): GistResponse {
        if (accountManager.isLocally().not()) {
            throw IllegalStateException(SyncI18n.getString("termora.plugins.sync.disabled-sync"))
        }

        synchronized(this) {
            disableTrigger = true
            try {
                return SyncerProvider.getInstance().getSyncer(config.type).pull(config)
            } finally {
                disableTrigger = false
            }
        }
    }

    fun push(config: SyncConfig): GistResponse {
        if (accountManager.isLocally().not()) {
            throw IllegalStateException(SyncI18n.getString("termora.plugins.sync.disabled-sync"))
        }

        synchronized(this) {
            try {
                disableTrigger = true
                return SyncerProvider.getInstance().getSyncer(config.type).push(config)
            } finally {
                disableTrigger = false
            }
        }
    }


    override fun dispose() {
        coroutineScope.cancel()
    }


    private class SyncerProvider private constructor() {
        companion object {
            fun getInstance(): SyncerProvider {
                return ApplicationScope.forApplicationScope().getOrCreate(SyncerProvider::class) { SyncerProvider() }
            }
        }


        fun getSyncer(type: SyncType): Syncer {
            return when (type) {
                SyncType.GitHub -> GitHubSyncer.getInstance()
                SyncType.Gitee -> GiteeSyncer.getInstance()
                SyncType.GitLab -> GitLabSyncer.getInstance()
                SyncType.WebDAV -> WebDAVSyncer.getInstance()
            }
        }
    }

    data class SyncResponse(val pull: GistResponse, val push: GistResponse)

}
