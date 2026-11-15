package com.example.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.ZeroLogEngine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keyboard layouts modeled after in-app-keyboard (QWERTY, Numeric, Decimal, Symbols)
 * plus Paranoid security additions (Russian ЙЦУКЕН, anti-shoulder-surf Scramble mode).
 */
enum class ParanoidKeyboardLayout {
    QWERTY_EN,
    QWERTY_RU,
    SYMBOLS,
    NUMERIC,
    DECIMAL
}

enum class ShiftState {
    OFF,
    SINGLE,
    CAPS_LOCK
}

data class InAppKeyboardTarget(
    val id: String,
    val preferredLayout: ParanoidKeyboardLayout = ParanoidKeyboardLayout.QWERTY_EN,
    val actionLabel: String = "DONE",
    val digitsOnly: Boolean = false,
    val getValue: () -> TextFieldValue,
    val onValueChange: (TextFieldValue) -> Unit,
    val onAction: () -> Unit
)

@Stable
class InAppKeyboardController {
    var activeTarget by mutableStateOf<InAppKeyboardTarget?>(null)
        private set

    var currentLayout by mutableStateOf(ParanoidKeyboardLayout.QWERTY_EN)
        private set

    var lastTextLanguage by mutableStateOf(ParanoidKeyboardLayout.QWERTY_EN)
        private set

    var shiftState by mutableStateOf(ShiftState.OFF)
        private set

    var isScrambled by mutableStateOf(false)
        private set

    var isStealthMode by mutableStateOf(false)
        private set

    var scrambleSeed by mutableStateOf(0)
        private set

    val isVisible: Boolean
        get() = activeTarget != null

    fun attach(target: InAppKeyboardTarget) {
        val isSwitchingField = activeTarget?.id != target.id
        activeTarget = target
        if (isSwitchingField) {
            currentLayout = when (target.preferredLayout) {
                ParanoidKeyboardLayout.QWERTY_EN,
                ParanoidKeyboardLayout.QWERTY_RU -> lastTextLanguage
                else -> target.preferredLayout
            }
            shiftState = ShiftState.OFF
        }
    }

    fun detach(targetId: String) {
        if (activeTarget?.id == targetId) {
            activeTarget = null
        }
    }

    fun hide() {
        activeTarget = null
    }

    fun insertText(raw: String) {
        val target = activeTarget ?: return
        val textToInsert = if (
            (currentLayout == ParanoidKeyboardLayout.QWERTY_EN ||
                currentLayout == ParanoidKeyboardLayout.QWERTY_RU) &&
            shiftState != ShiftState.OFF
        ) {
            raw.uppercase()
        } else {
            raw
        }

        val current = target.getValue()
        val selStart = current.selection.min.coerceIn(0, current.text.length)
        val selEnd = current.selection.max.coerceIn(0, current.text.length)

        if (target.digitsOnly && textToInsert.any { !it.isDigit() }) return
        if (currentLayout == ParanoidKeyboardLayout.DECIMAL && textToInsert == "." && current.text.contains(".")) {
            return
        }

        val updatedText = buildString {
            append(current.text, 0, selStart)
            append(textToInsert)
            append(current.text, selEnd, current.text.length)
        }
        val newCursor = selStart + textToInsert.length
        target.onValueChange(
            TextFieldValue(
                text = updatedText,
                selection = TextRange(newCursor)
            )
        )

        if (shiftState == ShiftState.SINGLE) {
            shiftState = ShiftState.OFF
        }
    }

    fun backspace() {
        val target = activeTarget ?: return
        val current = target.getValue()
        val selStart = current.selection.min.coerceIn(0, current.text.length)
        val selEnd = current.selection.max.coerceIn(0, current.text.length)

        if (selStart != selEnd) {
            val updatedText = current.text.removeRange(selStart, selEnd)
            target.onValueChange(
                TextFieldValue(
                    text = updatedText,
                    selection = TextRange(selStart)
                )
            )
        } else if (selStart > 0) {
            val updatedText = current.text.removeRange(selStart - 1, selStart)
            target.onValueChange(
                TextFieldValue(
                    text = updatedText,
                    selection = TextRange(selStart - 1)
                )
            )
        }
    }

