package dev.aten.webcam.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Date

class TlsIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }

    /** DNS names and IP addresses listed in the certificate's subjectAltName. */
    val subjectAltNames: Set<String>
        get() = certificate.subjectAlternativeNames.orEmpty().map { it[1].toString() }.toSet()
}

object CertGenerator {
    // Apple platforms reject server certificates valid for longer than 825 days.
    const val VALIDITY_DAYS = 825L
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun generate(subjectAltNames: Collection<String>, now: Instant = Instant.now()): TlsIdentity {
        // The platform's default providers are used on purpose: registering BouncyCastle as "BC"
        // would collide with Android's own stripped-down provider of the same name.
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val subject = X500Name("CN=IP Webcam")
        val names = subjectAltNames.map(::toGeneralName).toTypedArray()

        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(127, SecureRandom()).add(BigInteger.ONE),
            Date.from(now.minus(Duration.ofDays(1))),
            Date.from(now.plus(Duration.ofDays(VALIDITY_DAYS - 1))),
            subject,
            keyPair.public,
        )
            // CA:TRUE lets iOS/Android install the certificate as a trusted root.
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign))
            .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            .addExtension(Extension.subjectAlternativeName, false, GeneralNames(names))

        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return TlsIdentity(keyPair.private, certificate)
    }

    private fun toGeneralName(name: String): GeneralName {
        val isIp = IPV4.matches(name) || name.contains(':')
        return GeneralName(if (isIp) GeneralName.iPAddress else GeneralName.dNSName, name)
    }
}
