package app.termora.plugin.internal.ssh

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.junit.jupiter.api.DisplayName
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.*

class SshSystemMonitorIntegrationTest {
    private class LinuxSshContainer : GenericContainer<LinuxSshContainer>(
        DockerImageName.parse("linuxserver/openssh-server:9.3_p2-r1-ls147"),
    )

    private fun next(queue: LinkedBlockingQueue<SystemSnapshot>, predicate: (SystemSnapshot) -> Boolean): SystemSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val value = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(value)) return value
        }
        fail("Timed out waiting for system sampling")
    }

    private fun assertNoSamples(queue: LinkedBlockingQueue<SystemSnapshot>) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200)
        while (System.nanoTime() < deadline) {
            val value = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            assertEquals(0L, value.sampledAt)
        }
    }

    @Test
    @DisplayName("真实LinuxSSH采集共享快照并支持总开关浮窗订阅断连与重连")
    fun sharesLinuxSshSnapshotsAndHandlesEnablementSubscriptionsAndReconnects() {
        LinuxSshContainer().withEnv("PASSWORD_ACCESS", "true")
            .withEnv("USER_NAME", "foo").withEnv("USER_PASSWORD", "pass")
            .withExposedPorts(2222).waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(60)).use { container ->
                container.start()
                SshClient.setUpDefaultClient().use { client ->
                    client.serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
                    client.start()
                    client.connect("foo", container.host, container.getMappedPort(2222))
                        .verify(Duration.ofSeconds(10)).session.use { session ->
                            session.addPasswordIdentity("pass")
                            session.auth().verify(Duration.ofSeconds(10))
                            val monitor = SshSystemMonitor()
                            val first = LinkedBlockingQueue<SystemSnapshot>()
                            val second = LinkedBlockingQueue<SystemSnapshot>()
                            val callbacksOnEdt = AtomicBoolean(true)
                            lateinit var listener: app.termora.Disposable
                            lateinit var otherListener: app.termora.Disposable
                            SwingUtilities.invokeAndWait {
                                listener = monitor.subscribe({
                                    if (!SwingUtilities.isEventDispatchThread()) callbacksOnEdt.set(false)
                                    first.offer(it)
                                })
                                otherListener = monitor.subscribe({ second.offer(it) })
                            }
                            try {
                                monitor.setEnabled(false)
                                monitor.start(session)
                                first.clear()
                                assertNoSamples(first)

                                monitor.setEnabled(true)
                                val sampled = next(first) { it.state == "ready" }
                                assertNotNull(sampled.cpu)
                                assertTrue(sampled.memoryTotal!! > 0)
                                assertNotNull(sampled.networks)
                                assertNotNull(sampled.who)
                                assertTrue(sampled.df!!.contains("Filesystem"))
                                assertTrue(sampled.disks!!.isNotEmpty())
                                assertEquals("foo", sampled.username)
                                val shared = next(second) { it.sampledAt == sampled.sampledAt }
                                assertSame(sampled, shared)

                                monitor.setEnabled(false)
                                next(first) { it.state == "disconnected" }
                                first.clear()
                                assertNoSamples(first)

                                lateinit var details: app.termora.Disposable
                                SwingUtilities.invokeAndWait {
                                    details = monitor.subscribe({}, keepSampling = true)
                                }
                                next(first) { it.state == "ready" }
                                details.dispose()
                                next(first) { it.state == "disconnected" }
                                first.clear()
                                assertNoSamples(first)

                                monitor.setEnabled(true)
                                next(first) { it.state == "ready" }
                                monitor.stop()
                                val stopped = next(first) { it.state == "disconnected" }
                                assertTrue(stopped.history.isEmpty())
                                monitor.start(session)
                                val baseline = next(first) { it.sampledAt > 0 }
                                assertNull(baseline.cpu)
                                assertNull(baseline.networks)
                                assertTrue(baseline.history.isEmpty())
                                next(first) { it.state == "ready" }
                                assertTrue(callbacksOnEdt.get())
                            } finally {
                                listener.dispose()
                                otherListener.dispose()
                                monitor.dispose()
                            }
                        }
                }
            }
    }
}
