package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Contact
import com.example.data.Message
import com.example.data.ZeroLogEngine
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.sin
import androidx.compose.foundation.text.selection.SelectionContainer

// Theme Custom Dark Security Colors
val SecureDarkBackground = Color(0xFF0F1113)
val SecureDarkSurface = Color(0xFF1D2024)
val SecureGreenGlow = Color(0xFF34C759)
val SecureGreenMuted = Color(0xFF13361E)
val SecureRedPanic = Color(0xFFBA1A1A)
val SecureRedPanicMuted = Color(0xFF4F0D0D)
val SecureSlate = Color(0xFF333539)
val SecureTextWhite = Color(0xFFE2E2E6)
val SecureAccentBlue = Color(0xFFD1E4FF)
val SecureDeepBlue = Color(0xFF004785)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleXAppContent(viewModel: ChatViewModel) {
    val isUnlocked by viewModel.isUnlocked.collectAsStateWithLifecycle()
    val isPinSet by viewModel.isPinSet.collectAsStateWithLifecycle()
    val lockoutPeriodMs by viewModel.lockoutPeriodMs.collectAsStateWithLifecycle()
    val pinError by viewModel.pinError.collectAsStateWithLifecycle()
    val activeCallUser by viewModel.activeVoiceCallUser.collectAsStateWithLifecycle()

    val rootKeyboardController = remember { InAppKeyboardController() }

    LaunchedEffect(Unit) {
        ZeroLogEngine.log("Security", "System IME suppressed; Paranoid In-App Secure Keyboard active")
    }

    DisableSystemKeyboard(disable = true) {
        CompositionLocalProvider(LocalParanoidKeyboard provides rootKeyboardController) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SecureDarkBackground)
            ) {
                if (!isUnlocked) {
                    PinScreen(
                        isPinSet = isPinSet,
                        lockoutPeriodMs = lockoutPeriodMs,
                        pinError = pinError,
                        onSubmit = { pin -> viewModel.submitPin(pin) }
                    )
                } else {
                    MainDashboard(viewModel)

                    // Voice Call Overlay
                    if (activeCallUser != null) {
                        VoiceCallOverlay(
                            contact = activeCallUser!!,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PinScreen(
    isPinSet: Boolean,
    lockoutPeriodMs: Long,
    pinError: String?,
    onSubmit: (String) -> Unit
) {
    var pinBuffer by remember { mutableStateOf("") }
    val isLockedOut = lockoutPeriodMs > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .navigationBarsPadding()
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // App Identity Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Security Gateway",
                tint = if (isLockedOut) SecureRedPanic else SecureGreenGlow,
                modifier = Modifier
                    .size(64.dp)
                    .drawBehind {
                        drawCircle(
                            color = if (isLockedOut) SecureRedPanic.copy(alpha = 0.2f) else SecureGreenGlow.copy(alpha = 0.2f),
                            radius = size.minDimension * 0.8f,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "PARANOID SecureNode",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = SecureTextWhite,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Text(
                text = if (isPinSet) "DECRYPT LOCAL PORTAL KEYS" else "INITIALIZE MASTER ACCESS KEY",
                fontSize = 12.sp,
                color = if (isLockedOut) SecureRedPanic else SecureGreenGlow,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Pincode Input Dots Visualizer
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLockedOut) {
                Text(
                    text = "RATE-LIMIT LOCK ACTIVE: ${(lockoutPeriodMs / 1000) + 1}s",
                    color = SecureRedPanic,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(SecureRedPanicMuted.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    for (i in 0 until 6) {
                        val active = i < pinBuffer.length
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(16.dp)
                                .drawBehind {
                                    if (active) {
                                        drawCircle(color = SecureGreenGlow)
                                    } else {
                                        drawCircle(
                                            color = SecureTextWhite.copy(alpha = 0.3f),
                                            style = Stroke(width = 2.dp.toPx())
                                        )
                                    }
                                }
                        )
                    }
                }
            }

            if (pinError != null && !isLockedOut) {
                Text(
                    text = pinError,
                    color = SecureRedPanic,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else if (!isPinSet) {
                Text(
                    text = "Register a 6-digit passcode to initialize database keys.",
                    color = SecureTextWhite.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }

        // Numerical Cybersecurity Pin Keyboard
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("Clear", "0", "OK")
            )

            keys.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { digit ->
                        Button(
                            onClick = {
                                if (isLockedOut) return@Button
                                when (digit) {
                                    "Clear" -> {
                                        if (pinBuffer.isNotEmpty()) pinBuffer = pinBuffer.dropLast(1)
                                    }
                                    "OK" -> {
                                        if (pinBuffer.length == 6) {
                                            onSubmit(pinBuffer)
                                            pinBuffer = ""
                                        }
                                    }
                                    else -> {
                                        if (pinBuffer.length < 6) {
                                            pinBuffer += digit
                                            if (pinBuffer.length == 6) {
                                                onSubmit(pinBuffer)
                                                pinBuffer = ""
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isLockedOut,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (digit == "OK") SecureGreenMuted else SecureDarkSurface,
                                disabledContainerColor = SecureDarkSurface.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .size(height = 64.dp, width = 88.dp)
                                .border(
                                    1.dp,
                                    if (digit == "OK") SecureGreenGlow.copy(alpha = 0.5f) else SecureSlate,
                                    RoundedCornerShape(12.dp)
                                )
                                .testTag("pin_btn_$digit")
                        ) {
                            Text(
                                text = digit,
                                color = if (digit == "OK" || digit == "Clear") SecureGreenGlow else SecureTextWhite,
                                fontSize = if (digit == "Clear" || digit == "OK") 14.sp else 22.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboard(viewModel: ChatViewModel) {
    var currentTab by remember { mutableStateOf(0) } // 0 = Chats, 1 = SimpleX:node Network Tab

    val isTorVpn by viewModel.isTorVpnEnabled.collectAsStateWithLifecycle()
    val isTailscale by viewModel.isTailscaleEnabled.collectAsStateWithLifecycle()
    val onionAddr by viewModel.onionServerAddress.collectAsStateWithLifecycle()
    val selectedContact by viewModel.selectedContact.collectAsStateWithLifecycle()

    var showPanicConfirm by remember { mutableStateOf(false) }
    var showServerSettings by remember { mutableStateOf(false) }
    val keyboardController = LocalParanoidKeyboard.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            color = SecureGreenGlow,
                            shape = CircleShape
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PARANOID Node",
                            color = SecureTextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                actions = {
                    // Quick VPN status tags
                    Row(
                        modifier = Modifier.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NetworkIndicatorTag(label = "TOR", active = isTorVpn)
                        Spacer(modifier = Modifier.width(4.dp))
                        NetworkIndicatorTag(label = "TS", active = isTailscale)
                        Spacer(modifier = Modifier.width(12.dp))

                        // Settings Icon to Open Server Controls Dialog
                        IconButton(
                            onClick = { showServerSettings = true },
                            modifier = Modifier
                                .size(32.dp)
                                .background(SecureSlate, CircleShape)
                                .testTag("settings_gear_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Backend Tunnel Config",
                                tint = SecureGreenGlow,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))

                        // Flashing Warning PANIC BUTTON
                        Button(
                            onClick = { showPanicConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SecureRedPanic),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("panic_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Emergency Wipe Trigger",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "PANIC",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SecureDarkSurface,
                    scrolledContainerColor = SecureDarkSurface
                )
            )
        },
        bottomBar = {
            if (keyboardController?.isVisible != true) {
                NavigationBar(
                    containerColor = SecureDarkSurface,
                    modifier = Modifier.navigationBarsPadding(),
                    tonalElevation = 8.dp
                ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = {
                        currentTab = 0
                        viewModel.setContact(null)
                    },
                    icon = { Icon(Icons.Filled.Email, contentDescription = "Chats") },
                    label = { Text("Lanes", fontFamily = FontFamily.Monospace) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SecureGreenGlow,
                        selectedTextColor = SecureGreenGlow,
                        unselectedIconColor = SecureTextWhite.copy(alpha = 0.5f),
                        unselectedTextColor = SecureTextWhite.copy(alpha = 0.5f),
                        indicatorColor = SecureGreenMuted
                    )
                )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        icon = { Icon(Icons.Filled.Share, contentDescription = "SimpleX Routing Network") },
                        label = { Text("Nodes Net", fontFamily = FontFamily.Monospace) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SecureGreenGlow,
                            selectedTextColor = SecureGreenGlow,
                            unselectedIconColor = SecureTextWhite.copy(alpha = 0.5f),
                            unselectedTextColor = SecureTextWhite.copy(alpha = 0.5f),
                            indicatorColor = SecureGreenMuted
                        )
                    )
                }
            }
        },
        containerColor = SecureDarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                selectedContact != null -> {
                    // Chat Conversation Screen takes over priorities
                    ChatDetailScreen(viewModel = viewModel, contact = selectedContact!!)
                }
                currentTab == 0 -> {
                    ChatsListTab(viewModel)
                }
                currentTab == 1 -> {
                    RoutingNetworkTab(viewModel)
                }
            }

            // Panic Confirmation Modal Dialog
            if (showPanicConfirm) {
                AlertDialog(
                    onDismissRequest = { showPanicConfirm = false },
                    containerColor = SecureDarkSurface,
                    shape = RoundedCornerShape(16.dp),
                    title = {
                        Text(
                            text = "⚠️ IRREVERSIBLE COLD WIPE",
                            color = SecureRedPanic,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 18.sp
                        )
                    },
                    text = {
                        Text(
                            text = "This will immediately wipe all secure keys, active messaging cache, file residues, user identities, and reset the Master PIN.\n\nAll data is overwritten in RAM and flash with zeroes. Confirm emergency action?",
                            color = SecureTextWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showPanicConfirm = false
                                viewModel.executePanicButtonPurge()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SecureRedPanic)
                        ) {
                            Text("EXECUTE WIPEOUT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showPanicConfirm = false }
                        ) {
                            Text("ABORT", color = SecureTextWhite, fontFamily = FontFamily.Monospace)
                        }
                    },
                    modifier = Modifier.testTag("panic_confirm_dialog")
                )
            }

            // Server Configuration and Logs Settings Dialog
            if (showServerSettings) {
                ServerSettingsModal(viewModel = viewModel, onDismiss = { showServerSettings = false })
            }
        }
    }
}

@Composable
fun NetworkIndicatorTag(label: String, active: Boolean) {
    Box(
        modifier = Modifier
            .background(
                if (active) SecureGreenMuted.copy(alpha = 0.6f) else SecureSlate.copy(alpha = 0.5f),
                RoundedCornerShape(4.dp)
            )
            .border(
                width = 1.dp,
                color = if (active) SecureGreenGlow.copy(alpha = 0.6f) else SecureSlate,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = if (active) SecureGreenGlow else SecureTextWhite.copy(alpha = 0.4f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun ChatsListTab(viewModel: ChatViewModel) {
    val contacts by viewModel.contactsStr.collectAsStateWithLifecycle()
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showCreateRoomDialog by remember { mutableStateOf(false) }
    val lastGeneratedInvite by viewModel.lastGeneratedInvite.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // Quick Section Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Secure Communication Lanes",
                color = SecureTextWhite.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Room creation trigger button
                IconButton(
                    onClick = { showCreateRoomDialog = true },
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(28.dp)
                        .background(SecureSlate, CircleShape)
                        .testTag("create_room_trigger_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Create Secure Chat Room",
                        tint = SecureAccentBlue,
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Add Connection Link button
                IconButton(
                    onClick = { showAddContactDialog = true },
                    modifier = Modifier
                        .size(28.dp)
                        .background(SecureGreenMuted, CircleShape)
                        .testTag("add_contact_trigger_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New SimpleX Pair Link",
                        tint = SecureGreenGlow,
                        modifier = Modifier.size(16.dp)
                )
            }
        }
        }

        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "No Connections Found",
                        tint = SecureSlate,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No private queues active.\nCreate a link to pair queues.",
                        textAlign = TextAlign.Center,
                        color = SecureTextWhite.copy(alpha = 0.4f),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(contacts) { item ->
                    ContactRow(contact = item, onClick = { viewModel.setContact(item) })
                }
            }
        }

        // Add Contact Dialog
        if (showAddContactDialog) {
            var newContactName by remember { mutableStateOf("") }
            var newContactOnion by remember { mutableStateOf("") }
            val onionFocusRequester = remember { FocusRequester() }

            ParanoidKeyboardDialog(
                onDismissRequest = { showAddContactDialog = false }
            ) {
                val dialogKb = LocalParanoidKeyboard.current
                val dialogFocus = LocalFocusManager.current
                Surface(
                    color = SecureDarkSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SecureSlate),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Pair New Private SimpleX Queue",
                            color = SecureGreenGlow,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            "SimpleX uses unidirectional local queues. Paste the target's handshake link.",
                            color = SecureTextWhite.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        ParanoidOutlinedTextField(
                            value = newContactName,
                            onValueChange = { newContactName = it },
                            fieldId = "add_contact_alias",
                            actionLabel = "NEXT",
                            onAction = { onionFocusRequester.requestFocus() },
                            label = { Text("Endpoint Alias", fontFamily = FontFamily.Monospace) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        ParanoidOutlinedTextField(
                            value = newContactOnion,
                            onValueChange = { newContactOnion = it },
                            fieldId = "add_contact_onion",
                            actionLabel = "DONE",
                            onAction = {
                                dialogKb?.hide()
                                dialogFocus.clearFocus()
                            },
                            label = { Text("simplex://... handshake link", fontFamily = FontFamily.Monospace) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(onionFocusRequester)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showAddContactDialog = false }) {
                                Text("Cancel", color = SecureTextWhite, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (newContactName.isNotBlank() && newContactOnion.isNotBlank()) {
                                        viewModel.addCustomContact(newContactName, newContactOnion)
                                        showAddContactDialog = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SecureGreenMuted)
                            ) {
                                Text("Add Node Link", color = SecureGreenGlow, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        // Create Secure Chat Room Dialog
        if (showCreateRoomDialog) {
            var roomName by remember { mutableStateOf("") }
            ParanoidKeyboardDialog(
                onDismissRequest = { showCreateRoomDialog = false }
            ) {
                val dialogKb = LocalParanoidKeyboard.current
                val dialogFocus = LocalFocusManager.current
                Surface(
                    color = SecureDarkSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SecureSlate),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Initialize Multi-Peer Chat Room",
                            color = SecureAccentBlue,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "This generates a direct room queue on SimpleX and generates a unique single-use handshake link. Share the invite with peer endpoints to establish unidirectional paths.",
                            color = SecureTextWhite.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                        ParanoidOutlinedTextField(
                            value = roomName,
                            onValueChange = { roomName = it },
                            fieldId = "create_room_name",
                            actionLabel = "DONE",
                            onAction = {
                                dialogKb?.hide()
                                dialogFocus.clearFocus()
                            },
                            label = { Text("Room Name / Topic", fontFamily = FontFamily.Monospace) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SecureTextWhite,
                                unfocusedTextColor = SecureTextWhite,
                                focusedBorderColor = SecureAccentBlue,
                                unfocusedBorderColor = SecureSlate,
                                focusedLabelColor = SecureAccentBlue,
                                unfocusedLabelColor = SecureTextWhite.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("room_name_input")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showCreateRoomDialog = false }) {
                                Text("Cancel", color = SecureTextWhite, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (roomName.isNotBlank()) {
                                        viewModel.createChatRoom(roomName)
                                        showCreateRoomDialog = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SecureGreenMuted),
                                enabled = roomName.isNotBlank()
                            ) {
                                Text("Create & Mint", color = SecureGreenGlow, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        // Show Success Generation Dialog for Invite Link
        if (lastGeneratedInvite != null) {
            AlertDialog(
                onDismissRequest = { viewModel.clearLastGeneratedInvite() },
                containerColor = SecureDarkSurface,
                shape = RoundedCornerShape(12.dp),
                title = {
                    Text(
                        text = "🔑 Room Minted Successfully",
                        color = SecureGreenGlow,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Your high-security room \"${lastGeneratedInvite!!.roomName}\" is now online on port 5223. Share this cryptographic invitation link with other peers. It will expire once paired.",
                            color = SecureTextWhite,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SecureDarkBackground, RoundedCornerShape(6.dp))
                                .border(1.dp, SecureSlate, RoundedCornerShape(6.dp))
                                .padding(12.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = lastGeneratedInvite!!.inviteLink,
                                    color = SecureAccentBlue,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                        Text(
                            text = "ℹ️ Select text inside the box to copy invitation.",
                            color = SecureTextWhite.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.clearLastGeneratedInvite() },
                        colors = ButtonDefaults.buttonColors(containerColor = SecureGreenMuted)
                    ) {
                        Text("Done", color = SecureGreenGlow, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }
    }
}

@Composable
fun ContactRow(contact: Contact, onClick: () -> Unit) {
    val isRoom = contact.id.startsWith("room_")
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("contact_${contact.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(if (isRoom) SecureGreenMuted else SecureSlate, CircleShape)
                    .border(
                        width = 1.dp,
                        color = if (isRoom) SecureAccentBlue.copy(alpha = 0.6f) else SecureGreenGlow.copy(alpha = 0.4f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isRoom) Icons.Filled.Share else Icons.Filled.Face,
                    contentDescription = if (isRoom) "Chat Room" else "Contact Node",
                    tint = if (isRoom) SecureAccentBlue else SecureGreenGlow,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.displayName,
                    color = SecureTextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = contact.onionAddress,
                    color = SecureTextWhite.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = "Open Lanes",
                tint = SecureTextWhite.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
fun ServerControlTab(viewModel: ChatViewModel) {
    val isTorVpn by viewModel.isTorVpnEnabled.collectAsStateWithLifecycle()
    val isTailscale by viewModel.isTailscaleEnabled.collectAsStateWithLifecycle()
    val hostOnion by viewModel.onionServerAddress.collectAsStateWithLifecycle()
    val tsIp by viewModel.tailscaleNodeIp.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "SimpleX Core Gateway (Server Side Configuration)",
                color = SecureGreenGlow,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                "SimpleX acts serverless by hosting independent messaging queues directly. Configure local network tunnels here.",
                color = SecureTextWhite.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
        }

        // Section A: Tor Hidden Service (.onion queue address)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SecureSlate)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🧅 TOR HIDDEN SERVICE (.ONION)",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = SecureTextWhite
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SecureGreenMuted
                        ) {
                            Text(
                                "ACTIVE GATEWAY",
                                color = SecureGreenGlow,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Hosts local unidirectional simpleX message queue. External clients push messages directly to this onion address:",
                        color = SecureTextWhite.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SecureDarkBackground, RoundedCornerShape(6.dp))
                            .border(1.dp, SecureSlate, RoundedCornerShape(6.dp))
                            .padding(12.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = hostOnion,
                                color = SecureAccentBlue,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Local Port Listener: 5223",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SecureTextWhite.copy(alpha = 0.4f)
                        )
                        Text(
                            "Tor Circuit Consensus: YES",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SecureGreenGlow
                        )
                    }
                }
            }
        }

        // Section B: Tor VPN Routing Toggle
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SecureSlate)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "🔒 INTEGRATED TOR VPN ROUTING",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = SecureTextWhite
                            )
                            Text(
                                text = "SOCKS5 Proxy tunnel (Port 9050)",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = SecureTextWhite.copy(alpha = 0.5f)
                            )
                        }
                        Switch(
                            checked = isTorVpn,
                            onCheckedChange = { viewModel.toggleTorVpn(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SecureGreenGlow,
                                checkedTrackColor = SecureGreenMuted,
                                uncheckedThumbColor = SecureTextWhite.copy(alpha = 0.3f),
                                uncheckedTrackColor = SecureSlate
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Routes all outbound simplex queues and peer-to-peer data blocks through three distributed cryptographic nodes (Hops list: USA -> Germany -> Iceland).",
                        color = SecureTextWhite.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Section C: Tailscale Routing Toggle
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SecureSlate)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "🕸️ TAILSCALE OVERLAY ROUTING",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = SecureTextWhite
                            )
                            Text(
                                text = "Current Node overlay IP: $tsIp",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = SecureTextWhite.copy(alpha = 0.5f)
                            )
                        }
                        Switch(
                            checked = isTailscale,
                            onCheckedChange = { viewModel.toggleTailscale(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SecureGreenGlow,
                                checkedTrackColor = SecureGreenMuted,
                                uncheckedThumbColor = SecureTextWhite.copy(alpha = 0.3f),
                                uncheckedTrackColor = SecureSlate
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Bypasses Tor onion routing latency for high-speed voice call & large file media data transfers over authenticated WireGuard Mesh.",
                        color = SecureTextWhite.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun ZeroLogsTab(viewModel: ChatViewModel) {
    val logs by viewModel.serverLogs.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "☣️ ZERO PERSISTENT LOG INFORMATION",
                    color = SecureRedPanic,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
                Text(
                    text = "App does NOT write into persistent system caches.",
                    color = SecureTextWhite.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Button(
                onClick = { ZeroLogEngine.clearLogs() },
                colors = ButtonDefaults.buttonColors(containerColor = SecureSlate),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("CLEAR RAM", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = SecureTextWhite)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(SecureDarkBackground, RoundedCornerShape(8.dp))
                .border(1.dp, SecureSlate, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Telemetry silent. No transactions recorded in memory.",
                        color = SecureTextWhite.copy(alpha = 0.3f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs.reversed()) { log ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(log.timestamp))
                            Text(
                                text = "[$timeStr]",
                                color = SecureTextWhite.copy(alpha = 0.4f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "[${log.tag.uppercase()}]",
                                color = when (log.level) {
                                    "ERROR" -> SecureRedPanic
                                    "WARN" -> SecureAccentBlue
                                    else -> SecureGreenGlow
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = log.message,
                                color = SecureTextWhite,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsModal(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var selectedInnerTab by remember { mutableStateOf(0) } // 0 = Lanes Gateway, 1 = Zero-Logs Telemetry, 2 = DC Cloud

    ParanoidKeyboardDialog(onDismissRequest = onDismiss) {
        val dialogKb = LocalParanoidKeyboard.current
        Surface(
            color = SecureDarkSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, SecureSlate),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            tint = SecureGreenGlow,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "GATEWAY SETTINGS",
                            color = SecureTextWhite,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Close", tint = SecureTextWhite.copy(alpha = 0.5f))
                    }
                }

                // Interactive Subtab Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp)
                        .background(SecureDarkBackground, RoundedCornerShape(8.dp))
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedInnerTab == 0) SecureSlate else Color.Transparent)
                            .clickable {
                                dialogKb?.hide()
                                selectedInnerTab = 0
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Core Tunnel",
                            color = if (selectedInnerTab == 0) SecureGreenGlow else SecureTextWhite.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedInnerTab == 1) SecureSlate else Color.Transparent)
                            .clickable {
                                dialogKb?.hide()
                                selectedInnerTab = 1
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Zero-Logs",
                            color = if (selectedInnerTab == 1) SecureGreenGlow else SecureTextWhite.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedInnerTab == 2) SecureSlate else Color.Transparent)
                            .clickable {
                                dialogKb?.hide()
                                selectedInnerTab = 2
                            }
                            .padding(vertical = 8.dp)
                            .testTag("dc_cloud_tab_trigger"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "DC Cloud",
                            color = if (selectedInnerTab == 2) SecureGreenGlow else SecureTextWhite.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Subtab contents (adapts smoothly when in-app numeric keyboard is visible)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (dialogKb?.isVisible == true) 280.dp else 440.dp)
                ) {
                    when (selectedInnerTab) {
                        0 -> ServerControlTab(viewModel)
                        1 -> ZeroLogsTab(viewModel)
                        else -> DcCloudSettingsTab(viewModel)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = SecureGreenMuted)
                    ) {
                        Text(
                            text = "DISMISS GATEWAY",
                            color = SecureGreenGlow,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DcCloudSettingsTab(viewModel: ChatViewModel) {
    val isEnabled by viewModel.isDcCloudEnabled.collectAsStateWithLifecycle()
    val vaultSize by viewModel.dcCloudVaultSize.collectAsStateWithLifecycle()
    val isAutoclear by viewModel.isDcCloudAutoclearEnabled.collectAsStateWithLifecycle()
    val isServerActive by viewModel.isDcServerSideActive.collectAsStateWithLifecycle()

    var customSizeText by remember(vaultSize) { mutableStateOf(vaultSize.toString()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                "De-Centralized Cloud Sharing (DC Cloud)",
                color = SecureAccentBlue,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Secure global storage for relaying encrypted file/media packages with distributed validation checkpoints.",
                color = SecureTextWhite.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }

        // Card 1: DC Cloud Master switch + Server Side Part status
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (isServerActive) SecureGreenGlow.copy(alpha = 0.5f) else SecureSlate)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "☁️ DC CLOUD ROUTING",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = SecureTextWhite
                            )
                            Text(
                                text = if (isServerActive) {
                                    "SERVER-SIDE GLOBAL LAYER: ACTIVE"
                                } else if (!isEnabled) {
                                    "CLOUD ROUTING: DISABLED"
                                } else {
                                    "SERVER-SIDE LAYER: INACTIVE (SIZE < 16GB)"
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isServerActive) SecureGreenGlow else if (!isEnabled) SecureTextWhite.copy(alpha = 0.4f) else SecureRedPanic,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { viewModel.setDcCloudEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SecureGreenGlow,
                                checkedTrackColor = SecureGreenMuted,
                                uncheckedThumbColor = SecureTextWhite.copy(alpha = 0.3f),
                                uncheckedTrackColor = SecureSlate
                            ),
                            modifier = Modifier.testTag("dc_cloud_master_switch")
                        )
                    }
                    if (isEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isServerActive) SecureGreenMuted.copy(alpha = 0.3f) else SecureRedPanicMuted.copy(alpha = 0.3f),
                                    RoundedCornerShape(6.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isServerActive) SecureGreenGlow.copy(alpha = 0.3f) else SecureRedPanic.copy(alpha = 0.3f),
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(10.dp)
                        ) {
                            Text(
                                text = if (isServerActive) {
                                    "🛡️ High-Performance Server-Side daemon initiated. Global routing pool is fully operational using $vaultSize GB allocated cloud partition."
                                } else {
                                    "⚠️ Server-side sharing is suspended. Allocate at least 16 GB of secure vault storage below to activate the server side part globally."
                                },
                                color = if (isServerActive) SecureGreenGlow else SecureTextWhite,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }

        // Card 2: Vault size allocation inputs
        if (isEnabled) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SecureSlate)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "🗄️ VAULT ALLOCATION SIZE",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = SecureTextWhite
                        )
                        Text(
                            text = "Size of secure virtualized cloud container space in Gigabytes. (Min: 16 GB for Server-Side Activation)",
                            color = SecureTextWhite.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                        )

                        // Sizing interactive presets row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(8, 16, 32, 64).forEach { size ->
                                val isSelected = vaultSize == size
                                OutlinedButton(
                                    onClick = {
                                        viewModel.setDcCloudVaultSize(size)
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (isSelected) SecureSlate else Color.Transparent,
                                        contentColor = if (isSelected) SecureAccentBlue else SecureTextWhite.copy(alpha = 0.7f)
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) SecureAccentBlue else SecureSlate
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .testTag("dc_vault_preset_$size")
                                ) {
                                    Text(
                                        text = "${size}GB",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Custom Size Entry input text (In-App Numeric Keyboard)
                        val dcKb = LocalParanoidKeyboard.current
                        val dcFocus = LocalFocusManager.current
                        ParanoidOutlinedTextField(
                            value = customSizeText,
                            onValueChange = { newValue ->
                                if (newValue.all { it.isDigit() }) {
                                    customSizeText = newValue
                                    val checkedInt = newValue.toIntOrNull()
                                    if (checkedInt != null) {
                                        viewModel.setDcCloudVaultSize(checkedInt)
                                    }
                                }
                            },
                            fieldId = "dc_vault_size_custom_input",
                            preferredLayout = ParanoidKeyboardLayout.NUMERIC,
                            digitsOnly = true,
                            actionLabel = "DONE",
                            onAction = {
                                dcKb?.hide()
                                dcFocus.clearFocus()
                            },
                            label = { Text("Custom Vault Size (GB)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SecureTextWhite,
                                unfocusedTextColor = SecureTextWhite,
                                focusedBorderColor = if (isServerActive) SecureGreenGlow else SecureAccentBlue,
                                unfocusedBorderColor = SecureSlate,
                                focusedLabelColor = SecureAccentBlue,
                                unfocusedLabelColor = SecureTextWhite.copy(alpha = 0.5f)
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            singleLine = true,
                            trailingIcon = {
                                Text(
                                    "GB  ",
                                    color = SecureTextWhite.copy(alpha = 0.5f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("dc_vault_size_custom_input")
                        )
                    }
                }
            }
        }

        // Card 3: Autoclear Delivered items and telemetry IMMEDIATELY
        if (isEnabled) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SecureSlate)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "⚡ IMMEDIATE ATOMIC ERASE",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = SecureTextWhite
                                )
                                Text(
                                    text = "Remove delivered content/telemetry instantly",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = SecureTextWhite.copy(alpha = 0.5f)
                                )
                            }
                            Switch(
                                checked = isAutoclear,
                                onCheckedChange = { viewModel.setDcCloudAutoclearEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = SecureGreenGlow,
                                    checkedTrackColor = SecureGreenMuted,
                                    uncheckedThumbColor = SecureTextWhite.copy(alpha = 0.3f),
                                    uncheckedTrackColor = SecureSlate
                                ),
                                modifier = Modifier.testTag("dc_cloud_autoclear_switch")
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "When active, delivered containers and all related server logs, metrics, or caching residue are wiped from cloud endpoints immediately after transmission confirmation. Ensures zero persistent footprint.",
                            color = SecureTextWhite.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RoutingNetworkTab(viewModel: ChatViewModel) {
    val nodes by viewModel.availableNodes.collectAsStateWithLifecycle()
    val activeCircuit by viewModel.activeCircuit.collectAsStateWithLifecycle()
    val isAutoRouting by viewModel.isAutoRoutingEnabled.collectAsStateWithLifecycle()
    val statusText by viewModel.routingStatus.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Multi-hop switch controller
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, SecureSlate)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SimpleX:node Protocol",
                                color = SecureGreenGlow,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Autonomous global node cascading routing system",
                                color = SecureTextWhite.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Switch(
                            checked = isAutoRouting,
                            onCheckedChange = { viewModel.setAutoRouting(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SecureGreenGlow,
                                checkedTrackColor = SecureGreenMuted,
                                uncheckedThumbColor = SecureTextWhite.copy(alpha = 0.4f),
                                uncheckedTrackColor = SecureSlate
                            ),
                            modifier = Modifier.testTag("auto_routing_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SecureDarkBackground, RoundedCornerShape(8.dp))
                            .border(1.dp, SecureSlate, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = statusText,
                            color = SecureTextWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.rebuildOnionCircuit() },
                        colors = ButtonDefaults.buttonColors(containerColor = SecureGreenMuted),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .testTag("rebuild_circuit_btn")
                    ) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = null, tint = SecureGreenGlow, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "RE-ROUTE CIRCUIT ENTRIES",
                            color = SecureGreenGlow,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Circuit route visualization
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, SecureSlate)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ACTIVE PATH PIPELINE (ONION LAYERS)",
                        color = SecureTextWhite.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (activeCircuit != null) {
                        val hops = activeCircuit!!.hops
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Host
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(SecureSlate, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(imageVector = Icons.Filled.Face, contentDescription = "Device", tint = SecureTextWhite, modifier = Modifier.size(18.dp))
                                }
                                Text("[Host]", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = SecureTextWhite.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp))
                            }

                            // Dynamic Connections lines and Hops
                            hops.forEachIndexed { index, node ->
                                // Transition arrow
                                Icon(
                                    imageVector = Icons.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = SecureGreenGlow,
                                    modifier = Modifier.size(12.dp)
                                )

                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1.2f)) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(SecureSlate, CircleShape)
                                            .border(1.dp, SecureGreenGlow, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = node.countryCode,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = SecureGreenGlow
                                        )
                                    }
                                    Text(
                                        text = "${node.latencyMs}ms",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = SecureGreenGlow,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }

                            // Peer Node Target
                            Icon(
                                imageVector = Icons.Filled.ArrowForward,
                                contentDescription = null,
                                tint = SecureGreenGlow,
                                modifier = Modifier.size(12.dp)
                            )

                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(SecureSlate, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(imageVector = Icons.Filled.Lock, contentDescription = "Peer", tint = SecureAccentBlue, modifier = Modifier.size(18.dp))
                                }
                                Text("[Peer]", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = SecureAccentBlue, modifier = Modifier.padding(top = 4.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Divider(color = SecureSlate, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(12.dp))

                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Ephemeral Shared Secret ID:", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = SecureTextWhite.copy(alpha = 0.5f))
                                Text(activeCircuit!!.encryptionKeyStr, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = SecureAccentBlue, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Consensus Circuit Address:", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = SecureTextWhite.copy(alpha = 0.5f))
                                Text(activeCircuit!!.finalAddress, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = SecureTextWhite.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        Text("No pipeline actively assembled. Refresh routes.", fontSize = 11.sp, color = SecureRedPanic, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Global Node Directory List
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Global Nodes Directory",
                    color = SecureTextWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = "${nodes.size} Online",
                    color = SecureGreenGlow,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        items(nodes) { node ->
            val isCheckedInCircuit = activeCircuit?.hops?.any { it.id == node.id } == true
            Card(
                colors = CardDefaults.cardColors(containerColor = SecureDarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isCheckedInCircuit) SecureGreenGlow else SecureSlate
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isAutoRouting) {
                            viewModel.selectNodeForManualCircuit(node)
                        }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(SecureSlate, CircleShape)
                            .border(1.dp, if (isCheckedInCircuit) SecureGreenGlow else SecureTextWhite.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = node.countryCode,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isCheckedInCircuit) SecureGreenGlow else SecureTextWhite
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = node.host,
                            color = SecureTextWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "${node.region} • ${node.ipAddress} • Load ${node.loadPercentage}%",
                            color = SecureTextWhite.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${node.latencyMs} ms",
                            color = if (node.latencyMs < 50) SecureGreenGlow else SecureAccentBlue,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(SecureGreenGlow, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "ACTIVE",
                                color = SecureGreenGlow.copy(alpha = 0.8f),
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatDetailScreen(viewModel: ChatViewModel, contact: Contact) {
    val messages by viewModel.currentChatMessages.collectAsStateWithLifecycle()
    val isRecordingVoice by viewModel.isRecordingVoice.collectAsStateWithLifecycle()
    val recordingDurationSec by viewModel.recordingDurationSec.collectAsStateWithLifecycle()

    var textInput by remember { mutableStateOf("") }
    var expandedAttachmentsMenu by remember { mutableStateOf(false) }

    var showRoomInviteDialog by remember { mutableStateOf(false) }
    var showSendFileDialog by remember { mutableStateOf(false) }
    var showSendPhotoDialog by remember { mutableStateOf(false) }

    val keyboardController = LocalParanoidKeyboard.current
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = keyboardController?.isVisible == true) {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    DisposableEffect(contact.id) {
        onDispose {
            keyboardController?.hide()
        }
    }

    val isRoom = contact.id.startsWith("room_")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SecureDarkBackground)
    ) {
        // Chat Header with details and voice call trigger
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SecureDarkSurface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.setContact(null) }
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = SecureGreenGlow
                )
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(if (isRoom) SecureGreenMuted else SecureSlate, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isRoom) Icons.Filled.Share else Icons.Filled.Face,
                    contentDescription = null,
                    tint = if (isRoom) SecureAccentBlue else SecureGreenGlow,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.displayName,
                    color = SecureTextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isRoom) "MULTIPoint ROOM" else "TTL ACTIVE: 24h Queue",
                        color = if (isRoom) SecureAccentBlue else SecureGreenGlow,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (isRoom) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "• INVITE LINK",
                            color = SecureGreenGlow,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { showRoomInviteDialog = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Secure P2P Call Trigger
            IconButton(
                onClick = { viewModel.requestVoiceCall(contact) },
                modifier = Modifier.testTag("voice_call_btn")
            ) {
                Icon(
                    imageVector = Icons.Filled.Phone,
                    contentDescription = "Tor/TS Voice Call",
                    tint = SecureGreenGlow
                )
            }
        }

        // Messages Thread Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Starting private communication lane.\nAll transactions vanish in 24 hours.",
                        color = SecureTextWhite.copy(alpha = 0.3f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = false,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { message ->
                        MessageBubble(message = message, viewModel = viewModel)
                    }
                }
            }
        }

        // Secure file selectors expander panel
        AnimatedVisibility(
            visible = expandedAttachmentsMenu,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SecureDarkSurface)
                    .border(1.dp, SecureSlate)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButtonWithLabel(
                    icon = Icons.Filled.Add,
                    label = "Attach File",
                    onClick = {
                        expandedAttachmentsMenu = false
                        showSendFileDialog = true
                    }
                )
                IconButtonWithLabel(
                    icon = Icons.Filled.Share,
                    label = "Attach Photo",
                    onClick = {
                        expandedAttachmentsMenu = false
                        showSendPhotoDialog = true
                    }
                )
                IconButtonWithLabel(
                    icon = Icons.Filled.Lock,
                    label = "Key Slip",
                    onClick = {
                        expandedAttachmentsMenu = false
                        viewModel.sharePrivacyMockDocument("simplex_peer_signing_key", "sec")
                    }
                )
            }
        }

        // Show Room Invite Dialog
        if (showRoomInviteDialog) {
            AlertDialog(
                onDismissRequest = { showRoomInviteDialog = false },
                containerColor = SecureDarkSurface,
                shape = RoundedCornerShape(12.dp),
                title = {
                    Text("Room Handshake Key Details", color = SecureAccentBlue, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("This group lane is bound to port 5223. Peer endpoints can pair queues using this invite link:", color = SecureTextWhite.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SecureDarkBackground, RoundedCornerShape(6.dp))
                                .border(1.dp, SecureSlate, RoundedCornerShape(6.dp))
                                .padding(12.dp)
                        ) {
                            SelectionContainer {
                                Text(contact.onionAddress, color = SecureAccentBlue, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showRoomInviteDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = SecureGreenMuted)
                    ) {
                        Text("Done", color = SecureGreenGlow, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            )
        }

        // Custom Send File Dialog
        if (showSendFileDialog) {
            var fileName by remember { mutableStateOf("sec_intel_payload") }
            var fileExt by remember { mutableStateOf("pgp") }
            val extFocusRequester = remember { FocusRequester() }
            ParanoidKeyboardDialog(
                onDismissRequest = { showSendFileDialog = false }
            ) {
                val dialogKb = LocalParanoidKeyboard.current
                val dialogFocus = LocalFocusManager.current
                Surface(
                    color = SecureDarkSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SecureSlate),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Secure Container Dispatcher", color = SecureGreenGlow, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Compile a file container to transmit inside a 24-hour expiration threshold. The receiver's client auto-decrypts once validated.", color = SecureTextWhite.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        ParanoidOutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            fieldId = "send_file_name_input",
                            actionLabel = "NEXT",
                            onAction = { extFocusRequester.requestFocus() },
                            label = { Text("Base File Name", fontFamily = FontFamily.Monospace) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SecureTextWhite,
                                unfocusedTextColor = SecureTextWhite,
                                focusedBorderColor = SecureGreenGlow,
                                unfocusedBorderColor = SecureSlate
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("send_file_name_input")
                        )
                        ParanoidOutlinedTextField(
                            value = fileExt,
                            onValueChange = { fileExt = it },
                            fieldId = "send_file_ext_input",
                            actionLabel = "DONE",
                            onAction = {
                                dialogKb?.hide()
                                dialogFocus.clearFocus()
                            },
                            label = { Text("Cryptographic Extension (e.g. pgp, enc)", fontFamily = FontFamily.Monospace) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SecureTextWhite,
                                unfocusedTextColor = SecureTextWhite,
                                focusedBorderColor = SecureGreenGlow,
                                unfocusedBorderColor = SecureSlate
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(extFocusRequester)
                                .testTag("send_file_ext_input")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showSendFileDialog = false }) {
                                Text("Cancel", color = SecureTextWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (fileName.isNotBlank() && fileExt.isNotBlank()) {
                                        viewModel.sharePrivacyMockDocument(fileName, fileExt)
                                        showSendFileDialog = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SecureGreenMuted),
                                modifier = Modifier.testTag("send_file_confirm_btn")
                            ) {
                                Text("Dispatch Container", color = SecureGreenGlow, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // Custom Send Photo Dialog
        if (showSendPhotoDialog) {
            var photoLabel by remember { mutableStateOf("recon_snapshot.png") }
            AlertDialog(
                onDismissRequest = { showSendPhotoDialog = false },
                containerColor = SecureDarkSurface,
                shape = RoundedCornerShape(12.dp),
                title = {
                    Text("Ephemeral Media Broadcast", color = SecureGreenGlow, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Broadcast raw photos with ephemeral zero-traces to this queue. Select photo description context label:", color = SecureTextWhite.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        listOf(
                            "thermal_satellite_mesh_east.png",
                            "identity_shield_raw_face.bmp",
                            "handshake_fingerprints_0xda.png",
                            "tactical_manifesto_leak.png"
                        ).forEachIndexed { index, option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { photoLabel = option }
                                    .background(if (photoLabel == option) SecureGreenMuted else Color.Transparent, RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                                    .testTag("photo_option_$index"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = photoLabel == option,
                                    onClick = { photoLabel = option },
                                    colors = RadioButtonDefaults.colors(selectedColor = SecureGreenGlow, unselectedColor = SecureSlate)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(option, color = SecureTextWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (photoLabel.isNotBlank()) {
                                viewModel.shareSecurePhoto(photoLabel)
                                showSendPhotoDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SecureGreenMuted),
                        modifier = Modifier.testTag("send_photo_confirm_btn")
                    ) {
                        Text("Transmit Media", color = SecureGreenGlow, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSendPhotoDialog = false }) {
                        Text("Cancel", color = SecureTextWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            )
        }

        // Input and Voice note triggers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SecureDarkSurface)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Expander attachments button
            IconButton(
                onClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    expandedAttachmentsMenu = !expandedAttachmentsMenu
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Expand cryptographic payloads",
                    tint = SecureGreenGlow
                )
            }

            if (isRecordingVoice) {
                // Recording visualizer in place of Text Input
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(SecureRedPanicMuted, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape),
                            color = SecureRedPanic
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CAPTURING AUDIO RAW: ${recordingDurationSec}s",
                            color = SecureRedPanic,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Simple Pulsing Graph simulation
                    Row {
                        for (i in 0..4) {
                            val ht = (5..15).random().dp
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 1.dp)
                                    .size(width = 2.dp, height = ht)
                                    .background(SecureRedPanic)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Release/Click to STOP and send
                IconButton(
                    onClick = { viewModel.stopAndSendVoiceRecording() },
                    modifier = Modifier
                        .size(40.dp)
                        .background(SecureRedPanic, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Stop & Dispatch",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                // In-App Secure Keyboard Input (OS IME disabled)
                ParanoidOutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    fieldId = "chat_message_input_${contact.id}",
                    preferredLayout = ParanoidKeyboardLayout.QWERTY_EN,
                    actionLabel = "SEND",
                    onAction = {
                        if (textInput.isNotBlank()) {
                            viewModel.sendTextMessage(textInput)
                            textInput = ""
                        }
                    },
                    placeholder = { Text("Write encrypted packet...", fontFamily = FontFamily.Monospace, color = SecureTextWhite.copy(alpha = 0.4f), fontSize = 12.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .testTag("chat_message_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SecureTextWhite,
                        unfocusedTextColor = SecureTextWhite,
                        focusedBorderColor = SecureGreenGlow,
                        unfocusedBorderColor = SecureSlate,
                        focusedLabelColor = SecureGreenGlow,
                        unfocusedLabelColor = SecureTextWhite.copy(alpha = 0.5f)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    singleLine = true
                )

                // Voice Recording trigger / Send trigger
                if (textInput.isBlank()) {
                    IconButton(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            viewModel.startVoiceRecording()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(SecureSlate, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Voice Encryption",
                            tint = SecureTextWhite
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            viewModel.sendTextMessage(textInput)
                            textInput = ""
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(SecureGreenMuted, CircleShape)
                            .testTag("chat_send_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Send,
                            contentDescription = "Transmit Queue",
                            tint = SecureGreenGlow
                        )
                    }
                }
            }
        }

        // Docked Anti-Keylogger In-App Keyboard
        if (keyboardController != null && !isRecordingVoice) {
            AnimatedVisibility(
                visible = keyboardController.isVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ParanoidInAppKeyboard(
                    controller = keyboardController,
                    onHideRequested = {
                        keyboardController.hide()
                        focusManager.clearFocus()
                    },
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        }
    }
}

@Composable
fun IconButtonWithLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(SecureSlate, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = SecureGreenGlow, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = SecureTextWhite.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun MessageBubble(message: Message, viewModel: ChatViewModel) {
    var isPlayingVoice by remember { mutableStateOf(false) }

    // Dynamic 24h Expiry Countdown string
    var countdownStr by remember(message.id) { mutableStateOf("") }

    LaunchedEffect(key1 = message.id) {
        while (true) {
            val rem = message.ttlExpiry - System.currentTimeMillis()
            if (rem <= 0) {
                countdownStr = "Expired"
                break
            } else {
                val sec = (rem / 1000) % 60
                val min = (rem / (1000 * 60)) % 60
                val hr = (rem / (1000 * 60 * 60)) % 24
                countdownStr = String.format(Locale.getDefault(), "%02dh %02dm %02ds", hr, min, sec)
            }
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (message.isOutgoing) SecureSlate else SecureDarkSurface,
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (message.isOutgoing) 12.dp else 0.dp,
                        bottomEnd = if (message.isOutgoing) 0.dp else 12.dp
                    )
                )
                .border(
                    width = 1.dp,
                    color = if (!message.isDelivered) SecureRedPanic else SecureSlate,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (message.isOutgoing) 12.dp else 0.dp,
                        bottomEnd = if (message.isOutgoing) 0.dp else 12.dp
                    )
                )
                .padding(12.dp)
                .widthIn(max = 280.dp)
        ) {
            Column {
                // Sender label
                Text(
                    text = message.senderName,
                    color = if (message.isOutgoing) SecureGreenGlow else SecureAccentBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Message payload body
                if (message.mediaType == "VOICE") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                isPlayingVoice = true
                                viewModel.playVoiceMemo(message) {
                                    isPlayingVoice = false
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isPlayingVoice) Icons.Filled.Refresh else Icons.Filled.Lock,
                            contentDescription = "Voice note playback",
                            tint = SecureGreenGlow,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Secure Voice Memo",
                                color = SecureTextWhite,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Click to decrypt & cycle sound",
                                color = SecureTextWhite.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                } else {
                    Text(
                        text = message.text,
                        color = SecureTextWhite,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom strip: expiration TTL count + Delivery error warnings
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Vanish countdown indicator",
                            tint = SecureGreenGlow.copy(alpha = 0.6f),
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = countdownStr,
                            color = SecureGreenGlow.copy(alpha = 0.8f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (!message.isDelivered) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Packet Timeout Alert",
                                tint = SecureRedPanic,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Not Delivered (24h TTL Queued)",
                                color = SecureRedPanic,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceCallOverlay(contact: Contact, viewModel: ChatViewModel) {
    val callState by viewModel.callStateStr.collectAsStateWithLifecycle()
    val fingerprint by viewModel.callFingerprint.collectAsStateWithLifecycle()
    val durationSec by viewModel.callDurationSec.collectAsStateWithLifecycle()
    val isTailscaleActive by viewModel.isTailscaleEnabled.collectAsStateWithLifecycle()

    var tick by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            tick += 0.2f
            delay(50)
        }
    }

    Scaffold(
        containerColor = SecureDarkBackground,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Call metadata head
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isTailscaleActive) SecureGreenMuted else SecureSlate
                ) {
                    Text(
                        text = if (isTailscaleActive) "TAILSCALE P2P HIGH FREQUENCY" else "🧅 TOR-ONION SOCKS5 ROUTED VOICE",
                        color = if (isTailscaleActive) SecureGreenGlow else SecureAccentBlue,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Icon(
                    imageVector = Icons.Filled.Face,
                    contentDescription = contact.displayName,
                    tint = SecureGreenGlow,
                    modifier = Modifier
                        .size(96.dp)
                        .border(2.dp, SecureGreenGlow, CircleShape)
                        .padding(12.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = contact.displayName,
                    color = SecureTextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = callState,
                    color = if (callState.contains("Active")) SecureGreenGlow else SecureTextWhite.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Interactive sound wave oscillator visualizer Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val path = Path()

                    val basePeriod = 0.05f
                    val amp = if (callState.contains("Active")) 35f else 10f

                    for (x in 0..width.toInt() step 5) {
                        // Compose wave calculation using sinusoids
                        val y = (height / 2) +
                                sin((x * basePeriod) - tick) * amp +
                                sin((x * 0.12f) + tick) * (amp / 2f)

                        if (x == 0) {
                            path.moveTo(x.toFloat(), y)
                        } else {
                            path.lineTo(x.toFloat(), y)
                        }
                    }

                    drawPath(
                        path = path,
                        color = if (callState.contains("Active")) SecureGreenGlow else SecureSlate,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }

            // Key Verification Fingerprint ZRTP & Session details
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (callState.contains("Active")) {
                    Text(
                        text = "VERIFY ENCRYPTION FINGERPRINT",
                        color = SecureTextWhite.copy(alpha = 0.4f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = fingerprint,
                        color = SecureGreenGlow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .background(SecureGreenMuted.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .border(1.dp, SecureGreenGlow.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )

                    val minutes = durationSec / 60
                    val seconds = durationSec % 60
                    Text(
                        text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds),
                        color = SecureTextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                } else {
                    CircularProgressIndicator(color = SecureGreenGlow, strokeWidth = 2.dp)
                }
            }

            // Hang-up Trigger
            Button(
                onClick = { viewModel.endVoiceCall() },
                colors = ButtonDefaults.buttonColors(containerColor = SecureRedPanic),
                shape = CircleShape,
                modifier = Modifier
                    .size(64.dp)
                    .testTag("hangup_btn")
            ) {
                Icon(
                    imageVector = Icons.Filled.Phone,
                    contentDescription = "Hang Up Voice Call",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
