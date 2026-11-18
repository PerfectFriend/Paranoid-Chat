package com.example.core

/**
 * Provides transparent field-level AES-256-GCM encryption for Room-persisted message bodies
 * using [KeystoreVault]. Associated Authenticated Data (AAD) binds each ciphertext strictly to its
 * owning [contactId], preventing cross-conversation row swapping and ensuring instant
 * cryptographic erasure once [KeystoreVault.wipeMasterKey] destroys the hardware key.
 */
class MessageVaultCipher(private val keystoreVault: KeystoreVault) {

    companion object {
        private const val CIPHER_PREFIX = "enc:v1:"
        private const val AAD_PREFIX = "paranoid-msg-v1:"
        const val WIPED_PLACEHOLDER = "[Cryptographic key wiped or tampered]"
    }

    private fun aadFor(contactId: String): ByteArray {
        return (AAD_PREFIX + contactId).toByteArray(Charsets.UTF_8)
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(CIPHER_PREFIX)

    fun encryptText(contactId: String, plaintext: String): String {
        if (plaintext.isEmpty() || isEncrypted(plaintext)) return plaintext
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        return try {
            val blob = keystoreVault.seal(bytes, aadFor(contactId))
            CIPHER_PREFIX + blob.serialize()
        } finally {
            bytes.fill(0)
        }
    }

    fun decryptText(contactId: String, storedValue: String): String {
        if (!isEncrypted(storedValue)) return storedValue
        val encoded = storedValue.removePrefix(CIPHER_PREFIX)
        val blob = KeystoreVault.SealedBlob.deserialize(encoded) ?: return WIPED_PLACEHOLDER
        val decrypted = keystoreVault.unseal(blob, aadFor(contactId)) ?: return WIPED_PLACEHOLDER
        return try {
            String(decrypted, Charsets.UTF_8)
        } finally {
            decrypted.fill(0)
        }
    }
}