    fun clearAll() {
        val target = activeTarget ?: return
        target.onValueChange(TextFieldValue(text = "", selection = TextRange(0)))
    }

    fun toggleShift() {
        shiftState = when (shiftState) {
            ShiftState.OFF -> ShiftState.SINGLE
            ShiftState.SINGLE -> ShiftState.CAPS_LOCK
            ShiftState.CAPS_LOCK -> ShiftState.OFF
        }
    }

    fun toggleLanguage() {
        val next = if (lastTextLanguage == ParanoidKeyboardLayout.QWERTY_EN) {
            ParanoidKeyboardLayout.QWERTY_RU
        } else {
            ParanoidKeyboardLayout.QWERTY_EN
        }
        lastTextLanguage = next
        currentLayout = next
        shiftState = ShiftState.OFF
    }

    fun switchLayout(layout: ParanoidKeyboardLayout) {
        currentLayout = layout
        if (layout == ParanoidKeyboardLayout.QWERTY_EN || layout == ParanoidKeyboardLayout.QWERTY_RU) {
            lastTextLanguage = layout
        }
    }

    fun toggleScramble() {
        isScrambled = !isScrambled
        scrambleSeed++
        ZeroLogEngine.log(
            "Security",
            if (isScrambled) "In-App IME anti-shoulder-surf scramble ENABLED"
            else "In-App IME standard key order restored"
        )
    }

    fun toggleStealthMode() {
        isStealthMode = !isStealthMode
    }

    fun triggerAction() {
        activeTarget?.onAction?.invoke()
    }
}

val LocalParanoidKeyboard = compositionLocalOf<InAppKeyboardController?> { null }

/**
 * Intercepts and suppresses the Android OS software keyboard (Gboard / third-party IMEs)
 * at the platform text-input session layer so external keyboards can never read or log keystrokes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DisableSystemKeyboard(
    disable: Boolean = true,
    content: @Composable () -> Unit
) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            if (!disable) {
                nextHandler.startInputMethod(request)
            } else {
                awaitCancellation()
            }
        },
        content = content
    )
}

/**
 * Dialog container with its own isolated In-App Keyboard window host and OS IME suppression.
 * Ensures modals never get covered by the keyboard and never summon the Android system IME.
 */
