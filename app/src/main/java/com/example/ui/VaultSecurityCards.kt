package com.example.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DuressPinConfigCard(viewModel: ChatViewModel) {
    val hasDuress by viewModel.hasDuressPin.collectAsStateWithLifecycle()
    var duressInput by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    val kbController = LocalParanoidKeyboard.current
    val focusManager = LocalFocusManager.current

    Card(
        colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (hasDuress) SecureRedPanic.copy(alpha = 0.7f) else SecureSlate
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("duress_pin_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Duress Panic PIN",
                        tint = if (hasDuress) SecureRedPanic else SecureAccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "DURESS (PANIC) PASSCODE",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = SecureTextWhite
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (hasDuress) SecureRedPanicMuted else SecureSlate
                ) {
                    Text(
                        text = if (hasDuress) "ARMED" else "OFF",
                        color = if (hasDuress) SecureRedPanic else SecureTextWhite.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Entering this 6-digit decoy PIN on the lock screen executes an instant cold wipe of keys, chats, media, and preferences.",
                color = SecureTextWhite.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            ParanoidOutlinedTextField(
                value = duressInput,
                onValueChange = { next ->
                    if (next.length <= 6) {
                        duressInput = next
                        feedback = null
                    }
                },
                fieldId = "duress_pin_input",
                preferredLayout = ParanoidKeyboardLayout.NUMERIC,
                digitsOnly = true,
                actionLabel = "DONE",
                onAction = {
                    kbController?.hide()
                    focusManager.clearFocus()
                },
                label = { Text("6-digit Duress PIN", fontFamily = FontFamily.Monospace) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("duress_pin_input")
            )

            AnimatedVisibility(visible = feedback != null) {
                Text(
                    text = feedback.orEmpty(),
                    color = if (hasDuress && feedback?.contains("armed", true) == true) SecureGreenGlow else SecureRedPanic,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        kbController?.hide()
                        focusManager.clearFocus()
                        val candidate = duressInput
                        duressInput = ""
                        val armed = viewModel.setDuressPin(candidate)
                        feedback = if (armed) {
                            "Duress PIN armed with PBKDF2-120k."
                        } else {
                            "Rejected: must be 6 digits and distinct from primary PIN."
                        }
                    },
                    enabled = duressInput.length == 6,
                    colors = ButtonDefaults.buttonColors(containerColor = SecureRedPanicMuted),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("arm_duress_pin_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = SecureTextWhite,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (hasDuress) "Rotate PIN" else "Arm Duress",
                        color = SecureTextWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (hasDuress) {
                    OutlinedButton(
                        onClick = {
                            duressInput = ""
                            viewModel.clearDuressPin()
                            feedback = "Duress PIN disarmed."
                        },
                        border = BorderStroke(1.dp, SecureSlate),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("clear_duress_pin_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Disarm Duress PIN",
                            tint = SecureTextWhite.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Disarm",
                            color = SecureTextWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EncryptedSnapshotCard(viewModel: ChatViewModel) {
    val snapshotNotice by viewModel.snapshotNotice.collectAsStateWithLifecycle()
    var passphrase by remember { mutableStateOf("") }
    var pendingPassphrase by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val kbController = LocalParanoidKeyboard.current
    val focusManager = LocalFocusManager.current

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val secret = pendingPassphrase
        pendingPassphrase = null
        if (uri != null && !secret.isNullOrEmpty()) {
            viewModel.exportEncryptedSnapshot(uri, secret)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val secret = pendingPassphrase
        pendingPassphrase = null
        if (uri != null && !secret.isNullOrEmpty()) {
            viewModel.importEncryptedSnapshot(uri, secret)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SecureAccentBlue.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("snapshot_vault_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ENCRYPTED .PVAULT SNAPSHOT",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = SecureAccentBlue
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = SecureDeepBlue
                ) {
                    Text(
                        text = "HKDF + GCM",
                        color = SecureAccentBlue,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Portable zero-permission SAF backup of peer lanes & gateway toggles encrypted via PBKDF2-120k + HKDF-SHA256 + AES-256-GCM.",
                color = SecureTextWhite.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            ParanoidOutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    localError = null
                    viewModel.clearSnapshotNotice()
                },
                fieldId = "snapshot_passphrase_input",
                preferredLayout = ParanoidKeyboardLayout.QWERTY_EN,
                actionLabel = "DONE",
                onAction = {
                    kbController?.hide()
                    focusManager.clearFocus()
                },
                label = { Text("Snapshot Passphrase (min 8 chars)", fontFamily = FontFamily.Monospace) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("snapshot_passphrase_input")
            )

            val activeMessage = localError ?: snapshotNotice
            AnimatedVisibility(visible = activeMessage != null) {
                Text(
                    text = activeMessage.orEmpty(),
                    color = if (activeMessage?.contains("failed", true) == true || localError != null) {
                        SecureRedPanic
                    } else {
                        SecureGreenGlow
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        kbController?.hide()
                        focusManager.clearFocus()
                        if (passphrase.length < 8) {
                            localError = "Passphrase must be at least 8 characters."
                        } else {
                            localError = null
                            pendingPassphrase = passphrase
                            passphrase = ""
                            exportLauncher.launch("paranoid_node.pvault")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SecureGreenMuted),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("export_snapshot_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = SecureGreenGlow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Export .pvault",
                        color = SecureGreenGlow,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = {
                        kbController?.hide()
                        focusManager.clearFocus()
                        if (passphrase.isEmpty()) {
                            localError = "Enter snapshot passphrase before restoring."
                        } else {
                            localError = null
                            pendingPassphrase = passphrase
                            passphrase = ""
                            importLauncher.launch(arrayOf("*/*"))
                        }
                    },
                    border = BorderStroke(1.dp, SecureAccentBlue.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("restore_snapshot_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = SecureAccentBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Restore .pvault",
                        color = SecureAccentBlue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun NodeIdentityCard(viewModel: ChatViewModel) {
    val fingerprint by viewModel.localNodeFingerprint.collectAsStateWithLifecycle()

    Card(
        colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SecureGreenGlow.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("node_identity_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NODE EC P-256 IDENTITY",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = SecureGreenGlow
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = SecureGreenMuted
                ) {
                    Text(
                        text = "DB-GCM + ECDSA",
                        color = SecureGreenGlow,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Hardware-wrapped secp256r1 identity key used for peer safety verification; Room messages are sealed at rest with AES-256-GCM.",
                color = SecureTextWhite.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SecureDarkBackground, RoundedCornerShape(6.dp))
                    .border(1.dp, SecureSlate, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = fingerprint,
                    color = SecureAccentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("node_fingerprint_text")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.copySensitiveWithAutoClear(fingerprint, "Node Fingerprint") },
                    border = BorderStroke(1.dp, SecureAccentBlue.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("copy_fingerprint_btn")
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, tint = SecureAccentBlue, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy FP (30s)", color = SecureAccentBlue, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { viewModel.rotateNodeIdentity() },
                    colors = ButtonDefaults.buttonColors(containerColor = SecureGreenMuted),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("rotate_identity_btn")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = SecureGreenGlow, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Rotate Keypair", color = SecureGreenGlow, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

