package app.termora.plugin.internal.ssh

import app.termora.Disposable
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

internal data class SystemDisk(val mount: String, val used: Long, val total: Long, val percentage: Int)
internal data class NetworkRate(val name: String, val sent: Long, val received: Long)
internal data class SystemSnapshot(
    val state: String = "disconnected",
    val hostname: String = "",
    val system: String = "",
    val username: String = "",
    val cpu: Int? = null,
    val history: List<Int> = emptyList(),
    val memoryUsed: Long? = null,
    val memoryTotal: Long? = null,
    val swapUsed: Long? = null,
    val swapTotal: Long? = null,
    val networks: List<NetworkRate>? = null,
    val disks: List<SystemDisk>? = null,
    val who: String? = null,
    val df: String? = null,
    val sampledAt: Long = 0,
    val detailsAt: Long = 0,
)

/** Parses only machine-readable counters; command output for tooltips is kept separately. */
internal class SystemSampler {
    private var previousCpu: List<Long>? = null
    private var previousNetwork: Map<String, Pair<Long, Long>>? = null
    private var previousTime: Double? = null
    private val history = ArrayDeque<Int>()

    fun reset() {
        previousCpu = null
        previousNetwork = null
        previousTime = null
        history.clear()
    }

    fun sample(output: String, previous: SystemSnapshot, now: Long): SystemSnapshot {
        val sections = mutableMapOf<String, String>()
        val pattern = Regex("(?ms)^__TERMORA_([A-Z]+)__\\r?\\n(.*?)^__TERMORA_\\1_EXIT__0\\r?$")
        pattern.findAll(output).forEach { sections[it.groupValues[1]] = it.groupValues[2].trimEnd('\r', '\n') }
        if (sections["OS"]?.trim() != "Linux") {
            reset()
            return SystemSnapshot(state = if ("OS" in sections) "unsupported" else "unavailable")
        }

        val cpu = sections["CPU"]?.lineSequence()?.firstOrNull { it.startsWith("cpu ") }
            ?.trim()?.split(Regex("\\s+"))?.drop(1)?.take(8)?.mapNotNull(String::toLongOrNull)
            ?.takeIf { it.size == 8 }
        val oldCpu = previousCpu
        val usage = if (cpu != null && oldCpu != null) {
            val delta = cpu.zip(oldCpu) { a, b -> a - b }
            val total = delta.sum()
            if (total > 0 && delta.all { it >= 0 })
                ((total - delta[3] - delta[4]).toDouble() / total * 100).roundToInt().coerceIn(0, 100)
            else null
        } else null
        previousCpu = cpu
        if (usage != null) {
            history.addLast(usage)
            if (history.size > 32) history.removeFirst()
        }

        val memory = sections["MEM"].orEmpty().lineSequence().mapNotNull {
            val parts = it.split(Regex("\\s+"))
            val value = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            parts[0].removeSuffix(":") to value * 1024
        }.toMap()
        val totalMemory = memory["MemTotal"]?.takeIf { it > 0 }
        val available = memory["MemAvailable"] ?: memory["MemFree"]?.let {
            it + (memory["Buffers"] ?: 0) + (memory["Cached"] ?: 0) +
                (memory["SReclaimable"] ?: 0) - (memory["Shmem"] ?: 0)
        }
        val network = sections["NET"]?.lineSequence()?.mapNotNull {
            val split = it.trim().split(":", limit = 2)
            if (split.size != 2 || split[0] == "lo") return@mapNotNull null
            val fields = split[1].trim().split(Regex("\\s+"))
            val received = fields.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val sent = fields.getOrNull(8)?.toLongOrNull() ?: return@mapNotNull null
            split[0] to (sent to received)
        }?.toMap()
        val time = sections["TIME"]?.trim()?.substringBefore(' ')?.toDoubleOrNull()
        val oldNetwork = previousNetwork
        val elapsed = time?.let { t -> previousTime?.let { t - it } }
        val rates = if (network != null && oldNetwork != null && elapsed != null && elapsed > 0) {
            network.mapNotNull { (name, counters) ->
                val old = oldNetwork[name] ?: return@mapNotNull null
                val sent = counters.first - old.first
                val received = counters.second - old.second
                if (sent < 0 || received < 0) return@mapNotNull null
                NetworkRate(name, (sent / elapsed).toLong(), (received / elapsed).toLong())
            }.takeIf { it.isNotEmpty() || network.isEmpty() }
        } else null
        previousNetwork = network
        previousTime = time
        val details = "DISKS" in sections && "DF" in sections && "WHO" in sections
        return SystemSnapshot(
            state = if (usage != null && totalMemory != null && available != null && rates != null) "ready" else "unavailable",
            hostname = sections["HOST"]?.trim() ?: previous.hostname,
            system = sections["SYSTEM"]?.trim() ?: previous.system,
            username = sections["USER"]?.trim() ?: previous.username,
            cpu = usage, history = history.toList(),
            memoryUsed = totalMemory?.let { t -> available?.let { (t - it).coerceIn(0, t) } },
            memoryTotal = totalMemory,
            swapTotal = memory["SwapTotal"],
            swapUsed = memory["SwapTotal"]?.let { t -> memory["SwapFree"]?.let { (t - it).coerceIn(0, t) } },
            networks = rates,
            disks = if (details) parseDisks(sections.getValue("DISKS")) else previous.disks,
            who = if (details) sections["WHO"] else previous.who,
            df = if (details) sections["DF"] else previous.df,
            sampledAt = now, detailsAt = if (details) now else previous.detailsAt,
        )
    }

