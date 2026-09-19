package dev.aten.webcam.auth

import java.security.MessageDigest
import java.security.SecureRandom

object Passwords {
    // 32 unambiguous symbols (no 0/O, 1/l/I), so each character carries exactly 5 bits.
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    private const val GENERATED_LENGTH = 16
    const val MIN_LENGTH = 8

    fun generate(random: SecureRandom = SecureRandom()): String =
        String(CharArray(GENERATED_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** Compares digests so timing reveals neither the password's content nor its length. */
    fun matches(expected: String, candidate: String): Boolean =
        MessageDigest.isEqual(sha256(expected), sha256(candidate))

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
}
