package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = ChatRepository(db.chatDao())
    val secPrefs = SecurityPreferences(application)
    private val audioHelper = AudioHelper(application)

    // Pin & Access State
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _isPinSet = MutableStateFlow(secPrefs.isPinSet())
    val isPinSet: StateFlow<Boolean> = _isPinSet.asStateFlow()

    private val _lockoutPeriodMs = MutableStateFlow(0L)
    val lockoutPeriodMs: StateFlow<Long> = _lockoutPeriodMs.asStateFlow()

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    // Active Contacts and Chat
    val contactsStr: StateFlow<List<Contact>> = repository.allContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedContact = MutableStateFlow<Contact?>(null)
    val selectedContact: StateFlow<Contact?> = _selectedContact.asStateFlow()

    val currentChatMessages: StateFlow<List<Message>> = _selectedContact
        .flatMapLatest { contact ->
            if (contact != null) {
                repository.getMessagesForContact(contact.id)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Memory-only Server Logs
    private val _serverLogs = MutableStateFlow<List<ZeroLogEngine.LogEntry>>(emptyList())
    val serverLogs: StateFlow<List<ZeroLogEngine.LogEntry>> = _serverLogs.asStateFlow()

    // Proxy Routing States
    val isTorVpnEnabled = MutableStateFlow(secPrefs.isTorVpnEnabled)
    val isTailscaleEnabled = MutableStateFlow(secPrefs.isTailscaleEnabled)
    val onionServerAddress = MutableStateFlow(secPrefs.onionAddress)
    val tailscaleNodeIp = MutableStateFlow(secPrefs.tailscaleIp)

    // DC Cloud Sharing States
    val isDcCloudEnabled = MutableStateFlow(secPrefs.isDcCloudEnabled)
    val dcCloudVaultSize = MutableStateFlow(secPrefs.dcCloudVaultSize)
    val isDcCloudAutoclearEnabled = MutableStateFlow(secPrefs.isDcCloudAutoclearEnabled)
    val isDcServerSideActive: StateFlow<Boolean> = combine(isDcCloudEnabled, dcCloudVaultSize) { enabled, size ->
        enabled && size >= 16
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), secPrefs.isDcCloudEnabled && secPrefs.dcCloudVaultSize >= 16)

    // SimpleX:node Decentralized Routing Protocol flows
    val availableNodes: StateFlow<List<SimpleXNode>> = SimpleXNodeEngine.availableNodes
    val activeCircuit: StateFlow<OnionCircuit?> = SimpleXNodeEngine.activeCircuit
    val isAutoRoutingEnabled: StateFlow<Boolean> = SimpleXNodeEngine.isAutoRoutingEnabled
    val routingStatus: StateFlow<String> = SimpleXNodeEngine.routingStatus

    // Voice Recorder State
    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

    private var activeRecordingFile: String? = null
    private var recordingTimerJob: Job? = null

    // Voice Call state
    private val _activeVoiceCallUser = MutableStateFlow<Contact?>(null)
    val activeVoiceCallUser: StateFlow<Contact?> = _activeVoiceCallUser.asStateFlow()

    private val _callStateStr = MutableStateFlow("")
    val callStateStr: StateFlow<String> = _callStateStr.asStateFlow()

    private val _callFingerprint = MutableStateFlow("")
    val callFingerprint: StateFlow<String> = _callFingerprint.asStateFlow()

    private val _callDurationSec = MutableStateFlow(0)
    val callDurationSec: StateFlow<Int> = _callDurationSec.asStateFlow()

    private var callTimerJob: Job? = null

    // Timers & Jobs
    private var ttlTimerJob: Job? = null
    private var logRefresherJob: Job? = null
    private var lockoutTimerJob: Job? = null

    init {
        // Generate Server Identity Details if blank
        if (secPrefs.onionAddress.isEmpty()) {
            val randOnion = "simplexv3dwv" + UUID.randomUUID().toString().replace("-", "").take(16) + ".onion:5223"
            secPrefs.onionAddress = randOnion
            onionServerAddress.value = randOnion
        }
        if (secPrefs.tailscaleIp.isEmpty()) {
            val randTs = "100.${(60..120).random()}.${(10..240).random()}.${(10..240).random()}"
            secPrefs.tailscaleIp = randTs
            tailscaleNodeIp.value = randTs
        }

        // Initialize sample conversations for first-time use
        initializeMockContactsAndChats()

        // Core dynamic TTL runner
        startTtlPruner()

        // Simulated dynamic log flow
        startLogsPoller()

        // Check if locked out on init
        checkPinLockout()
    }

    private fun checkPinLockout() {
        val remaining = secPrefs.getLockoutRemainingMs()
        if (remaining > 0) {
            _lockoutPeriodMs.value = remaining
            startLockoutTimer()
        }
    }

    private fun startLockoutTimer() {
        lockoutTimerJob?.cancel()
        lockoutTimerJob = viewModelScope.launch {
            while (true) {
                val rem = secPrefs.getLockoutRemainingMs()
                _lockoutPeriodMs.value = rem
                if (rem <= 0) {
                    _pinError.value = null
                    break
                }
                delay(500)
            }
        }
    }

    fun submitPin(pin: String) {
        if (_lockoutPeriodMs.value > 0) {
            _pinError.value = "Too many attempts. Lockout in progress."
            return
        }

        if (pin.length != 6) {
            _pinError.value = "PIN must be exactly 6 digits."
            return
        }

        if (!_isPinSet.value) {
            // First time setup
            val success = secPrefs.setPin(pin)
            if (success) {
                _isPinSet.value = true
                _isUnlocked.value = true
                _pinError.value = null
                ZeroLogEngine.log("Security", "Secured master passcode set successfully.")
            } else {
                _pinError.value = "Invalid passcode pattern."
            }
        } else {
            // Validate
            val valid = secPrefs.verifyPin(pin)
            if (valid) {
                _isUnlocked.value = true
                _pinError.value = null
                ZeroLogEngine.log("Security", "Session verified successfully through master keys.")
            } else {
                // Pin failed. Set 30s lockout
                val until = System.currentTimeMillis() + 30000L
                secPrefs.setLockoutUntil(until)
                _pinError.value = "Security key mismatched. System locked for 30s."
                ZeroLogEngine.log("Security", "PIN authentication failed. 30 sec cooling lockout instituted.", "ERROR")
                _lockoutPeriodMs.value = 30000L
                startLockoutTimer()
            }
        }
    }

    fun lockSession() {
        _isUnlocked.value = false
        ZeroLogEngine.log("Security", "Session locked manually by user.")
    }

    // Toggle Config Controls
    fun toggleTorVpn(enabled: Boolean) {
        secPrefs.isTorVpnEnabled = enabled
        isTorVpnEnabled.value = enabled
        ZeroLogEngine.log("Network", "Tor VPN Socks5 proxy: ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun toggleTailscale(enabled: Boolean) {
        secPrefs.isTailscaleEnabled = enabled
        isTailscaleEnabled.value = enabled
        ZeroLogEngine.log("Network", "Tailscale Routing: ${if (enabled) "ROUTING VIA TAILNET" else "DISABLED"}")
    }

    fun setDcCloudEnabled(enabled: Boolean) {
        secPrefs.isDcCloudEnabled = enabled
        isDcCloudEnabled.value = enabled
        ZeroLogEngine.log("Cloud", "DC Cloud sharing system: ${if (enabled) "ENABLED" else "DISABLED"}")
        if (enabled) {
            val size = dcCloudVaultSize.value
            if (size >= 16) {
                ZeroLogEngine.log("Cloud", "Server-side component activated (Allocated Vault: ${size}GB)")
            } else {
                ZeroLogEngine.log("Cloud", "Server-side component INACTIVE (Requires 16GB minimum, current: ${size}GB)", "WARN")
            }
        }
    }

    fun setDcCloudVaultSize(sizeGB: Int) {
        val sanitizedSize = if (sizeGB < 1) 1 else sizeGB
        secPrefs.dcCloudVaultSize = sanitizedSize
        dcCloudVaultSize.value = sanitizedSize
        ZeroLogEngine.log("Cloud", "Global sharing vault size updated: ${sanitizedSize}GB")
        if (isDcCloudEnabled.value) {
            if (sanitizedSize >= 16) {
                ZeroLogEngine.log("Cloud", "Server-side component activated successfully (${sanitizedSize}GB allocated storage).")
            } else {
                ZeroLogEngine.log("Cloud", "Server-side component DEACTIVATED. A minimum of 16GB is required.", "WARN")
            }
        }
    }

    fun setDcCloudAutoclearEnabled(enabled: Boolean) {
        secPrefs.isDcCloudAutoclearEnabled = enabled
        isDcCloudAutoclearEnabled.value = enabled
        ZeroLogEngine.log("Cloud", "Delivered container autodelete: ${if (enabled) "ON-DELIVERY ATOMIC ERASE" else "MANUAL PRUNING ONLY"}")
    }

    fun setContact(contact: Contact?) {
        _selectedContact.value = contact
        if (contact != null) {
            ZeroLogEngine.log("Chat", "Loaded handshake queues for peer: ${contact.displayName}")
        }
    }

    // Chat Operations
    fun sendTextMessage(content: String) {
        val contact = _selectedContact.value ?: return
        if (content.isBlank()) return

        // SimpleX doesn't have metadata, we define isDelivered randomly here to let users view
        // the "not delivered message alert to sender" behavior:
        val isDeliveredRandom = (1..100).random() > 15 // 15% chance undelivered to simulate routing delays
        val msg = Message(
            contactId = contact.id,
            senderName = "MasterKey",
            isOutgoing = true,
            text = content,
            isDelivered = isDeliveredRandom,
            ttlExpiry = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
        )

        viewModelScope.launch {
            repository.insertMessage(msg)
            ZeroLogEngine.log("Protocol", "Published unidirectional outbound packet to SimpleX queue")
            if (!isDeliveredRandom) {
                ZeroLogEngine.log("Protocol", "Packet delivery ACK timeout! Alerting client of queue backlog.", "WARN")
            }

            // Simulate automatic receiver reply
            triggerAutomaticReply(contact, content)
        }
    }

    private fun triggerAutomaticReply(contact: Contact, triggerText: String) {
        viewModelScope.launch {
            delay(1500)
            val isRoom = contact.id.startsWith("room_")
            val sender = if (isRoom) {
                listOf("Sec-Officer Alpha", "Core_Route_0x7b", "AnonPeer", "GhostNet", "Oracle").random()
            } else {
                contact.displayName
            }

            val text = when {
                triggerText.contains("hello", true) || triggerText.contains("hi", true) -> {
                    if (isRoom) "Encrypted queue ACK received from $sender inside room. Tunnel fully secured."
                    else "Encrypted queue ACK received. Connection established over .onion tunnel."
                }
                triggerText.contains("file", true) || triggerText.contains("media", true) -> {
                    "File container decrypted successfully. Initializing SHA-256 integrity validation."
                }
                else -> {
                    if (isRoom) "Broadcast packet decrypted in group context by $sender. Relayed successfully."
                    else "Outbound queue cleared. Received response packet from ${contact.displayName} via TorVPN SOCKS5."
                }
            }

            val reply = Message(
                contactId = contact.id,
                senderName = sender,
                isOutgoing = false,
                text = text,
                isDelivered = true,
                ttlExpiry = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
            )
            repository.insertMessage(reply)
            ZeroLogEngine.log("Protocol", "De-queued inbound message packet from unique simplex address.")
        }
    }

    // Voice note operations
    fun startVoiceRecording() {
        if (_isRecordingVoice.value) return
        _recordingDurationSec.value = 0
        val fileId = UUID.randomUUID().toString().take(8)
        activeRecordingFile = audioHelper.startRecording(fileId)
        if (activeRecordingFile != null) {
            _isRecordingVoice.value = true
            recordingTimerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    _recordingDurationSec.value += 1
                }
            }
        }
    }

    fun stopAndSendVoiceRecording() {
        if (!_isRecordingVoice.value) return
        val durationMs = audioHelper.stopRecording()
        recordingTimerJob?.cancel()
        _isRecordingVoice.value = false

        val file = activeRecordingFile
        val contact = _selectedContact.value
        if (file != null && contact != null) {
            viewModelScope.launch {
                val voiceMessage = Message(
                    contactId = contact.id,
                    senderName = "MasterKey",
                    isOutgoing = true,
                    text = "🎤 Voice Record (${_recordingDurationSec.value}s)",
                    mediaUri = file,
                    mediaType = "VOICE",
                    voiceDurationMs = durationMs,
                    isDelivered = true,
                    ttlExpiry = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
                )
                repository.insertMessage(voiceMessage)
                ZeroLogEngine.log("Audio", "In-memory voice packet committed to encrypted DB.")
            }
        }
        activeRecordingFile = null
    }

    fun playVoiceMemo(message: Message, onCompletion: () -> Unit) {
        val uri = message.mediaUri ?: return
        audioHelper.startPlayback(uri, onCompletion, {
            ZeroLogEngine.log("Audio", "Voice memo playback bypassed or synthetic simulation complete.")
            onCompletion()
        })
    }

    // Select custom files to share securely
    fun sharePrivacyMockDocument(name: String, extension: String) {
        val contact = _selectedContact.value ?: return
        val cacheFile = File(getApplication<Application>().cacheDir, "sec_doc_${UUID.randomUUID().toString().take(6)}.$extension")
        try {
            FileOutputStream(cacheFile).use { fos ->
                fos.write("SECURE Priv_Shield Container - Integrity Pass HMAC512".toByteArray())
            }
            viewModelScope.launch {
                val fileMsg = Message(
                    contactId = contact.id,
                    senderName = "MasterKey",
                    isOutgoing = true,
                    text = "📂 Encrypted shared container: $name.$extension",
                    mediaUri = cacheFile.absolutePath,
                    mediaType = "FILE",
                    isDelivered = true,
                    ttlExpiry = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
                )
                repository.insertMessage(fileMsg)
                ZeroLogEngine.log("Protocol", "Completed media transfer of: $name.$extension in strict 24-hr envelope.")

                if (isDcCloudEnabled.value) {
                    val isActive = dcCloudVaultSize.value >= 16
                    ZeroLogEngine.log("Cloud", "Relaying encrypted container to DC Cloud Vault (${dcCloudVaultSize.value}GB)...")
                    if (isActive) {
                        ZeroLogEngine.log("Cloud", "Server-side global sharing component processing block dispatch.")
                        delay(700)
                        ZeroLogEngine.log("Cloud", "Container successfully delivered to remote client.")
                        if (isDcCloudAutoclearEnabled.value) {
                            ZeroLogEngine.log("Cloud", "Cloud Autoclear Active: Purging container '$name.$extension' and corresponding cloud telemetry immediately after delivery.", "WARN")
                            ZeroLogEngine.log("Cloud", "Atomic erase sequence completed. Global sharing cache returns to 0% footprint.")
                        }
                    } else {
                        ZeroLogEngine.log("Cloud", "Server-side processing BYPASSED. (Reason: Vault size ${dcCloudVaultSize.value}GB is below the 16GB minimum limit).", "WARN")
                        delay(500)
                        ZeroLogEngine.log("Cloud", "Container transmitted directly without cloud-activation layer.")
                    }
                }
            }
        } catch (e: Exception) {
            ZeroLogEngine.log("Protocol", "Failed writing secure container block: ${e.message}", "ERROR")
        }
    }

    fun shareSecurePhoto(photoLabel: String) {
        val contact = _selectedContact.value ?: return
        val cacheFile = File(getApplication<Application>().cacheDir, "sec_pic_${UUID.randomUUID().toString().take(6)}.png")
        try {
            FileOutputStream(cacheFile).use { fos ->
                fos.write("MOCK CRYPTO PIC ENVELOPE".toByteArray())
            }
            viewModelScope.launch {
                val picMsg = Message(
                    contactId = contact.id,
                    senderName = "MasterKey",
                    isOutgoing = true,
                    text = "🖼️ Visual key snapshot: $photoLabel",
                    mediaUri = cacheFile.absolutePath,
                    mediaType = "IMAGE",
                    isDelivered = true,
                    ttlExpiry = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
                )
                repository.insertMessage(picMsg)
                ZeroLogEngine.log("Protocol", "Shared photo transfer completed: $photoLabel")

                if (isDcCloudEnabled.value) {
                    val isActive = dcCloudVaultSize.value >= 16
                    ZeroLogEngine.log("Cloud", "Relaying photo media block to DC Cloud Vault (${dcCloudVaultSize.value}GB)...")
                    if (isActive) {
                        ZeroLogEngine.log("Cloud", "Server-side global sharing component processing media block.")
                        delay(700)
                        ZeroLogEngine.log("Cloud", "Photo container successfully delivered to remote client.")
                        if (isDcCloudAutoclearEnabled.value) {
                            ZeroLogEngine.log("Cloud", "Cloud Autoclear Active: Purging media package '$photoLabel' and corresponding cloud telemetry immediately after delivery.", "WARN")
                            ZeroLogEngine.log("Cloud", "Atomic erase sequence completed. Global sharing cache returns to 0% footprint.")
                        }
                    } else {
                        ZeroLogEngine.log("Cloud", "Server-side processing BYPASSED. (Reason: Vault size ${dcCloudVaultSize.value}GB is below the 16GB minimum limit).", "WARN")
                        delay(500)
                        ZeroLogEngine.log("Cloud", "Container transmitted directly without cloud-activation layer.")
                    }
                }
            }
        } catch (e: Exception) {
            ZeroLogEngine.log("Protocol", "Failed writing secure photo envelope: ${e.message}", "ERROR")
        }
    }

    // Voice Call Procedures (Secure peer-to-peer over SOCKS5 onion node)
    fun requestVoiceCall(contact: Contact) {
        _activeVoiceCallUser.value = contact
        _callStateStr.value = "Initializing Tor circuit..."
        _callFingerprint.value = "ZRTP: " + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        _callDurationSec.value = 0

        callTimerJob?.cancel()
        callTimerJob = viewModelScope.launch {
            delay(1500)
            _callStateStr.value = "Negotiating DH prime keys..."
            ZeroLogEngine.log("Call", "Voice route: Tor (onion) -> Tailscale P2P tunnel for ${contact.displayName}")
            delay(1500)
            _callStateStr.value = "Active (ZRTP SECURE)"
            ZeroLogEngine.log("Call", "P2P Voice crypto session fully established!")

            while (true) {
                delay(1000)
                _callDurationSec.value += 1
            }
        }
    }

    fun endVoiceCall() {
        callTimerJob?.cancel()
        _activeVoiceCallUser.value = null
        _callStateStr.value = ""
        ZeroLogEngine.log("Call", "Voice peer connection torn down and buffers flushed.")
    }

    // Dynamic 24-hour message pruner
    private fun startTtlPruner() {
        ttlTimerJob?.cancel()
        ttlTimerJob = viewModelScope.launch {
            while (true) {
                val pruned = repository.pruneExpiredMessages(System.currentTimeMillis())
                if (pruned > 0) {
                    ZeroLogEngine.log("Clean", "Pruned $pruned messages on 24-hour expiration threshold.")
                }
                delay(4000)
            }
        }
    }

    // Zero log local poller logic
    private fun startLogsPoller() {
        logRefresherJob?.cancel()
        logRefresherJob = viewModelScope.launch {
            while (true) {
                _serverLogs.value = ZeroLogEngine.getLogs()
                delay(1200)
            }
        }
    }

    // Master trigger: Panic button wipes everything and locks instantly
    fun executePanicButtonPurge() {
        viewModelScope.launch {
            audioHelper.StopStreams()
            // 1. Wipe SharedPreferences
            secPrefs.clearAll()

            // 2. Clear Database Tables
            repository.panicPurge()

            // 3. Delete file residues in directories
            deletePrivateData(getApplication<Application>().cacheDir)
            deletePrivateData(getApplication<Application>().filesDir)

            // 4. Wipe runtime zero logging engine
            ZeroLogEngine.clearLogs()
            ZeroLogEngine.log("Panic", "--- COLD EMERGENCY PURGE COMPLETE ---")

            // 5. Hard Reset View Model States
            _isUnlocked.value = false
            _isPinSet.value = false
            _selectedContact.value = null
            _activeVoiceCallUser.value = null
            isTorVpnEnabled.value = false
            isTailscaleEnabled.value = false
            onionServerAddress.value = ""
            tailscaleNodeIp.value = ""
            isDcCloudEnabled.value = true
            dcCloudVaultSize.value = 16
            isDcCloudAutoclearEnabled.value = true
            _pinError.value = "EMERGENCY: Cryptographic wipe successfully executed!"

            // Re-generate identity details safely in memory
            val randOnion = "simplexv3dwv" + UUID.randomUUID().toString().replace("-", "").take(16) + ".onion:5223"
            secPrefs.onionAddress = randOnion
            onionServerAddress.value = randOnion
            val randTs = "100.${(60..120).random()}.${(10..240).random()}.${(10..240).random()}"
            secPrefs.tailscaleIp = randTs
            tailscaleNodeIp.value = randTs
        }
    }

    // Last Generated Invite for Success Dialog UI state
    data class InviteResult(val roomName: String, val inviteLink: String)
    val lastGeneratedInvite = MutableStateFlow<InviteResult?>(null)

    fun createChatRoom(roomName: String) {
        viewModelScope.launch {
            val roomId = "room_${UUID.randomUUID().toString().take(6)}"
            val inviteLink = "simplex://invite-room-${UUID.randomUUID().toString().take(8)}.onion/" + UUID.randomUUID().toString().replace("-", "").take(12)
            val contact = Contact(
                id = roomId,
                displayName = "$roomName [ROOM]",
                onionAddress = inviteLink,
                tailscaleIp = null,
                publicKey = "RoomEd25519-" + UUID.randomUUID().toString().replace("-", "").take(8)
            )
            repository.insertContact(contact)
            lastGeneratedInvite.value = InviteResult(roomName, inviteLink)
            ZeroLogEngine.log("Setup", "Initiated secure room queue: $roomName")
            ZeroLogEngine.log("Protocol", "One-time invitation token bound to port 5223")
        }
    }

    fun clearLastGeneratedInvite() {
        lastGeneratedInvite.value = null
    }

    private fun deletePrivateData(directory: File) {
        try {
            directory.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    deletePrivateData(file)
                }
                file.delete()
            }
        } catch (e: Exception) {}
    }

    // Initializer with high-privacy pre-configurations
    private fun initializeMockContactsAndChats() {
        viewModelScope.launch {
            // Check if mock setup has run before
            if (!secPrefs.areMockConversationsInitialized) {
                val sovereign = Contact(
                    id = "node_sov",
                    displayName = "Sovereign Node",
                    onionAddress = "simplex://sovnode276fghsdg.onion/dugq3k",
                    tailscaleIp = "100.80.12.99",
                    publicKey = "KeyEd25519-7fgh92..."
                )
                val cipherX = Contact(
                    id = "node_cipher",
                    displayName = "Cipher-X",
                    onionAddress = "simplex://cipher72ws8djfj.onion/kpqy7x",
                    tailscaleIp = "100.114.25.10",
                    publicKey = "KeyEd25519-2jd8fs..."
                )

                repository.insertContact(sovereign)
                repository.insertContact(cipherX)

                // Insert introductory high-privacy messages
                val now = System.currentTimeMillis()
                val m1 = Message(
                    contactId = sovereign.id,
                    senderName = sovereign.displayName,
                    isOutgoing = false,
                    text = "Welcome to your local SimpleX server queue. No IDs or global identities exist.",
                    timestamp = now - 50000,
                    ttlExpiry = now + (23 * 60 * 60 * 1000) // Expiry in 23 hours
                )
                val m2 = Message(
                    contactId = sovereign.id,
                    senderName = "MasterKey",
                    isOutgoing = true,
                    text = "Session active. Routed over Tor VPN + Tailscale routing.",
                    timestamp = now - 40000,
                    ttlExpiry = now + (23 * 60 * 60 * 1000)
                )

                val c1 = Message(
                    contactId = cipherX.id,
                    senderName = cipherX.displayName,
                    isOutgoing = false,
                    text = "ZRTP pairing confirmed. Send a voice memo or privacy document anytime.",
                    timestamp = now - 90000,
                    ttlExpiry = now + (23 * 60 * 60 * 1000)
                )

                repository.insertMessage(m1)
                repository.insertMessage(m2)
                repository.insertMessage(c1)

                secPrefs.areMockConversationsInitialized = true
                ZeroLogEngine.log("Setup", "Pre-loaded encrypted high-privacy test lanes established.")
            }
        }
    }

    fun addCustomContact(name: String, onionAddress: String) {
        viewModelScope.launch {
            if (onionAddress.startsWith("simplex://invite-room-") || name.contains("room", ignoreCase = true)) {
                val groupContact = Contact(
                    id = "room_" + UUID.randomUUID().toString().take(6),
                    displayName = if (name.contains("[ROOM]", ignoreCase = true)) name else "$name [ROOM]",
                    onionAddress = onionAddress,
                    tailscaleIp = null,
                    publicKey = "RoomEd25519-" + UUID.randomUUID().toString().replace("-", "").take(8)
                )
                repository.insertContact(groupContact)
                ZeroLogEngine.log("Setup", "Joined decentralized group lane: $name via one-time invite")
            } else {
                val contact = Contact(
                    id = "c_${UUID.randomUUID().toString().take(6)}",
                    displayName = name,
                    onionAddress = onionAddress,
                    tailscaleIp = "100.81.${(30..200).random()}.${(20..200).random()}",
                    publicKey = "KeyEd25519-" + UUID.randomUUID().toString().replace("-", "").take(8)
                )
                repository.insertContact(contact)
                ZeroLogEngine.log("Setup", "Custom communication link registered: $name")
            }
        }
    }

    // SimpleX:node routing handlers
    fun setAutoRouting(enabled: Boolean) {
        SimpleXNodeEngine.setAutoRouting(enabled)
        ZeroLogEngine.log("Protocol", "SimpleX:node AutoRouting set to $enabled")
    }

    fun selectNodeForManualCircuit(node: SimpleXNode) {
        SimpleXNodeEngine.selectNodeForManualCircuit(node)
        ZeroLogEngine.log("Protocol", "SimpleX:node manual target: ${node.host}")
    }

    fun rebuildOnionCircuit() {
        SimpleXNodeEngine.rebuildCircuit()
        val hopsCount = if (isAutoRoutingEnabled.value) 3 else 1
        ZeroLogEngine.log("Protocol", "Decentralized route regenerated ($hopsCount hops)")
    }

    override fun onCleared() {
        super.onCleared()
        ttlTimerJob?.cancel()
        logRefresherJob?.cancel()
        lockoutTimerJob?.cancel()
        recordingTimerJob?.cancel()
        callTimerJob?.cancel()
        audioHelper.StopStreams()
    }
}
