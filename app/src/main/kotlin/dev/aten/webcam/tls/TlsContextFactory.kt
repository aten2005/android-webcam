package dev.aten.webcam.tls

import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object TlsContextFactory {
    val PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

    fun serverContext(identity: TlsIdentity): SSLContext {
        val password = CharArray(0)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("webcam", identity.privateKey, password, arrayOf(identity.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }
            .keyManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }
}
