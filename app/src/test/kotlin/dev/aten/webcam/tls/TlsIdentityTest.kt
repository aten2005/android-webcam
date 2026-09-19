package dev.aten.webcam.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyStore
import java.time.Duration
import java.time.Instant
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

class TlsIdentityTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun generatesServerCertificateWithExpectedExtensions() {
        val identity = CertGenerator.generate(listOf("192.168.1.20", "cam.example.org", "fe80::1"))
        val cert = identity.certificate

        cert.checkValidity()
        cert.verify(cert.publicKey)
        assertEquals("EC", cert.publicKey.algorithm)
        assertTrue(cert.basicConstraints >= 0)
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), cert.extendedKeyUsage)
        val sans = cert.subjectAlternativeNames.map { it[0] as Int to it[1] as String }
        assertTrue(sans.contains(7 to "192.168.1.20"))
        assertTrue(sans.contains(2 to "cam.example.org"))
        assertEquals(3, sans.size)
        val lifetime = Duration.between(cert.notBefore.toInstant(), cert.notAfter.toInstant())
        assertTrue(lifetime <= Duration.ofDays(CertGenerator.VALIDITY_DAYS))
        assertTrue(Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$").matches(identity.fingerprint))
    }

    @Test
    fun storeReusesIdentityUntilNamesOrExpiryRequireANewOne() {
        val store = CertStore(temp.newFolder("tls"))
        val first = store.loadOrCreate(listOf("10.0.0.5"))
        assertEquals(first.fingerprint, store.loadOrCreate(listOf("10.0.0.5")).fingerprint)
        assertEquals(first.fingerprint, CertStore(temp.root.resolve("tls")).loadOrCreate(emptyList()).fingerprint)

        val second = store.loadOrCreate(listOf("10.0.0.9", "FE80::1%wlan0"))
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertTrue(second.subjectAltNames.containsAll(listOf("10.0.0.5", "10.0.0.9")))
        assertEquals(second.fingerprint, store.loadOrCreate(listOf("fe80::1", "10.0.0.5")).fingerprint)

        val nearExpiry = Instant.now().plus(Duration.ofDays(CertGenerator.VALIDITY_DAYS - 10))
        assertNotEquals(second.fingerprint, store.loadOrCreate(listOf("10.0.0.9"), nearExpiry).fingerprint)
    }

    @Test
    fun storeRecoversFromCorruptFiles() {
        val directory = temp.newFolder("tls")
        val store = CertStore(directory)
        store.loadOrCreate(listOf("10.0.0.5"))
        directory.resolve("tls-cert.der").writeBytes(byteArrayOf(1, 2, 3))
        store.loadOrCreate(listOf("10.0.0.5")).certificate.checkValidity()
    }

    @Test
    fun serverContextCompletesHandshakeWithPinnedClient() {
        val identity = CertGenerator.generate(listOf("127.0.0.1"))
        val server = TlsContextFactory.serverContext(identity).serverSocketFactory
            .createServerSocket(0) as SSLServerSocket
        server.enabledProtocols = TlsContextFactory.PROTOCOLS
        val acceptor = thread {
            server.accept().use { socket ->
                socket.getOutputStream().write(42)
                socket.getOutputStream().flush()
                socket.getInputStream().read()
            }
        }

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("webcam", identity.certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
        val client = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        (client.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use { socket ->
            socket.startHandshake()
            assertEquals("TLSv1.3", socket.session.protocol)
            assertEquals(42, socket.getInputStream().read())
            socket.getOutputStream().write(1)
        }
        acceptor.join(5000)
        server.close()
    }
}