    companion object {
        private val excludedTypes = setOf(
            "overlay", "tmpfs", "devtmpfs", "shm", "ramfs", "proc", "sysfs", "devpts", "cgroup",
            "cgroup2", "securityfs", "debugfs", "tracefs", "pstore", "configfs", "mqueue", "hugetlbfs",
            "fusectl", "autofs", "binfmt_misc", "rpc_pipefs", "nsfs", "squashfs",
        )

        fun parseDisks(output: String): List<SystemDisk> = output.lineSequence().drop(1).mapNotNull {
            val fields = it.trim().split(Regex("\\s+"), limit = 7)
            if (fields.size != 7 || fields[1] in excludedTypes || fields[0] == "shm") return@mapNotNull null
            val total = fields[2].toLongOrNull()?.takeIf { size -> size > 0 } ?: return@mapNotNull null
            val used = fields[3].toLongOrNull() ?: return@mapNotNull null
            val percentage = fields[5].removeSuffix("%").toIntOrNull() ?: return@mapNotNull null
            SystemDisk(fields[6], used, total, percentage)
        }.distinctBy { it.mount }
            .sortedWith(compareBy<SystemDisk> { it.mount != "/" }.thenByDescending { it.total }.thenBy { it.mount })
            .toList()
    }
}

/** One collector per SSH tab. All subscribers receive immutable snapshots on the EDT. */
internal class SshSystemMonitor : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<(SystemSnapshot) -> Unit>()
    private var job: Job? = null
    private var session: ClientSession? = null
    private var enabled = true
    private var detailSubscribers = 0
    @Volatile private var generation = 0
    @Volatile private var channel: ChannelExec? = null
    @Volatile var snapshot = SystemSnapshot()
        private set

    @Synchronized
    fun subscribe(listener: (SystemSnapshot) -> Unit, keepSampling: Boolean = false): Disposable {
        listeners.add(listener)
        listener(snapshot)
        if (keepSampling) {
            detailSubscribers++
            updateSampling()
        }
        return object : Disposable {
            override fun dispose() {
                synchronized(this@SshSystemMonitor) {
                    if (listeners.remove(listener) && keepSampling) {
                        detailSubscribers--
                        updateSampling()
                    }
                }
            }
        }
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        updateSampling()
    }

    @Synchronized
    fun start(session: ClientSession) {
        stop()
        this.session = session
        updateSampling()
    }

    private fun updateSampling() {
        val currentSession = session
        if (currentSession == null || (!enabled && detailSubscribers == 0)) {
            if (job != null) stopSampling()
            return
        }
        if (job?.isActive == true) return
        startSampling(currentSession)
    }

    private fun startSampling(session: ClientSession) {
        val token = generation
        job = scope.launch {
            val sampler = SystemSampler()
            var current = SystemSnapshot(state = "unavailable")
            publish(token, current)
            while (isActive && session.isOpen) {
                val started = System.nanoTime()
                try {
                    val now = System.currentTimeMillis()
                    val details = current.detailsAt == 0L || now - current.detailsAt >= 10_000
                    val output = runInterruptible { collect(session, details, token) }
                    current = sampler.sample(output, current, System.currentTimeMillis()).copy(username = session.username)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    sampler.reset()
                    current = SystemSnapshot(state = "unavailable")
                }
                publish(token, current)
                if (current.state == "unsupported") break
                val elapsed = (System.nanoTime() - started) / 1_000_000
                delay((1000 - elapsed).coerceAtLeast(1))
            }
        }
    }

    private fun collect(session: ClientSession, details: Boolean, token: Int): String {
        val script = SshSystemMonitor::class.java.getResource("/scripts/system-status.sh")!!.readText()
        val exec = session.createExecChannel("TERMORA_DETAILS=${if (details) 1 else 0}; $script")
        synchronized(this) {
            if (token != generation) {
                exec.close(true)
                throw CancellationException()
            }
            channel = exec
        }
        try {
            val output = ByteArrayOutputStream()
            exec.out = output
            exec.err = ByteArrayOutputStream()
            exec.open().verify(Duration.ofSeconds(5))
            val events = exec.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Duration.ofSeconds(5))
            check(ClientChannelEvent.CLOSED in events && exec.exitStatus == 0) { "System sampling failed" }
            return output.toString(Charsets.UTF_8)
        } finally {
            exec.close(true)
            synchronized(this) { if (channel === exec) channel = null }
        }
    }

    private suspend fun publish(token: Int, value: SystemSnapshot) = withContext(Dispatchers.Swing) {
        if (token == generation) {
            snapshot = value
            listeners.forEach { it(value) }
        }
    }

    @Synchronized
    fun stop() {
        session = null
        stopSampling()
    }

    private fun stopSampling() {
        generation++
        job?.cancel()
        job = null
        channel?.close(true)
        channel = null
        val token = generation
        scope.launch { publish(token, SystemSnapshot()) }
    }

    override fun dispose() {
        stop()
        scope.cancel()
        listeners.clear()
    }
}
