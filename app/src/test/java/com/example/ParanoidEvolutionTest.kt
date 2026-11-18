package com.example

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.CoreEngineBackend
import com.example.core.CoreEvent
import com.example.core.CoreNetworkConfig
import com.example.core.HkdfSha256
import com.example.core.KeystoreVault
import com.example.core.MessageVaultCipher
import com.example.core.NodeIdentityManager
import com.example.core.RustNativeBridgeProbe
import com.example.core.SecureClipboardHelper
import com.example.core.SecureFileShredder
import com.example.core.StagingCoreEngine
import com.example.core.StreamingDigestHelper
import com.example.core.VaultSnapshotManager
import com.example.core.XftpDispatchRequest
import com.example.data.AppDatabase
import com.example.data.ChatRepository
import com.example.data.Contact
import com.example.data.Message
import com.example.data.PinCheckResult
import com.example.data.SecurityPreferences
import com.example.ui.ChatViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ParanoidEvolutionTest {

    @Test
    fun `pbkdf2 pin hashing uses unique salt verifies constant time and enforces progressive lockout`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SecurityPreferences(context)
        prefs.clearAll()

        assertFalse(prefs.isPinSet())
        assertFalse(prefs.setPin("123")) // too short
        assertTrue(prefs.setPin("847291"))
        assertTrue(prefs.isPinSet())

        val rawStored1 = context.getSharedPreferences("simplex_secure_prefs", Context.MODE_PRIVATE)
            .getString("sec_pin_hash", "") ?: ""
        assertTrue(rawStored1.startsWith("pbkdf2$"))

        // Re-setting same PIN must produce different random salt
        assertTrue(prefs.setPin("847291"))
        val rawStored2 = context.getSharedPreferences("simplex_secure_prefs", Context.MODE_PRIVATE)
            .getString("sec_pin_hash", "") ?: ""
        assertTrue(rawStored1 != rawStored2)

        assertFalse(prefs.verifyPin("000000"))
        val firstLockMs = prefs.recordFailedPinAttempt()
        val secondLockMs = prefs.recordFailedPinAttempt()
        assertEquals(30_000L, firstLockMs)
        assertEquals(60_000L, secondLockMs)

        // Successful verify resets failed attempt counter
        assertTrue(prefs.verifyPin("847291"))
        assertEquals(0, prefs.getFailedPinAttempts())

        prefs.clearAll()
        assertFalse(prefs.isPinSet())
    }

    @Test
    fun `legacy sha256 pin is verified and transparently upgraded to pbkdf2`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SecurityPreferences(context)
        prefs.clearAll()

        val legacyHash = MessageDigest.getInstance("SHA-256")
            .digest("135790".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        context.getSharedPreferences("simplex_secure_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("sec_pin_hash", legacyHash)
            .apply()

        assertTrue(prefs.verifyPin("135790"))
        val upgraded = context.getSharedPreferences("simplex_secure_prefs", Context.MODE_PRIVATE)
            .getString("sec_pin_hash", "") ?: ""
        assertTrue("Legacy PIN hash should upgrade to PBKDF2", upgraded.startsWith("pbkdf2$"))
    }

    @Test
    fun `streaming sha256 helper hashes multi chunk stream accurately`() {
        val payload = ByteArray(40_000) { idx -> ((idx * 31) and 0xFF).toByte() }
        val expectedHex = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        val (actualHex, size) = StreamingDigestHelper.computeSha256AndSize(ByteArrayInputStream(payload))
        assertEquals(40_000L, size)
        assertEquals(expectedHex, actualHex)
    }

    @Test
    fun `staging core engine enforces 16gb dc cloud threshold and atomic purge`() = runTest {
        val engine = StagingCoreEngine(
            initialConfig = CoreNetworkConfig(
                torSocks5Enabled = true,
                tailscaleEnabled = true,
                dcCloudEnabled = true,
                dcVaultSizeGb = 16,
                dcAutoclear = true
            ),
            nativeLibraryPresent = RustNativeBridgeProbe.isNativeCoreLoaded()
        )
        assertEquals(CoreEngineBackend.KOTLIN_STAGING, engine.status.value.backend)
        assertTrue(engine.status.value.dcServerSideActive)

        val contact = Contact(
            id = "c_test",
            displayName = "Test Peer",
            onionAddress = "simplex://test.onion/abc",
            tailscaleIp = "100.64.0.1",
            publicKey = "KeyEd25519-test"
        )

        val telemetry = mutableListOf<CoreEvent>()
        val collectJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            engine.events.take(5).toList(telemetry)
        }

        val activeResult = engine.dispatchXftpContainer(
            XftpDispatchRequest(
                contact = contact,
                displayName = "vault_bundle.bin",
                sizeBytes = 65_536L,
                sha256Hex = "deadbeef",
                dcCloudEnabled = true,
                dcVaultSizeGb = 16,
                dcAutoclear = true
            )
        )
        collectJob.join()

        assertTrue(activeResult.delivered)
        assertTrue(activeResult.serverSideProcessed)
        assertTrue(activeResult.purgedFromCloud)
        assertTrue(telemetry.any { it is CoreEvent.DcCloudTelemetry && it.message.contains("FDEL(") })

        val bypassResult = engine.dispatchXftpContainer(
            XftpDispatchRequest(
                contact = contact,
                displayName = "small_vault.bin",
                sizeBytes = 1024L,
                sha256Hex = "cafebabe",
                dcCloudEnabled = true,
                dcVaultSizeGb = 8,
                dcAutoclear = true
            )
        )
        assertFalse(bypassResult.serverSideProcessed)
        assertFalse(bypassResult.purgedFromCloud)
    }

    @Test
    fun `main activity enables FLAG_SECURE screenshot shield`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val flags = activity.window.attributes.flags
        assertTrue(
            "FLAG_SECURE must be enabled on MainActivity window",
            (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        )
    }

    @Test
    fun `secure file shredder confines deletions to app sandbox`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sandboxFile = File(context.cacheDir, "sec_doc_test.bin").apply {
            writeText("top-secret-payload")
        }
        val outsideFile = File.createTempFile("outside_guard", ".txt").apply {
            writeText("must-not-touch")
        }

        assertFalse(SecureFileShredder.shredLocalMedia(context.cacheDir, context.filesDir, "content://media/external/images/1"))
        assertFalse(SecureFileShredder.shredLocalMedia(context.cacheDir, context.filesDir, outsideFile.absolutePath))
        assertTrue(outsideFile.exists())

        assertTrue(SecureFileShredder.shredLocalMedia(context.cacheDir, context.filesDir, sandboxFile.absolutePath))
        assertFalse(sandboxFile.exists())
        outsideFile.delete()
    }

    @Test
    fun `ttl pruning and lane shredding clean orphaned local media files`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = ChatRepository(
                chatDao = db.chatDao(),
                shredLocalFile = { path ->
                    SecureFileShredder.shredLocalMedia(context.cacheDir, context.filesDir, path)
                }
            )
            val contact = Contact(
                id = "c_prune",
                displayName = "Ephemeral Peer",
                onionAddress = "simplex://prune.onion/q",
                tailscaleIp = null,
                publicKey = "Ed25519-prune"
            )
            repo.insertContact(contact)

            val expiredFile = File(context.cacheDir, "sec_voice_expired.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val liveFile = File(context.cacheDir, "sec_doc_live.pgp").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }

            val now = 1_000_000L
            repo.insertMessage(
                Message(
                    contactId = contact.id,
                    senderName = "MasterKey",
                    isOutgoing = true,
                    text = "expired",
                    mediaUri = expiredFile.absolutePath,
                    mediaType = "VOICE",
                    ttlExpiry = now - 100
                )
            )
            repo.insertMessage(
                Message(
                    contactId = contact.id,
                    senderName = "MasterKey",
                    isOutgoing = true,
                    text = "live",
                    mediaUri = liveFile.absolutePath,
                    mediaType = "FILE",
                    ttlExpiry = now + 60_000
                )
            )

            val pruned = repo.pruneExpiredMessages(now)
            assertEquals(1, pruned)
            assertFalse("Expired cache file must be shredded", expiredFile.exists())
            assertTrue("Live cache file must stay intact", liveFile.exists())

            repo.shredContactAndLane(contact)
            assertFalse("Live cache file must be shredded on contact shred", liveFile.exists())
            assertTrue(repo.allContacts.first().isEmpty())
            assertTrue(repo.getMessagesForContact(contact.id).first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `keystore vault seals unseals enforces aad and wipes master key`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreVault(context)
        vault.wipeMasterKey()

        val secretSeed = ByteArray(32) { idx -> (idx + 7).toByte() }
        val aad = "paranoid-smp-store-v1".toByteArray()
        val sealed = vault.seal(secretSeed, aad)
        val roundTrip = KeystoreVault.SealedBlob.deserialize(sealed.serialize())!!

        val unsealed = vault.unseal(roundTrip, aad)
        assertArrayEquals(secretSeed, unsealed)

        // Wrong AAD must reject decryption
        assertNull(vault.unseal(roundTrip, "tampered-aad".toByteArray()))

        // Destroying master key invalidates previous sealed blobs
        vault.wipeMasterKey()
        assertNull(vault.unseal(roundTrip, aad))
    }

    @Test
    fun `viewmodel manual lock and ttl preset cycling update state`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ChatViewModel(app)

        vm.submitPin("654321")
        assertTrue(vm.isUnlocked.value)

        vm.lockSession()
        assertFalse(vm.isUnlocked.value)

        assertEquals(24L * 60L * 60L * 1000L, vm.messageTtlMs.value)
        vm.cycleMessageTtlPreset()
        assertEquals(15L * 60L * 1000L, vm.messageTtlMs.value)
        vm.cycleMessageTtlPreset()
        assertEquals(60L * 60L * 1000L, vm.messageTtlMs.value)
    }

    @Test
    fun `duress pin rejects primary collision triggers duress result and cold wipes viewmodel`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = SecurityPreferences(app)
        prefs.clearAll()

        // Cannot arm duress PIN before primary PIN is initialized
        assertFalse(prefs.setDuressPin("999000"))

        assertTrue(prefs.setPin("112233"))
        // Collision with primary PIN must be rejected
        assertFalse(prefs.setDuressPin("112233"))
        assertFalse(prefs.hasDuressPin())

        assertTrue(prefs.setDuressPin("999000"))
        assertTrue(prefs.hasDuressPin())
        // Rotating primary PIN to match duress PIN must also be rejected
        assertFalse(prefs.setPin("999000"))

        assertEquals(PinCheckResult.VALID, prefs.verifyPinWithDuress("112233"))
        assertEquals(PinCheckResult.DURESS_TRIGGERED, prefs.verifyPinWithDuress("999000"))
        assertEquals(PinCheckResult.INVALID, prefs.verifyPinWithDuress("555555"))

        prefs.clearDuressPin()
        assertFalse(prefs.hasDuressPin())
        prefs.clearAll()

        // Full ViewModel integration: entering Duress PIN on lock screen executes cold purge
        val vm = ChatViewModel(app)
        vm.submitPin("246810")
        assertTrue(vm.isUnlocked.value)
        assertTrue(vm.setDuressPin("018642"))
        assertTrue(vm.hasDuressPin.value)

        vm.lockSession()
        assertFalse(vm.isUnlocked.value)

        vm.submitPin("018642")
        assertFalse(vm.isUnlocked.value)
        assertFalse(vm.isPinSet.value)
        assertFalse(vm.hasDuressPin.value)
        assertFalse(vm.secPrefs.isPinSet())
    }

    @Test
    fun `hkdf sha256 matches rfc 5869 test vector 1 and separates domains`() {
        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expectedPrk = "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
        val expectedOkm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

        val actualPrk = HkdfSha256.extract(salt, ikm)
        assertEquals(expectedPrk, actualPrk.toHex())
        val actualOkm = HkdfSha256.deriveKey(ikm, salt, info, 42)
        assertEquals(expectedOkm, actualOkm.toHex())

        val keyDomainA = HkdfSha256.deriveKey(ikm, salt, "domain-a".toByteArray(), 32)
        val keyDomainB = HkdfSha256.deriveKey(ikm, salt, "domain-b".toByteArray(), 32)
        assertFalse(keyDomainA.contentEquals(keyDomainB))
    }

    @Test
    fun `vault snapshot encrypts restores contacts and config and rejects tampering`() {
        val original = VaultSnapshotManager.VaultSnapshot(
            createdAtMs = 1_700_000_000_000L,
            config = VaultSnapshotManager.SnapshotConfig(
                torVpnEnabled = true,
                tailscaleEnabled = false,
                dcCloudEnabled = true,
                dcVaultSizeGb = 32,
                dcAutoclearEnabled = true,
                onionAddress = "simplexv3test1234567890.onion:5223",
                tailscaleIp = "100.77.88.99"
            ),
            contacts = listOf(
                Contact(
                    id = "lane_1",
                    displayName = "Phantom Relay",
                    onionAddress = "simplex://phantom.onion/rx1",
                    tailscaleIp = "100.88.1.2",
                    isBlocked = false,
                    publicKey = "Ed25519-phantom",
                    lastSeenTimestamp = 1_700_000_000_123L
                )
            )
        )

        val envelope = VaultSnapshotManager.encryptSnapshot(original, "correct-horse-battery")
        val restored = VaultSnapshotManager.decryptSnapshot(envelope, "correct-horse-battery")

        assertEquals(original.config, restored.config)
        assertEquals(1, restored.contacts.size)
        assertEquals("Phantom Relay", restored.contacts[0].displayName)
        assertEquals("Ed25519-phantom", restored.contacts[0].publicKey)

        // Wrong passphrase fails GCM authentication
        var wrongRejected = false
        try {
            VaultSnapshotManager.decryptSnapshot(envelope, "wrong-passphrase-value")
        } catch (_: VaultSnapshotManager.SnapshotException) {
            wrongRejected = true
        }
        assertTrue(wrongRejected)

        // Single-bit ciphertext tampering fails GCM authentication
        val tampered = envelope.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()
        var tamperRejected = false
        try {
            VaultSnapshotManager.decryptSnapshot(tampered, "correct-horse-battery")
        } catch (_: VaultSnapshotManager.SnapshotException) {
            tamperRejected = true
        }
        assertTrue(tamperRejected)
    }

    @Test
    fun `secure clipboard marks sensitive extra and wipes on demand`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        SecureClipboardHelper.copySensitive(
            context = context,
            scope = this,
            label = "Onion Host",
            secretText = "simplexv3secret.onion:5223",
            autoClearDelayMs = 60_000L
        )

        val clip = clipboard.primaryClip
        assertTrue(clip != null && clip.itemCount > 0)
        assertEquals("simplexv3secret.onion:5223", clip!!.getItemAt(0).text.toString())
        assertTrue(
            "ClipDescription must mark EXTRA_IS_SENSITIVE",
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        )

        SecureClipboardHelper.wipeNow(context)
        val afterWipe = clipboard.primaryClip
        val cleared = afterWipe == null || afterWipe.itemCount == 0 || afterWipe.getItemAt(0).text.isNullOrEmpty()
        assertTrue("Clipboard must be cleared after wipeNow", cleared)
    }

    @Test
    fun `room message cipher seals text at rest binds contact aad and breaks on master key wipe`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreVault(context)
        vault.wipeMasterKey()
        val cipher = MessageVaultCipher(vault)

        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.chatDao()
            val repo = ChatRepository(
                chatDao = dao,
                encryptMessageText = { cId, plain -> cipher.encryptText(cId, plain) },
                decryptMessageText = { cId, stored -> cipher.decryptText(cId, stored) }
            )

            val secretMessage = "Top secret rendezvous at 23:00"
            repo.insertMessage(
                Message(
                    contactId = "peer_alpha",
                    senderName = "MasterKey",
                    isOutgoing = true,
                    text = secretMessage
                )
            )

            // Raw SQLite DAO row must contain AES-256-GCM envelope, never plaintext
            val rawRows = dao.getMessagesForContact("peer_alpha").first()
            assertEquals(1, rawRows.size)
            assertTrue(rawRows[0].text.startsWith("enc:v1:"))
            assertFalse(rawRows[0].text.contains(secretMessage))

            // Cross-contact AAD mismatch rejects decryption
            assertEquals(MessageVaultCipher.WIPED_PLACEHOLDER, cipher.decryptText("peer_beta", rawRows[0].text))

            // Repository Flow transparently decrypts for legitimate peer
            val decryptedRows = repo.getMessagesForContact("peer_alpha").first()
            assertEquals(secretMessage, decryptedRows[0].text)

            // Wiping master key immediately renders stored DB ciphertext unrecoverable
            vault.wipeMasterKey()
            val afterWipeRows = repo.getMessagesForContact("peer_alpha").first()
            assertEquals(MessageVaultCipher.WIPED_PLACEHOLDER, afterWipeRows[0].text)
        } finally {
            db.close()
        }
    }

    @Test
    fun `node identity manager generates wrapped ec p256 key signs verifies and rotates fingerprint`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreVault(context)
        val identity = NodeIdentityManager(context, vault)
        identity.wipeIdentity()

        val fp1 = identity.getLocalFingerprint()
        val pubB64 = identity.getPublicKeyBase64()
        assertTrue(fp1.isNotBlank())
        assertTrue(pubB64.isNotBlank())

        val challenge = "simplex-auth-nonce-01".toByteArray()
        val signature = identity.signChallenge(challenge)!!
        assertTrue(identity.verifySignature(pubB64, challenge, signature))
        assertFalse(identity.verifySignature(pubB64, "tampered-nonce".toByteArray(), signature))

        val safetyA = identity.computeSafetyNumber("PeerEd25519-ABC")
        assertEquals(12, safetyA.split(' ').size)
        assertEquals(safetyA, identity.computeSafetyNumber("PeerEd25519-ABC"))

        identity.rotateIdentity()
        val fp2 = identity.getLocalFingerprint()
        assertFalse("Rotating identity must produce a distinct fingerprint", fp1 == fp2)
        assertFalse("Safety number must change after identity rotation", safetyA == identity.computeSafetyNumber("PeerEd25519-ABC"))
    }
}
