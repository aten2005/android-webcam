package dev.aten.webcam.tls

import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant

/** Persists the self-signed identity so browsers keep their accepted-certificate exception. */
class CertStore(private val directory: File) {
    private val keyFile = File(directory, "tls-key.pk8")
    private val certFile = File(directory, "tls-cert.der")

    /**
     * Reuses the stored identity unless it is close to expiry or lacks one of [requiredNames].
     * Previously issued names are carried over so moving between networks does not force a new
     * certificate (and a new browser warning) on every switch.
     */
    fun loadOrCreate(requiredNames: Collection<String>, now: Instant = Instant.now()): TlsIdentity {
        val required = requiredNames.map(::normalize).toSet()
        val existing = load()
        val existingNames = existing?.subjectAltNames.orEmpty().map(::normalize).toSet()
        if (existing != null) {
            val expiresSoon = existing.certificate.notAfter.toInstant() < now.plus(RENEW_BEFORE)
            if (!expiresSoon && existingNames.containsAll(required)) return existing
        }
        val carriedOver = (existingNames - required).take((MAX_NAMES - required.size).coerceAtLeast(0))
        return CertGenerator.generate(required + carriedOver, now).also(::save)
    }

    fun regenerate(requiredNames: Collection<String>, now: Instant = Instant.now()): TlsIdentity =
        CertGenerator.generate(requiredNames.map(::normalize).toSet(), now).also(::save)

    private fun load(): TlsIdentity? = try {
        if (!keyFile.exists() || !certFile.exists()) {
            null
        } else {
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certFile.inputStream()) as X509Certificate
            TlsIdentity(key, cert)
        }
    } catch (_: Exception) {
        null
    }

    private fun save(identity: TlsIdentity) {
        directory.mkdirs()
        keyFile.writeBytes(identity.privateKey.encoded)
        certFile.writeBytes(identity.certificate.encoded)
    }

    companion object {
        private val RENEW_BEFORE: Duration = Duration.ofDays(30)
        private const val MAX_NAMES = 24

        /** Drops IPv6 zone ids and expands IPv6 to the form the JDK reports back from a certificate. */
        fun normalize(name: String): String {
            val bare = name.substringBefore('%').lowercase()
            if (!bare.contains(':')) return bare
            return try {
                java.net.InetAddress.getByName(bare).hostAddress ?: bare
            } catch (_: Exception) {
                bare
            }
        }
    }
}