@Composable
fun ParanoidKeyboardDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    val dialogKeyboardController = remember { InAppKeyboardController() }
    val focusManager = LocalFocusManager.current

    Dialog(
        onDismissRequest = {
            dialogKeyboardController.hide()
            onDismissRequest()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        DisableSystemKeyboard(disable = true) {
            CompositionLocalProvider(LocalParanoidKeyboard provides dialogKeyboardController) {
                val localFocusManager = LocalFocusManager.current
                BackHandler(enabled = dialogKeyboardController.isVisible) {
                    dialogKeyboardController.hide()
                    localFocusManager.clearFocus()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.68f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            dialogKeyboardController.hide()
                            localFocusManager.clearFocus()
                            onDismissRequest()
                        }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Consume clicks inside the dialog card so backdrop tap doesn't dismiss it
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 560.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {}
                            ) {
                                content()
                            }
                        }

                        AnimatedVisibility(
                            visible = dialogKeyboardController.isVisible,
                            enter = expandVertically(
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                            ) + fadeIn(),
                            exit = shrinkVertically(
                                animationSpec = spring(stiffness = Spring.StiffnessMedium)
                            ) + fadeOut()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {}
                            ) {
                                ParanoidInAppKeyboard(
                                    controller = dialogKeyboardController,
                                    onHideRequested = {
                                        dialogKeyboardController.hide()
                                        localFocusManager.clearFocus()
                                        focusManager.clearFocus()
                                    },
                                    modifier = Modifier.navigationBarsPadding()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Drop-in OutlinedTextField wired to LocalParanoidKeyboard.
 * System IME is disabled; tapping or focusing attaches this field to ParanoidInAppKeyboard.
 */
@Composable
fun ParanoidOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldId: String = remember { java.util.UUID.randomUUID().toString() },
    preferredLayout: ParanoidKeyboardLayout = ParanoidKeyboardLayout.QWERTY_EN,
    actionLabel: String = "DONE",
    digitsOnly: Boolean = false,
    onAction: () -> Unit = {},
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    textStyle: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = SecureTextWhite,
        unfocusedTextColor = SecureTextWhite,
        focusedBorderColor = SecureGreenGlow,
        unfocusedBorderColor = SecureSlate,
        focusedLabelColor = SecureGreenGlow,
        unfocusedLabelColor = SecureTextWhite.copy(alpha = 0.5f)
    )
) {
    val controller = LocalParanoidKeyboard.current
    val interactionSource = remember { MutableInteractionSource() }

    var textFieldValueState by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }

    if (textFieldValueState.text != value) {
        textFieldValueState = TextFieldValue(text = value, selection = TextRange(value.length))
    }

    val latestTfv by rememberUpdatedState(textFieldValueState)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnAction by rememberUpdatedState(onAction)

    val applyTfv: (TextFieldValue) -> Unit = { newTfv ->
        val filtered = if (digitsOnly) newTfv.text.filter { it.isDigit() } else newTfv.text
        val clampedStart = newTfv.selection.start.coerceIn(0, filtered.length)
        val clampedEnd = newTfv.selection.end.coerceIn(0, filtered.length)
        val sanitized = newTfv.copy(
            text = filtered,
            selection = TextRange(clampedStart, clampedEnd)
        )
        textFieldValueState = sanitized
        if (sanitized.text != value) {
            latestOnValueChange(sanitized.text)
        }
    }
    val latestApplyTfv by rememberUpdatedState(applyTfv)

    val attachSelf = {
        controller?.attach(
            InAppKeyboardTarget(
                id = fieldId,
                preferredLayout = preferredLayout,
                actionLabel = actionLabel,
                digitsOnly = digitsOnly,
                getValue = { latestTfv },
                onValueChange = { latestApplyTfv(it) },
                onAction = { latestOnAction() }
            )
        )
    }

    // Re-attach if user taps an already-focused field after hiding the keyboard
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                attachSelf()
            }
        }
    }

    DisposableEffect(fieldId) {
        onDispose {
            controller?.detach(fieldId)
        }
    }

    OutlinedTextField(
        value = textFieldValueState,
        onValueChange = { newTfv -> applyTfv(newTfv) },
        label = label,
        placeholder = placeholder,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        textStyle = textStyle,
        colors = colors,
        interactionSource = interactionSource,
        modifier = modifier.onFocusChanged { state ->
            if (state.isFocused) {
                attachSelf()
            }
        }
    )
}

