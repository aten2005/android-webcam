# Only BouncyCastle's certificate-building classes are used (tls/CertGenerator.kt). They are kept
# whole so shrinking cannot break first-run certificate generation; the unused bulk of the library
# (cipher engines, post-quantum, PGP, ...) is still removed.
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }

# Optional integrations that do not exist on Android.
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
