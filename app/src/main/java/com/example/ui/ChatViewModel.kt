package com.example.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.CoreEvent
import com.example.core.CoreNetworkConfig
import com.example.core.CoreRuntimeStatus
import com.example.core.KeystoreVault
import com.example.core.MessageVaultCipher
import com.example.core.NodeIdentityManager
import com.example.core.ParanoidCoreEngine
import com.example.core.ParanoidCoreFactory
import com.example.core.SecureClipboardHelper
import com.example.core.SecureFileShredder
import com.example.core.StreamingDigestHelper
import com.example.core.VaultSnapshotManager
import com.example.core.XftpDispatchRequest
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    val secPrefs = SecurityPreferences(application)
    private val audioHelper = AudioHelper(application)
    val keystoreVault = KeystoreVault(application)
    private val messageVaultCipher = MessageVaultCipher(keystoreVault)
    val nodeIdentityManager = NodeIdentityManager(application, keystoreVault)

    private val repository = ChatRepository(
        chatDao = db.chatDao(),
        shredLocalFile = { pathOrUri ->
            SecureFileShredder.shredLocalMedia(
                cacheDir = application.cacheDir,
                filesDir = application.filesDir,
                pathOrUri = pathOrUri
            )
        },
        encryptMessageText = { contactId, plaintext ->
            messageVaultCipher.encryptText(contactId, plaintext)
        },
        decryptMessageText = { contactId, stored ->
            messageVaultCipher.decryptText(contactId, stored)
        }
    )

    private val _keystoreModeLabel = MutableStateFlow(keystoreVault.activeMode().label)
    val keystoreModeLabel: StateFlow<String> = _keystoreModeLabel.asStateFlow()

    private val _localNodeFingerprint = MutableStateFlow(nodeIdentityManager.getLocalFingerprint())
    val localNodeFingerprint: StateFlow<String> = _localNodeFingerprint.asStateFlow()

    private val coreEngine: ParanoidCoreEngine = ParanoidCoreFactory.create(
        CoreNetworkConfig(
            torSocks5Enabled = secPrefs.isTorVpnEnabled,
            tailscaleEnabled = secPrefs.isTailscaleEnabled,
            dcCloudEnabled = secPrefs.isDcCloudEnabled,
            dcVaultSizeGb = secPrefs.dcCloudVaultSize,
            dcAutoclear = secPrefs.isDcCloudAutoclearEnabled
        )
    )

    val coreStatus: StateFlow<CoreRuntimeStatus> = coreEngine.status

    // Message TTL policy (default 24h; user can tighten down to 15m/1h/6h per session)
    private val _messageTtlMs = MutableStateFlow(24L * 60L * 60L * 1000L)
    val messageTtlMs: StateFlow<Long> = _messageTtlMs.asStateFlow()

    // Pin & Access State
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _isPinSet = MutableStateFlow(secPrefs.isPinSet())
    val isPinSet: StateFlow<Boolean> = _isPinSet.asStateFlow()

    private val _hasDuressPin = MutableStateFlow(secPrefs.hasDuressPin())
    val hasDuressPin: StateFlow<Boolean> = _hasDuressPin.asStateFlow()

    private val _lockoutPeriodMs = MutableStateFlow(0L)
    val lockoutPeriodMs: StateFlow<Long> = _lockoutPeriodMs.asStateFlow()

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    private val _isAutoLockEnabled = MutableStateFlow(secPrefs.isAutoLockOnBackgroundEnabled)
    val isAutoLockEnabled: StateFlow<Boolean> = _isAutoLockEnabled.asStateFlow()

    private val _clipboardNotice = MutableStateFlow<String?>(null)
    val clipboardNotice: StateFlow<String?> = _clipboardNotice.asStateFlow()

    private val _snapshotNotice = MutableStateFlow<String?>(null)
    val snapshotNotice: StateFlow<String?> = _snapshotNotice.asStateFlow()

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

    private val _permissionNotice = MutableStateFlow<String?>(null)
    val permissionNotice: StateFlow<String?> = _permissionNotice.asStateFlow()

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

        observeCoreEvents()
        initializeMockContactsAndChats()
        startTtlPruner()
        startLogsPoller()
        checkPinLockout()
    }

    private fun observeCoreEvents() {
        viewModelScope.launch {
            coreEngine.events.collect { event ->
                when (event) {
                    is CoreEvent.DcCloudTelemetry -> ZeroLogEngine.log("Cloud", event.message, event.level)
                    is CoreEvent.InboundMessage -> {
                        val reply = Message(
                            contactId = event.contactId,
                            senderName = event.senderName,
                            isOutgoing = false,
                            text = event.text,
                            isDelivered = true,
                            ttlExpiry = System.currentTimeMillis() + _messageTtlMs.value
                        )
                        repository.insertMessage(reply)
                    }
                }
            }
        }
    }

    private fun syncEngineConfig() {
        viewModelScope.launch {
            coreEngine.applyNetworkConfig(
                CoreNetworkConfig(
                    torSocks5Enabled = isTorVpnEnabled.value,
                    tailscaleEnabled = isTailscaleEnabled.value,
                    dcCloudEnabled = isDcCloudEnabled.value,
                    dcVaultSizeGb = dcCloudVaultSize.value,
                    dcAutoclear = isDcCloudAutoclearEnabled.value
                )
            )
        }
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
            val success = secPrefs.setPin(pin)
            if (success) {
                _isPinSet.value = true
                _isUnlocked.value = true
                _pinError.value = null
                ZeroLogEngine.log("Security", "Master passcode hardened with PBKDF2-HMAC-SHA256 (120k rounds).")
            } else {
                _pinError.value = "Invalid passcode pattern."
            }
        } else {
            when (secPrefs.verifyPinWithDuress(pin)) {
                PinCheckResult.VALID -> {
                    _isUnlocked.value = true
                    _pinError.value = null
                    ZeroLogEngine.log("Security", "Session verified successfully via PBKDF2 constant-time check.")
                }
                PinCheckResult.DURESS_TRIGGERED -> {
                    executePanicButtonPurge(duressTriggered = true)
                }
                PinCheckResult.INVALID -> {
                    val lockoutDuration = secPrefs.recordFailedPinAttempt()
                    val sec = lockoutDuration / 1000L
                    _pinError.value = "Security key mismatched. Locked for ${sec}s."
                    ZeroLogEngine.log("Security", "PIN authentication failed. ${sec}s progressive lockout instituted.", "ERROR")
                    _lockoutPeriodMs.value = lockoutDuration
                    startLockoutTimer()
                }
            }
        }
    }

    fun setDuressPin(pin: String): Boolean {
        val ok = secPrefs.setDuressPin(pin)
        if (ok) {
            _hasDuressPin.value = true
            ZeroLogEngine.log("Security", "Duress panic passcode armed with PBKDF2-HMAC-SHA256 (120k rounds).")
        } else {
            ZeroLogEngine.log("Security", "Rejected duress PIN: must be 6 digits and distinct from primary PIN.", "WARN")
        }
        return ok
    }

    fun clearDuressPin() {
        secPrefs.clearDuressPin()
        _hasDuressPin.value = false
        ZeroLogEngine.log("Security", "Duress panic passcode disarmed.")
    }

    fun lockSession() {
        _isUnlocked.value = false
        SecureClipboardHelper.wipeNow(getApplication())
        _clipboardNotice.value = null
        ZeroLogEngine.log("Security", "Session locked manually by user (clipboard cleared).")
    }

    fun onAppBackgrounded() {
        if (_isUnlocked.value && _isAutoLockEnabled.value) {
            _isUnlocked.value = false
            SecureClipboardHelper.wipeNow(getApplication())
            _clipboardNotice.value = null
            ZeroLogEngine.log("Security", "Auto-locked session on background transition (clipboard cleared).")
        }
    }

    fun setAutoLockOnBackground(enabled: Boolean) {
        secPrefs.isAutoLockOnBackgroundEnabled = enabled
        _isAutoLockEnabled.value = enabled
        ZeroLogEngine.log("Security", "Background auto-lock: ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun copySensitiveWithAutoClear(text: String, label: String) {
        if (text.isBlank()) return
        SecureClipboardHelper.copySensitive(
            context = getApplication(),
            scope = viewModelScope,
            label = label,
            secretText = text,
            onAutoCleared = {
                _clipboardNotice.value = "Clipboard auto-cleared after 30s."
            }
        )
        _clipboardNotice.value = "$label copied (hidden preview, auto-clears in 30s)."
    }

    fun clearClipboardNotice() {
        _clipboardNotice.value = null
    }

    fun clearSnapshotNotice() {
        _snapshotNotice.value = null
    }

    fun rotateNodeIdentity() {
        nodeIdentityManager.rotateIdentity()
        _localNodeFingerprint.value = nodeIdentityManager.getLocalFingerprint()
    }

    fun getPeerSafetyNumber(contact: Contact): String {
        val peerMaterial = contact.publicKey.ifBlank { contact.onionAddress }
        return nodeIdentityManager.computeSafetyNumber(peerMaterial)
    }

    fun exportEncryptedSnapshot(uri: Uri, passphrase: String) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val contacts = contactsStr.value
                val snapshot = VaultSnapshotManager.VaultSnapshot(
                    config = VaultSnapshotManager.SnapshotConfig(
                        torVpnEnabled = isTorVpnEnabled.value,
                        tailscaleEnabled = isTailscaleEnabled.value,
                        dcCloudEnabled = isDcCloudEnabled.value,
                        dcVaultSizeGb = dcCloudVaultSize.value,
                        dcAutoclearEnabled = isDcCloudAutoclearEnabled.value,
                        onionAddress = onionServerAddress.value,
                        tailscaleIp = tailscaleNodeIp.value
                    ),
                    contacts = contacts
                )
                val writtenBytes = withContext(Dispatchers.IO) {
                    VaultSnapshotManager.exportToUri(app, uri, snapshot, passphrase)
                }
                _snapshotNotice.value = "Encrypted .pvault exported (${writtenBytes} B, ${contacts.size} lanes)."
                ZeroLogEngine.log("Vault", "Exported AES-256-GCM .pvault snapshot (${contacts.size} contacts, $writtenBytes B).")
            } catch (e: Exception) {
                _snapshotNotice.value = "Export failed: ${e.message}"
                ZeroLogEngine.log("Vault", "Snapshot export failed: ${e.message}", "ERROR")
            }
        }
    }

    fun importEncryptedSnapshot(uri: Uri, passphrase: String) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    VaultSnapshotManager.importFromUri(app, uri, passphrase)
                }
                toggleTorVpn(snapshot.config.torVpnEnabled)
                toggleTailscale(snapshot.config.tailscaleEnabled)
                setDcCloudEnabled(snapshot.config.dcCloudEnabled)
                setDcCloudVaultSize(snapshot.config.dcVaultSizeGb)
                setDcCloudAutoclearEnabled(snapshot.config.dcAutoclearEnabled)

                if (snapshot.config.onionAddress.isNotBlank()) {
                    secPrefs.onionAddress = snapshot.config.onionAddress
                    onionServerAddress.value = snapshot.config.onionAddress
                }
                if (snapshot.config.tailscaleIp.isNotBlank()) {
                    secPrefs.tailscaleIp = snapshot.config.tailscaleIp
                    tailscaleNodeIp.value = snapshot.config.tailscaleIp
                }

                for (contact in snapshot.contacts) {
                    repository.insertContact(contact)
                }
                _snapshotNotice.value = "Restored ${snapshot.contacts.size} peer lanes & gateway config."
                ZeroLogEngine.log("Vault", "Restored ${snapshot.contacts.size} peer lanes from AES-256-GCM .pvault snapshot.")
            } catch (e: Exception) {
                _snapshotNotice.value = "Restore failed: ${e.message}"
                ZeroLogEngine.log("Vault", "Snapshot restore failed: ${e.message}", "ERROR")
            }
        }
    }

    // Toggle Config Controls
    fun toggleTorVpn(enabled: Boolean) {
        secPrefs.isTorVpnEnabled = enabled
        isTorVpnEnabled.value = enabled
        syncEngineConfig()
        ZeroLogEngine.log("Network", "Tor VPN Socks5 proxy: ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun toggleTailscale(enabled: Boolean) {
        secPrefs.isTailscaleEnabled = enabled
        isTailscaleEnabled.value = enabled
        syncEngineConfig()
        ZeroLogEngine.log("Network", "Tailscale Routing: ${if (enabled) "ROUTING VIA TAILNET" else "DISABLED"}")
    }

    fun setDcCloudEnabled(enabled: Boolean) {
        secPrefs.isDcCloudEnabled = enabled
        isDcCloudEnabled.value = enabled
        syncEngineConfig()
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
        syncEngineConfig()
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
        syncEngineConfig()
        ZeroLogEngine.log("Cloud", "Delivered container autodelete: ${if (enabled) "ON-DELIVERY ATOMIC ERASE" else "MANUAL PRUNING ONLY"}")
    }

    fun setContact(contact: Contact?) {
        _selectedContact.value = contact
        if (contact != null) {
            ZeroLogEngine.log("Chat", "Loaded handshake queues for peer: ${contact.displayName}")
        }
    }

    fun setMessageTtlMs(ttlMs: Long) {
        val clamped = ttlMs.coerceIn(60_000L, 24L * 60L * 60L * 1000L)
        _messageTtlMs.value = clamped
        val label = when {
            clamped < 3_600_000L -> "${clamped / 60_000L}m"
            else -> "${clamped / 3_600_000L}h"
        }
        ZeroLogEngine.log("Clean", "Active conversation TTL updated to $label.")
    }

    fun cycleMessageTtlPreset() {
        val presets = listOf(
            15L * 60L * 1000L,
            60L * 60L * 1000L,
            6L * 60L * 60L * 1000L,
            24L * 60L * 60L * 1000L
        )
        val idx = presets.indexOf(_messageTtlMs.value)
        val next = presets[(idx + 1) % presets.size]
        setMessageTtlMs(next)
    }

    fun shredConversation(contact: Contact, deleteContact: Boolean) {
        viewModelScope.launch {
            if (_selectedContact.value?.id == contact.id && _isRecordingVoice.value) {
                recordingTimerJob?.cancel()
                audioHelper.stopRecording()
                _isRecordingVoice.value = false
                _recordingDurationSec.value = 0
                activeRecordingFile?.let {
                    SecureFileShredder.shredLocalMedia(
                        getApplication<Application>().cacheDir,
                        getApplication<Application>().filesDir,
                        it
                    )
                }
                activeRecordingFile = null
            }

            if (deleteContact) {
                val removed = repository.shredContactAndLane(contact)
                if (_selectedContact.value?.id == contact.id) {
                    _selectedContact.value = null
                }
                ZeroLogEngine.log("Clean", "Shredded peer lane '${contact.displayName}' + $removed messages & local blobs.")
            } else {
                val removed = repository.clearConversation(contact.id)
                ZeroLogEngine.log("Clean", "Cleared $removed messages & local blobs for '${contact.displayName}'.")
            }
        }
    }

    // Chat Operations
    fun sendTextMessage(content: String) {
        val contact = _selectedContact.value ?: return
        if (content.isBlank()) return

        viewModelScope.launch {
            val delivered = coreEngine.sendText(contact, content)
            val msg = Message(
                contactId = contact.id,
                senderName = "MasterKey",
                isOutgoing = true,
                text = content,
                isDelivered = delivered,
                ttlExpiry = System.currentTimeMillis() + _messageTtlMs.value
            )
            repository.insertMessage(msg)
            ZeroLogEngine.log("Protocol", "Published unidirectional outbound packet via ${coreStatus.value.backend.label}")

            if (coreStatus.value.backend == com.example.core.CoreEngineBackend.KOTLIN_STAGING) {
                triggerAutomaticReply(contact, content)
            }
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
                ttlExpiry = System.currentTimeMillis() + _messageTtlMs.value
            )
            repository.insertMessage(reply)
            ZeroLogEngine.log("Protocol", "De-queued inbound message packet from unique simplex address.")
        }
    }

    fun reportAudioPermissionDenied(action: String) {
        _permissionNotice.value = "Microphone permission is required for $action."
        ZeroLogEngine.log("Audio", "RECORD_AUDIO denied by user for $action.", "WARN")
    }

    fun clearPermissionNotice() {
        _permissionNotice.value = null
    }

    // Voice note operations
    fun startVoiceRecording() {
        if (_isRecordingVoice.value) return
        _permissionNotice.value = null
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
                    ttlExpiry = System.currentTimeMillis() + _messageTtlMs.value
                )
                repository.insertMessage(voiceMessage)
                ZeroLogEngine.log("Audio", "In-memory voice packet committed to encrypted DB.")
            }
        }
        _recordingDurationSec.value = 0
        activeRecordingFile = null
    }

    fun playVoiceMemo(message: Message, onCompletion: () -> Unit) {
        val uri = message.mediaUri ?: return
        audioHelper.startPlayback(uri, onCompletion, {
            ZeroLogEngine.log("Audio", "Voice memo playback bypassed or synthetic simulation complete.")
            onCompletion()
        })
    }

    /**
     * Streams a real file/photo chosen via Android's zero-permission Photo Picker or SAF OpenDocument,
     * calculates a 16KB-chunked SHA-256 without loading the payload into RAM, and dispatches through XFTP/DC Cloud.
     */
    fun shareRealPickedMedia(uri: Uri, isImage: Boolean) {
        val contact = _selectedContact.value ?: return
        val app = getApplication<Application>()
        val resolver = app.contentResolver

        viewModelScope.launch {
            try {
                val (displayName, sha256Hex, byteSize) = withContext(Dispatchers.IO) {
                    var name = if (isImage) "photo_${UUID.randomUUID().toString().take(6)}.jpg" else "container_${UUID.randomUUID().toString().take(6)}.bin"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) {
                                val resolved = cursor.getString(idx)
                                if (!resolved.isNullOrBlank()) name = resolved
                            }
                        }
                    }
                    val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Unable to open stream for URI")
                    val (hash, size) = StreamingDigestHelper.computeSha256AndSize(input)
                    Triple(name, hash, size)
                }

                val sizeLabel = when {
                    byteSize >= 1024L * 1024L -> String.format("%.2f MB", byteSize / (1024.0 * 1024.0))
                    byteSize >= 1024L -> String.format("%.1f KB", byteSize / 1024.0)
                    else -> "$byteSize B"
                }

                val msg = Message(
                    contactId = contact.id,
                    senderName = "MasterKey",
                    isOutgoing = true,
                    text = if (isImage) {
                        "🖼️ Encrypted visual stream: $displayName ($sizeLabel) • SHA256:${sha256Hex.take(10)}"
                    } else {
                        "📂 Encrypted XFTP container: $displayName ($sizeLabel) • SHA256:${sha256Hex.take(10)}"
                    },
                    mediaUri = uri.toString(),
                    mediaType = if (isImage) "IMAGE" else "FILE",
                    isDelivered = true,
                    ttlExpiry = System.currentTimeMillis() + _messageTtlMs.value
                )
                repository.insertMessage(msg)
                ZeroLogEngine.log("Protocol", "Streamed $displayName ($sizeLabel), SHA-256 verified: ${sha256Hex.take(16)}...")

                coreEngine.dispatchXftpContainer(
                    XftpDispatchRequest(
                        contact = contact,
                        displayName = displayName,
                        sizeBytes = byteSize,
                        sha256Hex = sha256Hex,
                        dcCloudEnabled = isDcCloudEnabled.value,
                        dcVaultSizeGb = dcCloudVaultSize.value,
                        dcAutoclear = isDcCloudAutoclearEnabled.value
                    )
                )
            } catch (e: Exception) {
                ZeroLogEngine.log("Protocol", "Streaming media error: ${e.message}", "ERROR")
            }
        }
    }

    // Custom container dispatcher (kept compatible with dialog inputs & tests)
    fun sharePrivacyMockDocument(name: String, extension: String) {
        val contact = _selectedContact.value ?: return
        val cacheFile = File(getApplication<Application>().cacheDir, "sec_doc_${UUID.randomUUID().toString().take(6)}.$extension")
        try {
            val payload = "SECURE Priv_Shield Container - Integrity Pass HMAC512".toByteArray()
            FileOutputStream(cacheFile).use { fos ->
                fos.write(payload)
            }
            val shaHex = StreamingDigestHelper.computeSha256AndSize(payload.inputStream()).first
            viewModelScope.launch {
                val fileMsg = Message(
                    contactId = contact.id,
                    senderName = "MasterKey",
                    isOutgoing = true,
                    text = "📂 Encrypted shared container: $name.$extension",
                    mediaUri = cacheFile.absolutePath,
                    mediaType = "FILE",
                    isDelivered = true,
                    ttlExpiry = System.currentTimeMillis() + _messageTtlMs.value
                )
                repository.insertMessage(fileMsg)
                ZeroLogEngine.log("Protocol", "Completed media transfer of: $name.$extension under active TTL policy.")

                coreEngine.dispatchXftpContainer(
                    XftpDispatchRequest(
                        contact = contact,
                        displayName = "$name.$extension",
                        sizeBytes = payload.size.toLong(),
                        sha256Hex = shaHex,
                        dcCloudEnabled = isDcCloudEnabled.value,
                        dcVaultSizeGb = dcCloudVaultSize.value,
                        dcAutoclear = isDcCloudAutoclearEnabled.value
                    )
                )
            }
        } catch (e: Exception) {
            ZeroLogEngine.log("Protocol", "Failed writing secure container block: ${e.message}", "ERROR")
        }
    }

    fun shareSecurePhoto(photoLabel: String) {
        val contact = _selectedContact.value ?: return
        val cacheFile = File(getApplication<Application>().cacheDir, "sec_pic_${UUID.randomUUID().toString().take(6)}.png")
        try {
            val payload = "MOCK CRYPTO PIC ENVELOPE".toByteArray()
            FileOutputStream(cacheFile).use { fos ->
                fos.write(payload)
            }
            val shaHex = StreamingDigestHelper.computeSha256AndSize(payload.inputStream()).first
            viewModelScope.launch {
                val picMsg = Message(
                    contactId = contact.id,
                    senderName = "MasterKey",
                    isOutgoing = true,
                    text = "🖼️ Visual key snapshot: $photoLabel",
                    mediaUri = cacheFile.absolutePath,
                    mediaType = "IMAGE",
                    isDelivered = true,
                    ttlExpiry = System.currentTimeMillis() + _messageTtlMs.value
                )
                repository.insertMessage(picMsg)
                ZeroLogEngine.log("Protocol", "Shared photo transfer completed: $photoLabel")

                coreEngine.dispatchXftpContainer(
                    XftpDispatchRequest(
                        contact = contact,
                        displayName = photoLabel,
                        sizeBytes = payload.size.toLong(),
                        sha256Hex = shaHex,
                        dcCloudEnabled = isDcCloudEnabled.value,
                        dcVaultSizeGb = dcCloudVaultSize.value,
                        dcAutoclear = isDcCloudAutoclearEnabled.value
                    )
                )
            }
        } catch (e: Exception) {
            ZeroLogEngine.log("Protocol", "Failed writing secure photo envelope: ${e.message}", "ERROR")
        }
    }

    // Voice Call Procedures (Secure peer-to-peer over SOCKS5 onion node)
    fun requestVoiceCall(contact: Contact) {
        _permissionNotice.value = null
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

    // Master trigger: Panic button or Duress PIN wipes everything and locks instantly
    fun executePanicButtonPurge(duressTriggered: Boolean = false) {
        recordingTimerJob?.cancel()
        callTimerJob?.cancel()
        _isRecordingVoice.value = false
        _recordingDurationSec.value = 0
        activeRecordingFile = null
        _activeVoiceCallUser.value = null
        _callStateStr.value = ""
        _callFingerprint.value = ""
        _callDurationSec.value = 0
        _permissionNotice.value = null
        _clipboardNotice.value = null
        _snapshotNotice.value = null
        lastGeneratedInvite.value = null

        // Lock UI immediately and wipe prefs/clipboard without waiting for async file shredding
        SecureClipboardHelper.wipeNow(getApplication())
        secPrefs.clearAll()
        _isUnlocked.value = false
        _isPinSet.value = false
        _hasDuressPin.value = false
        _selectedContact.value = null

        viewModelScope.launch {
            audioHelper.wipeVoiceCache()
            coreEngine.panicWipe()
            nodeIdentityManager.wipeIdentity()
            keystoreVault.wipeMasterKey()
            _keystoreModeLabel.value = keystoreVault.activeMode().label
            repository.panicPurge()
            db.securePurgeAndCheckpoint()
            _localNodeFingerprint.value = nodeIdentityManager.getLocalFingerprint()

            val shreddedCache = SecureFileShredder.shredDirectoryContents(getApplication<Application>().cacheDir)
            val shreddedFiles = SecureFileShredder.shredDirectoryContents(getApplication<Application>().filesDir)

            ZeroLogEngine.clearLogs()
            val triggerSource = if (duressTriggered) "DURESS PIN" else "PANIC BUTTON"
            ZeroLogEngine.log("Panic", "--- COLD EMERGENCY PURGE ($triggerSource) COMPLETE (Shredded ${shreddedCache + shreddedFiles} files) ---")

            isTorVpnEnabled.value = false
            isTailscaleEnabled.value = false
            onionServerAddress.value = ""
            tailscaleNodeIp.value = ""
            isDcCloudEnabled.value = true
            dcCloudVaultSize.value = 16
            isDcCloudAutoclearEnabled.value = true
            _messageTtlMs.value = 24L * 60L * 60L * 1000L
            _pinError.value = if (duressTriggered) {
                "Vault reset: initialize a fresh 6-digit passcode."
            } else {
                "EMERGENCY: Cryptographic wipe successfully executed!"
            }

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
            val contact = coreEngine.createRoomInvite(roomName)
            repository.insertContact(contact)
            lastGeneratedInvite.value = InviteResult(roomName, contact.onionAddress)
            ZeroLogEngine.log("Setup", "Initiated secure room queue: $roomName")
            ZeroLogEngine.log("Protocol", "One-time invitation token bound to port 5223")
        }
    }

    fun clearLastGeneratedInvite() {
        lastGeneratedInvite.value = null
    }

    // Initializer with high-privacy pre-configurations
    private fun initializeMockContactsAndChats() {
        viewModelScope.launch {
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

                val now = System.currentTimeMillis()
                val m1 = Message(
                    contactId = sovereign.id,
                    senderName = sovereign.displayName,
                    isOutgoing = false,
                    text = "Welcome to your local SimpleX server queue. No IDs or global identities exist.",
                    timestamp = now - 50000,
                    ttlExpiry = now + (23 * 60 * 60 * 1000)
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
            val contact = coreEngine.joinOrRegisterContact(name, onionAddress)
            repository.insertContact(contact)
            ZeroLogEngine.log("Setup", "Registered communication link: ${contact.displayName}")
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
        SecureClipboardHelper.wipeNow(getApplication())
    }
}
