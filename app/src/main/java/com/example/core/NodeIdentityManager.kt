package com.example.core

import android.content.Context
import android.util.Base64
import com.example.data.ZeroLogEngine
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Manages a real hardware-wrapped EC (P-256 / secp256r1) cryptographic identity for the local
 * SimpleX node. Private key material (PKCS#8) is sealed via [KeystoreVault] AES-256-GCM with AAD
 * and never stored in plaintext. Provides ECDSA challenge signing and Signal/SimpleX-style
 * order-independent numeric Safety Numbers for out-of-band peer verification.
 */
class NodeIdentityManager(
    context: Context,
    private val keystoreVault: KeystoreVault
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "paranoid_node_identity"
        private const val KEY_SEALED_PRIV = "ec_p256_sealed_priv_v1"
        private const val KEY_PUB_X509 = "ec_p256_pub_x509_v1"
        private val KEY_AAD = "paranoid-node-identity-ec-v1".toByteArray(Charsets.UTF_8)
        private const val EC_CURVE = "secp256r1"
        private const val SIG_ALG = "SHA256withECDSA"
    }

    @Synchronized
    fun ensureIdentity(): String {
        val existingPub = prefs.getString(KEY_PUB_X509, null)
        val existingSealed = prefs.getString(KEY_SEALED_PRIV, null)
        if (!existingPub.isNullOrBlank() && !existingSealed.isNullOrBlank()) {
            val blob = KeystoreVault.SealedBlob.deserialize(existingSealed)
            val probe = blob?.let { keystoreVault.unseal(it, KEY_AAD) }
            if (probe != null) {
                probe.fill(0)
                return existingPub
            }
        }
        return rotateIdentity()
    }

    @Synchronized
    fun rotateIdentity(): String {
        val kp = generateEcKeyPair()
        val pubBytes = kp.public.encoded
        val privBytes = kp.private.encoded
        return try {
            val sealed = keystoreVault.seal(privBytes, KEY_AAD)
            val pubB64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP)
            prefs.edit()
                .putString(KEY_PUB_X509, pubB64)
                .putString(KEY_SEALED_PRIV, sealed.serialize())
                .apply()
            ZeroLogEngine.log("Crypto", "Rotated local EC P-256 node identity (AES-256-GCM wrapped).")
            pubB64
        } finally {
            privBytes.fill(0)
            pubBytes.fill(0)
        }
    }

    @Synchronized
    fun wipeIdentity() {
        prefs.edit().clear().apply()
        ZeroLogEngine.log("Crypto", "Local EC P-256 node identity wiped.")
    }

    fun getPublicKeyBase64(): String = ensureIdentity()

    fun getLocalFingerprint(): String {
        val pubBytes = Base64.decode(ensureIdentity(), Base64.NO_WRAP)
        val hash = MessageDigest.getInstance("SHA-256").digest(pubBytes)
        return hash.take(16).chunked(2).joinToString(":") { pair ->
            pair.joinToString("") { "%02X".format(it) }
        }
    }

    fun signChallenge(challenge: ByteArray): ByteArray? {
        val privKey = loadPrivateKey() ?: return null
        val signer = Signature.getInstance(SIG_ALG)
        signer.initSign(privKey)
        signer.update(challenge)
        return signer.sign()
    }

    fun verifySignature(publicKeyBase64: String, challenge: ByteArray, signature: ByteArray): Boolean {
        return try {
            val pubBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
            val verifier = Signature.getInstance(SIG_ALG)
            verifier.initVerify(pubKey)
            verifier.update(challenge)
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Derives a 60-digit (12 x 5-digit blocks) order-independent safety number combining
     * local EC public key digest and peer public key / queue identifier digest.
     */
    fun computeSafetyNumber(peerKeyOrAddress: String): String {
        val localDigest = MessageDigest.getInstance("SHA-256")
            .digest(Base64.decode(ensureIdentity(), Base64.NO_WRAP))
        val peerDigest = MessageDigest.getInstance("SHA-256")
            .digest(peerKeyOrAddress.toByteArray(Charsets.UTF_8))

        val combined = if (compareUnsignedBytes(localDigest, peerDigest) <= 0) {
            localDigest + peerDigest
        } else {
            peerDigest + localDigest
        }

        // Key-stretch via iterated SHA-256 to spread entropy across 12 numeric groups
        var digest = combined
        repeat(64) {
            digest = MessageDigest.getInstance("SHA-256").digest(digest)
        }
        val finalDigest = digest + MessageDigest.getInstance("SHA-256").digest(digest)
        return (0 until 12).joinToString(" ") { idx ->
            val offset = idx * 4
            val chunk = ((finalDigest[offset].toInt() and 0xFF) shl 24) or
                ((finalDigest[offset + 1].toInt() and 0xFF) shl 16) or
                ((finalDigest[offset + 2].toInt() and 0xFF) shl 8) or
                (finalDigest[offset + 3].toInt() and 0xFF)
            val positive = chunk.toLong() and 0xFFFFFFFFL
            "%05d".format(positive % 100_000L)
        }
    }

    private fun compareUnsignedBytes(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val av = a[i].toInt() and 0xFF
            val bv = b[i].toInt() and 0xFF
            if (av != bv) return av - bv
        }
        return a.size - b.size
    }

    private fun loadPrivateKey(): PrivateKey? {
        ensureIdentity()
        val serialized = prefs.getString(KEY_SEALED_PRIV, null) ?: return null
        val blob = KeystoreVault.SealedBlob.deserialize(serialized) ?: return null
        val pkcs8 = keystoreVault.unseal(blob, KEY_AAD) ?: return null
        return try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } catch (_: Exception) {
            null
        } finally {
            pkcs8.fill(0)
        }
    }

    private fun generateEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        return kpg.generateKeyPair()
    }
}