@Composable
fun ParanoidInAppKeyboard(
    controller: InAppKeyboardController,
    modifier: Modifier = Modifier,
    onHideRequested: () -> Unit = { controller.hide() }
) {
    val target = controller.activeTarget
    val layout = controller.currentLayout
    val shift = controller.shiftState
    val isScrambled = controller.isScrambled
    val isStealth = controller.isStealthMode
    val seed = controller.scrambleSeed
    val actionLabel = target?.actionLabel ?: "DONE"
    val digitsOnly = target?.digitsOnly == true

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("paranoid_keyboard_container"),
        color = Color(0xFF0A0C0E),
        tonalElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = SecureGreenGlow.copy(alpha = 0.35f)
                )
                .padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Security & Telemetry Toolbar
                KeyboardSecurityToolbar(
                    controller = controller,
                    digitsOnly = digitsOnly,
                    isScrambled = isScrambled,
                    isStealth = isStealth,
                    onHideRequested = onHideRequested
                )

                // Active Keyboard Layout Rows
                when (layout) {
                    ParanoidKeyboardLayout.QWERTY_EN -> {
                        QwertyKeyboardRows(
                            isRussian = false,
                            shift = shift,
                            isScrambled = isScrambled,
                            isStealth = isStealth,
                            scrambleSeed = seed,
                            actionLabel = actionLabel,
                            controller = controller
                        )
                    }
                    ParanoidKeyboardLayout.QWERTY_RU -> {
                        QwertyKeyboardRows(
                            isRussian = true,
                            shift = shift,
                            isScrambled = isScrambled,
                            isStealth = isStealth,
                            scrambleSeed = seed,
                            actionLabel = actionLabel,
                            controller = controller
                        )
                    }
                    ParanoidKeyboardLayout.SYMBOLS -> {
                        SymbolsKeyboardRows(
                            isScrambled = isScrambled,
                            isStealth = isStealth,
                            scrambleSeed = seed,
                            actionLabel = actionLabel,
                            controller = controller
                        )
                    }
                    ParanoidKeyboardLayout.NUMERIC,
                    ParanoidKeyboardLayout.DECIMAL -> {
                        NumericKeyboardRows(
                            allowDecimal = layout == ParanoidKeyboardLayout.DECIMAL && !digitsOnly,
                            allowSwitchToText = !digitsOnly,
                            isScrambled = isScrambled,
                            isStealth = isStealth,
                            scrambleSeed = seed,
                            actionLabel = actionLabel,
                            controller = controller
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardSecurityToolbar(
    controller: InAppKeyboardController,
    digitsOnly: Boolean,
    isScrambled: Boolean,
    isStealth: Boolean,
    onHideRequested: () -> Unit
) {
    val langLabel = if (controller.lastTextLanguage == ParanoidKeyboardLayout.QWERTY_RU) "RU" else "EN"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF12161B), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        if (isScrambled) SecureAccentBlue else SecureGreenGlow,
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Zero-IME Isolation",
                tint = SecureGreenGlow,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isScrambled) "PARANOID IME • SCRAMBLED" else "PARANOID SECURE IME",
                color = if (isScrambled) SecureAccentBlue else SecureGreenGlow,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!digitsOnly) {
                ToolbarChip(
                    label = langLabel,
                    active = true,
                    activeColor = SecureGreenGlow,
                    modifier = Modifier.testTag("kb_lang"),
                    onClick = { controller.toggleLanguage() }
                )
            }
            ToolbarChip(
                label = "RND",
                active = isScrambled,
                activeColor = SecureAccentBlue,
                modifier = Modifier.testTag("kb_scramble"),
                onClick = { controller.toggleScramble() }
            )
            ToolbarChip(
                label = "STL",
                active = isStealth,
                activeColor = SecureAccentBlue,
                modifier = Modifier.testTag("kb_stealth"),
                onClick = { controller.toggleStealthMode() }
            )
            ToolbarChip(
                label = "CLR",
                active = false,
                activeColor = SecureRedPanic,
                modifier = Modifier.testTag("kb_clear"),
                onClick = { controller.clearAll() }
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SecureSlate.copy(alpha = 0.6f))
                    .clickable { onHideRequested() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("kb_hide"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Hide Keyboard",
                    tint = SecureTextWhite,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ToolbarChip(
    label: String,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) activeColor.copy(alpha = 0.16f) else SecureDarkSurface)
            .border(
                width = 1.dp,
                color = if (active) activeColor.copy(alpha = 0.65f) else SecureSlate,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (active) activeColor else SecureTextWhite.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun QwertyKeyboardRows(
    isRussian: Boolean,
    shift: ShiftState,
    isScrambled: Boolean,
    isStealth: Boolean,
    scrambleSeed: Int,
    actionLabel: String,
    controller: InAppKeyboardController
) {
    val baseDigits = remember { listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0") }

    val baseRow1 = remember(isRussian) {
        if (isRussian) listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х")
        else listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    }
    val baseRow2 = remember(isRussian) {
        if (isRussian) listOf("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э")
        else listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    }
    val baseRow3 = remember(isRussian) {
        if (isRussian) listOf("я", "ч", "с", "м", "и", "т", "ь", "б", "ю")
        else listOf("z", "x", "c", "v", "b", "n", "m")
    }

    val (digits, row1, row2, row3) = remember(isRussian, isScrambled, scrambleSeed) {
        if (!isScrambled) {
            listOf(baseDigits, baseRow1, baseRow2, baseRow3)
        } else {
            val rnd = java.util.Random(scrambleSeed.toLong() + if (isRussian) 97 else 31)
            val allLetters = (baseRow1 + baseRow2 + baseRow3).shuffled(rnd)
            val s1 = allLetters.subList(0, baseRow1.size)
            val s2 = allLetters.subList(baseRow1.size, baseRow1.size + baseRow2.size)
            val s3 = allLetters.subList(baseRow1.size + baseRow2.size, allLetters.size)
            listOf(baseDigits.shuffled(rnd), s1, s2, s3)
        }
    }

    val formatCase: (String) -> String = { ch ->
        if (shift != ShiftState.OFF) ch.uppercase() else ch
    }

    // Compact number row on top
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        digits.forEach { d ->
            KeyCap(
                label = d,
                isStealth = isStealth,
                modifier = Modifier.weight(1f),
                height = 34.dp,
                isSecondary = true,
                onClick = { controller.insertText(d) }
            )
        }
    }

    // Row 1
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        row1.forEach { ch ->
            val display = formatCase(ch)
            KeyCap(
                label = display,
                isStealth = isStealth,
                modifier = Modifier.weight(1f),
                onClick = { controller.insertText(ch) }
            )
        }
    }

    // Row 2
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isRussian) 0.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        row2.forEach { ch ->
            val display = formatCase(ch)
            KeyCap(
                label = display,
                isStealth = isStealth,
                modifier = Modifier.weight(1f),
                onClick = { controller.insertText(ch) }
            )
        }
    }

    // Row 3: Shift + letters + Backspace
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val shiftIcon = when (shift) {
            ShiftState.OFF -> "⇧"
            ShiftState.SINGLE -> "⬆"
            ShiftState.CAPS_LOCK -> "⇪"
        }
        SpecialKeyCap(
            label = shiftIcon,
            isActive = shift != ShiftState.OFF,
            activeColor = SecureGreenGlow,
            modifier = Modifier
                .weight(1.4f)
                .testTag("kb_shift"),
            onClick = { controller.toggleShift() }
        )
        row3.forEach { ch ->
            val display = formatCase(ch)
            KeyCap(
                label = display,
                isStealth = isStealth,
                modifier = Modifier.weight(1f),
                onClick = { controller.insertText(ch) }
            )
        }
        BackspaceKeyCap(
            modifier = Modifier
                .weight(1.4f)
                .testTag("kb_backspace"),
            onBackspace = { controller.backspace() }
        )
    }

    // Row 4: ?123 | , | Space | . | Action
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        SpecialKeyCap(
            label = "?123",
            modifier = Modifier
                .weight(1.4f)
                .testTag("kb_mode_switch"),
            onClick = { controller.switchLayout(ParanoidKeyboardLayout.SYMBOLS) }
        )
        KeyCap(
            label = if (isRussian) "ъ" else "/",
            isStealth = isStealth,
            isSecondary = true,
            modifier = Modifier.weight(0.9f),
            onClick = { controller.insertText(if (isRussian) "ъ" else "/") }
        )
        KeyCap(
            label = if (isRussian) "SPACE • RU" else "SPACE • EN",
            isStealth = isStealth,
            modifier = Modifier
                .weight(3.8f)
                .testTag("kb_space"),
            fontSizeSp = 10,
            onClick = { controller.insertText(" ") }
        )
        KeyCap(
            label = ".",
            isStealth = isStealth,
            isSecondary = true,
            modifier = Modifier.weight(0.9f),
            onClick = { controller.insertText(".") }
        )
        ActionKeyCap(
            label = actionLabel,
            modifier = Modifier
                .weight(1.8f)
                .testTag("kb_action"),
            onClick = { controller.triggerAction() }
        )
    }
}

