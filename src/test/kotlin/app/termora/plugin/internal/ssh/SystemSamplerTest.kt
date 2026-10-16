package app.termora.plugin.internal.ssh

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemSamplerTest {
    private fun section(name: String, text: String, exit: Int = 0) =
        "__TERMORA_${name}__\n$text\n__TERMORA_${name}_EXIT__$exit\n"

    private fun output(busy: Long, idle: Long, time: Double, sent: Long = 1000, received: Long = 2000): String =
        section("OS", "Linux") + section("CPU", "cpu $busy 0 0 $idle 0 0 0 0") +
            section("MEM", "MemTotal: 1024 kB\nMemAvailable: 256 kB\nSwapTotal: 512 kB\nSwapFree: 128 kB") +
            section("TIME", "$time 0.00") +
            section("NET", "lo: 999 0 0 0 0 0 0 0 999 0 0 0 0 0 0 0\neth0: $received 0 0 0 0 0 0 0 $sent 0 0 0 0 0 0 0")

    @Test
    @DisplayName("首次采样仅建立基线并使用可用内存计算已用量")
    fun firstSampleInitializesBaselineAndCalculatesUsedMemory() {
        val value = SystemSampler().sample(output(100, 100, 1.0), SystemSnapshot(), 1000)
        assertNull(value.cpu)
        assertNull(value.networks)
        assertTrue(value.history.isEmpty())
        assertEquals(768L * 1024, value.memoryUsed)
        assertEquals(384L * 1024, value.swapUsed)
    }

    @Test
    @DisplayName("CPU与网络使用差值和实际采样间隔并排除回环接口")
    fun calculatesCpuAndNetworkDeltasAndExcludesLoopback() {
        val sampler = SystemSampler()
        val first = sampler.sample(output(100, 100, 1.0), SystemSnapshot(), 1000)
        val second = sampler.sample(output(150, 250, 3.0, 1400, 2800), first, 3000)
        assertEquals(25, second.cpu)
        assertEquals(listOf(NetworkRate("eth0", 200, 400)), second.networks)
        assertEquals("ready", second.state)
    }

    @Test
    @DisplayName("历史仅保留最近32次有效采样且重置清除历史")
    fun retainsLast32ValidSamplesAndClearsHistoryOnReset() {
        val sampler = SystemSampler()
        var snapshot = SystemSnapshot()
        var busy = 0L
        var idle = 0L
        for (index in 0..40) {
            busy += index
            idle += 100 - index
            snapshot = sampler.sample(output(busy, idle, index.toDouble()), snapshot, index * 1000L)
        }
        assertEquals((9..40).toList(), snapshot.history)
        sampler.reset()
        val reset = sampler.sample(output(5000, 5000, 100.0), SystemSnapshot(), 100_000)
        assertNull(reset.cpu)
        assertTrue(reset.history.isEmpty())
    }

    @Test
    @DisplayName("网络计数重置时不产生负速率或假零值")
    fun networkCounterResetDoesNotProduceNegativeOrFalseZeroRates() {
        val sampler = SystemSampler()
        val first = sampler.sample(output(100, 100, 1.0), SystemSnapshot(), 1000)
        val reset = sampler.sample(output(150, 150, 2.0, 1, 2), first, 2000)
        assertNull(reset.networks)
        val next = sampler.sample(output(200, 200, 3.0, 101, 202), reset, 3000)
        assertEquals(listOf(NetworkRate("eth0", 100, 200)), next.networks)
    }

    @Test
    @DisplayName("存储按类型过滤并保留网络文件系统和含空格的挂载路径")
    fun filtersFilesystemTypesAndPreservesNetworkMountsAndSpaces() {
        val disks = SystemSampler.parseDisks(
            """
                Filesystem Type 1B-blocks Used Available Use% Mounted on
                /dev/sda1 ext4 1000 910 90 91% /
                server:/data nfs4 2000 500 1500 25% /mnt/shared data
                overlay overlay 1000 910 90 91% /var/lib/containers/merged
                shm tmpfs 64 0 64 0% /dev/shm
                tmpfs tmpfs 100 0 100 0% /run
            """.trimIndent()
        )
        assertEquals(listOf("/", "/mnt/shared data"), disks.map { it.mount })
        assertEquals(91, disks.first().percentage)
    }

    @Test
    @DisplayName("存储根挂载点优先其余按总容量降序并按路径稳定排序")
    fun sortsRootFirstThenByDescendingCapacityAndMountPath() {
        val disks = SystemSampler.parseDisks(
            """
                Filesystem Type 1B-blocks Used Available Use% Mounted on
                /dev/sdb1 ext4 2000 1500 500 75% /small
                /dev/sdc1 xfs 9000 1000 8000 12% /large
                /dev/sda1 ext4 1000 910 90 91% /
                /dev/sdd1 ext4 2000 100 1900 5% /equal
            """.trimIndent()
        )
        assertEquals(listOf("/", "/large", "/equal", "/small"), disks.map { it.mount })
        val withoutRoot = SystemSampler.parseDisks(
            "Filesystem Type 1B-blocks Used Available Use% Mounted on\n" +
                "/dev/sdb1 ext4 2000 1500 500 75% /small\n/dev/sdc1 xfs 9000 1000 8000 12% /large"
        )
        assertEquals(listOf("/large", "/small"), withoutRoot.map { it.mount })
    }

    @Test
    @DisplayName("低频详情在快速采样间缓存并保留原始输出")
    fun cachesDetailsBetweenSamplesAndPreservesRawOutput() {
        val sampler = SystemSampler()
        val df = "Filesystem Size Used Avail Use% Mounted on\n/dev/sda1 1G 500M 500M 50% /"
        val who = "root  pts/0  2026-10-17 09:18 (192.168.1.1)"
        val first = sampler.sample(output(100, 100, 1.0) + section("DF", df) + section("WHO", who) +
            section("DISKS", "Filesystem Type 1B-blocks Used Available Use% Mounted on\n/dev/sda1 ext4 1000 500 500 50% /"),
            SystemSnapshot(), 1000)
        val next = sampler.sample(output(150, 150, 2.0), first, 2000)
        assertEquals(df, next.df)
        assertEquals(who, next.who)
        assertEquals(1000L, next.detailsAt)
        assertEquals(first.disks, next.disks)
    }

    @Test
    @DisplayName("命令失败不作为有效指标且非Linux系统明确标为不支持")
    fun rejectsFailedCommandsAndMarksNonLinuxSystemsUnsupported() {
        val sampler = SystemSampler()
        val unsupported = sampler.sample(section("OS", "Darwin"), SystemSnapshot(), 1000)
        assertEquals("unsupported", unsupported.state)
        val failed = sampler.sample(section("OS", "Linux") + section("CPU", "permission denied", 1),
            SystemSnapshot(), 2000)
        assertNull(failed.cpu)
        assertNull(failed.memoryUsed)
        assertNull(failed.networks)
        assertEquals("unavailable", failed.state)
    }
}
