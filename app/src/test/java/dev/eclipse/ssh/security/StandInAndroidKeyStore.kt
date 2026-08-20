package dev.eclipse.ssh.security

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A JCA provider that answers to the name `AndroidKeyStore` on the JVM, so the code that stores
 * credentials can be tested through the app rather than only on a device.
 *
 * Nothing about [SecureVault] is replaced or relaxed here. The vault keeps asking the platform for a
 * hardware-backed AES-256 key exactly as it does in production; what this provides is somewhere for
 * that request to land, because `AndroidKeyStore` is one of the few pieces of the platform Robolectric
 * has no implementation of at all — `KeyStore.getInstance("AndroidKeyStore")` throws
 * `NoSuchAlgorithmException` before the vault gets as far as generating anything. Without it, every
 * write through [dev.eclipse.ssh.data.credentials.HostCredentialStore] fails on the JVM, and the whole
 * of "save a password on a host, connect with it, delete the host, watch the secret go" is unreachable
 * from a unit test.
 *
 * The key it hands out is a real AES-256 key from [SecureRandom] and the encryption is the platform's
 * own AES/GCM — the vault's [SecretCipher.encrypt] and [SecretCipher.decrypt] run their real code
 * paths against it, including the tag check that makes a tampered payload fail. What is *not* real is
 * where the key lives: in a map in this process rather than in a TEE. That property is the one thing
 * this cannot check, and it is the reason
 * [dev.eclipse.ssh.security.SecureVaultInstrumentedTest] exists and stays on a device.
 *
 * [install] is idempotent and [uninstall] puts the JVM back as it was, so a class that opts in does
 * not quietly change how the next test class in the same JVM behaves.
 */
object StandInAndroidKeyStore {
    const val NAME = "AndroidKeyStore"

    /** Keys by alias, shared for the life of the JVM the way the platform keystore is by a device. */
    private val keys = ConcurrentHashMap<String, SecretKey>()

    fun install() {
        if (Security.getProvider(NAME) == null) Security.addProvider(StandInProvider())
    }

    fun uninstall() {
        Security.removeProvider(NAME)
        keys.clear()
    }

    // The (String, String, String) constructor is JDK 9+ and is not on the Android API this compiles
    // against, so the numeric version it is.
    @Suppress("DEPRECATION")
    private class StandInProvider : Provider(NAME, 1.0, "In-memory AndroidKeyStore stand-in (tests only)") {
        init {
            putService(StandInService(this, "KeyStore", NAME))
            putService(StandInService(this, "KeyGenerator", "AES"))
        }
    }

    /**
     * Builds the SPI directly instead of letting JCA reflect on a class name.
     *
     * Robolectric loads each test class in its own sandbox classloader, and a provider registered
     * from one sandbox outlives it in the JVM-wide [Security] registry. Reflection would then look
     * these classes up in whichever loader got there first; returning the instance is the only form
     * that cannot depend on that.
     */
    private class StandInService(
        provider: Provider,
        type: String,
        algorithm: String,
    ) : Provider.Service(provider, type, algorithm, StandInKeyStoreSpi::class.java.name, null, null) {
        override fun newInstance(constructorParameter: Any?): Any = when (type) {
            "KeyStore" -> StandInKeyStoreSpi()
            "KeyGenerator" -> StandInKeyGeneratorSpi()
            else -> throw KeyStoreException("Unsupported service type $type")
        }

        override fun supportsParameter(parameter: Any?): Boolean = true
    }

    /**
     * Generates the key the vault asks for and files it under the alias in its
     * [KeyGenParameterSpec], which is what makes the *next* run of the process find the same key
     * instead of encrypting under a new one.
     */
    private class StandInKeyGeneratorSpi : KeyGeneratorSpi() {
        private var alias: String? = null
        private var sizeBits = DEFAULT_SIZE_BITS

        override fun engineInit(random: SecureRandom?) = Unit

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            val spec = params as? KeyGenParameterSpec ?: return
            alias = spec.keystoreAlias
            if (spec.keySize > 0) sizeBits = spec.keySize
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) {
            sizeBits = keysize
        }

        override fun engineGenerateKey(): SecretKey {
            val material = ByteArray(sizeBits / 8).also(SecureRandom()::nextBytes)
            val key = SecretKeySpec(material, "AES")
            alias?.let { keys[it] = key }
            return key
        }

        private companion object {
            const val DEFAULT_SIZE_BITS = 256
        }
    }

    /** The alias lookup, and nothing else: the vault only ever stores and fetches one secret key. */
    private class StandInKeyStoreSpi : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

        override fun engineGetKey(alias: String?, password: CharArray?): Key? = keys[alias]

        override fun engineContainsAlias(alias: String?): Boolean = keys.containsKey(alias)

        override fun engineIsKeyEntry(alias: String?): Boolean = keys.containsKey(alias)

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())

        override fun engineSize(): Int = keys.size

        override fun engineDeleteEntry(alias: String?) {
            keys.remove(alias)
        }

        override fun engineSetKeyEntry(
            alias: String?,
            key: Key?,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ) {
            val secret = key as? SecretKey ?: throw KeyStoreException("Only secret keys are supported")
            keys[alias ?: throw KeyStoreException("Alias required")] = secret
        }

        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) =
            throw KeyStoreException("Encoded key entries are not supported")

        override fun engineSetCertificateEntry(alias: String?, certificate: Certificate?) =
            throw KeyStoreException("Certificate entries are not supported")

        override fun engineGetCertificate(alias: String?): Certificate? = null

        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null

        override fun engineGetCertificateAlias(certificate: Certificate?): String? = null

        override fun engineGetCreationDate(alias: String?): Date? = null

        override fun engineIsCertificateEntry(alias: String?): Boolean = false

        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    }
}
