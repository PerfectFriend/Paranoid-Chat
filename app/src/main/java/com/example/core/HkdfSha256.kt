package com.example.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HKDF-SHA256 key derivation helper for domain-separated symmetric keys
 * (e.g., encrypted vault snapshots, queue secrets, and future Rust FFI handoff parity).
 */
object HkdfSha256 {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(effectiveSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        require(prk.isNotEmpty()) { "PRK must not be empty" }
        require(outputLength > 0 && outputLength <= 255 * HASH_LEN) {
            "Invalid HKDF output length: $outputLength"
        }

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        val okm = ByteArray(outputLength)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1

        try {
            while (offset < outputLength) {
                mac.reset()
                if (t.isNotEmpty()) {
                    mac.update(t)
                }
                if (info.isNotEmpty()) {
                    mac.update(info)
                }
                mac.update(counter.toByte())
                val nextBlock = mac.doFinal()
                t.fill(0)
                t = nextBlock

                val toCopy = minOf(t.size, outputLength - offset)
                System.arraycopy(t, 0, okm, offset, toCopy)
                offset += toCopy
                counter++
            }
            return okm
        } finally {
            t.fill(0)
        }
    }

    fun deriveKey(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int = HASH_LEN
    ): ByteArray {
        val prk = extract(salt, ikm)
        return try {
            expand(prk, info, outputLength)
        } finally {
            prk.fill(0)
        }
    }
}