@Composable
private fun SymbolsKeyboardRows(
    isScrambled: Boolean,
    isStealth: Boolean,
    scrambleSeed: Int,
    actionLabel: String,
    controller: InAppKeyboardController
) {
    val row1 = remember(isScrambled, scrambleSeed) {
        val base = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        if (isScrambled) base.shuffled(java.util.Random(scrambleSeed.toLong() + 11)) else base
    }
    val row2 = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")
    val row3 = listOf("*", "\"", "'", ":", ";", "!", "?", "~", "\\", "|")
    val row4 = listOf("=", "%", "[", "]", "{", "}", "<", ">", ",")

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        row1.forEach { sym ->
            KeyCap(label = sym, isStealth = isStealth, modifier = Modifier.weight(1f)) {
                controller.insertText(sym)
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        row2.forEach { sym ->
            KeyCap(label = sym, isStealth = isStealth, modifier = Modifier.weight(1f)) {
                controller.insertText(sym)
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        row3.forEach { sym ->
            KeyCap(label = sym, isStealth = isStealth, modifier = Modifier.weight(1f)) {
                controller.insertText(sym)
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        row4.forEach { sym ->
            KeyCap(label = sym, isStealth = isStealth, modifier = Modifier.weight(1f)) {
                controller.insertText(sym)
            }
        }
        BackspaceKeyCap(
            modifier = Modifier
                .weight(1.4f)
                .testTag("kb_backspace"),
            onBackspace = { controller.backspace() }
        )
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        SpecialKeyCap(
            label = "ABC",
            modifier = Modifier
                .weight(1.5f)
                .testTag("kb_mode_switch"),
            onClick = { controller.switchLayout(controller.lastTextLanguage) }
        )
        SpecialKeyCap(
            label = "123",
            modifier = Modifier.weight(1.2f),
            onClick = { controller.switchLayout(ParanoidKeyboardLayout.DECIMAL) }
        )
        KeyCap(
            label = "SPACE",
            isStealth = isStealth,
            modifier = Modifier
                .weight(3.5f)
                .testTag("kb_space"),
            fontSizeSp = 10,
            onClick = { controller.insertText(" ") }
        )
        KeyCap(
            label = ".",
            isStealth = isStealth,
            isSecondary = true,
            modifier = Modifier.weight(1f),
            onClick = { controller.insertText(".") }
        )
        ActionKeyCap(
            label = actionLabel,
            modifier = Modifier
                .weight(1.8f)
                .testTag("kb_action"),
            onClick = { controller.triggerAction() }
        )
    }
}

@Composable
private fun NumericKeyboardRows(
    allowDecimal: Boolean,
    allowSwitchToText: Boolean,
    isScrambled: Boolean,
    isStealth: Boolean,
    scrambleSeed: Int,
    actionLabel: String,
    controller: InAppKeyboardController
) {
    val digits = remember(isScrambled, scrambleSeed) {
        val base = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        if (isScrambled) base.shuffled(java.util.Random(scrambleSeed.toLong() + 53)) else base
    }

    val r1 = digits.subList(0, 3)
    val r2 = digits.subList(3, 6)
    val r3 = digits.subList(6, 9)
    val zero = digits[9]

    listOf(r1, r2, r3).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            row.forEach { d ->
                KeyCap(
                    label = d,
                    isStealth = isStealth,
                    modifier = Modifier.weight(1f),
                    height = 48.dp,
                    fontSizeSp = 18,
                    onClick = { controller.insertText(d) }
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        when {
            allowDecimal -> {
                KeyCap(
                    label = ".",
                    isStealth = isStealth,
                    isSecondary = true,
                    modifier = Modifier.weight(1f),
                    height = 48.dp,
                    fontSizeSp = 18,
                    onClick = { controller.insertText(".") }
                )
            }
            allowSwitchToText -> {
                SpecialKeyCap(
                    label = "ABC",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("kb_mode_switch"),
                    height = 48.dp,
                    onClick = { controller.switchLayout(controller.lastTextLanguage) }
                )
            }
            else -> {
                SpecialKeyCap(
                    label = "CLR",
                    modifier = Modifier.weight(1f),
                    height = 48.dp,
                    onClick = { controller.clearAll() }
                )
            }
        }

        KeyCap(
            label = zero,
            isStealth = isStealth,
            modifier = Modifier.weight(1f),
            height = 48.dp,
            fontSizeSp = 18,
            onClick = { controller.insertText(zero) }
        )

        BackspaceKeyCap(
            modifier = Modifier
                .weight(0.6f)
                .testTag("kb_backspace"),
            height = 48.dp,
            onBackspace = { controller.backspace() }
        )

        ActionKeyCap(
            label = actionLabel,
            modifier = Modifier
                .weight(0.8f)
                .testTag("kb_action"),
            height = 48.dp,
            onClick = { controller.triggerAction() }
        )
    }
}

@Composable
private fun KeyCap(
    label: String,
    isStealth: Boolean,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 44.dp,
    fontSizeSp: Int = 15,
    isSecondary: Boolean = false,
    onClick: () -> Unit
) {
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }

    val bgColor = when {
        isPressed && !isStealth -> SecureGreenMuted
        isSecondary -> Color(0xFF15191E)
        else -> Color(0xFF1D2228)
    }
    val borderColor = when {
        isPressed && !isStealth -> SecureGreenGlow.copy(alpha = 0.8f)
        else -> SecureSlate.copy(alpha = 0.7f)
    }

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .pointerInput(label, onClick) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() }
                )
            }
            .testTag("kb_key_$label"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isPressed && !isStealth) SecureGreenGlow else SecureTextWhite,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = fontSizeSp.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun SpecialKeyCap(
    label: String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 44.dp,
    isActive: Boolean = false,
    activeColor: Color = SecureAccentBlue,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val bgColor = if (isActive) activeColor.copy(alpha = 0.22f) else Color(0xFF232932)
    val borderColor = if (isActive) activeColor.copy(alpha = 0.8f) else SecureSlate

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) activeColor else SecureTextWhite.copy(alpha = 0.9f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun BackspaceKeyCap(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 44.dp,
    onBackspace: () -> Unit
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val latestBackspace by rememberUpdatedState(onBackspace)
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isPressed) SecureRedPanicMuted else Color(0xFF232932))
            .border(
                1.dp,
                if (isPressed) SecureRedPanic else SecureSlate,
                RoundedCornerShape(6.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        latestBackspace()
                        val repeatJob = scope.launch {
                            delay(360L)
                            while (true) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                latestBackspace()
                                delay(55L)
                            }
                        }
                        tryAwaitRelease()
                        repeatJob.cancel()
                        isPressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⌫",
            color = if (isPressed) SecureRedPanic else SecureTextWhite,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun ActionKeyCap(
    label: String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 44.dp,
    onClick: () -> Unit
) {
    val view = LocalView.current

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(SecureGreenMuted)
            .border(1.dp, SecureGreenGlow.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            if (label.equals("SEND", ignoreCase = true)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = SecureGreenGlow,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                color = SecureGreenGlow,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}
