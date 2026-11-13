package io.github.munetoshi.slidecompose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

private val Source = Canvas()

/**
 * The events a window on the desktop gives Compose for one press of the key [code]: pressed,
 * typed when it types [char], and released, as AWT sends them. A text field types from the typed
 * event and leaves the pressed one.
 */
internal fun keyPress(code: Int, char: Char = AwtKeyEvent.CHAR_UNDEFINED, modifiers: Int = 0): List<KeyEvent> {
    val types = char != AwtKeyEvent.CHAR_UNDEFINED && modifiers and InputEvent.META_DOWN_MASK == 0
    return listOfNotNull(
        keyEvent(AwtKeyEvent.KEY_PRESSED, code, char, modifiers),
        if (types) keyEvent(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, char, modifiers) else null,
        keyEvent(AwtKeyEvent.KEY_RELEASED, code, char, modifiers),
    )
}

/** The presses that type [text], one character a key. */
internal fun typing(text: String): List<KeyEvent> = text.flatMap(::characterPress)

private fun characterPress(char: Char): List<KeyEvent> = keyPress(AwtKeyEvent.getExtendedKeyCodeForChar(char.code), char)

@OptIn(InternalComposeUiApi::class)
private fun keyEvent(id: Int, code: Int, char: Char, modifiers: Int): KeyEvent {
    val type = when (id) {
        AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
        AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
        else -> KeyEventType.Unknown
    }
    return KeyEvent(
        Key(code),
        type,
        codePoint = char.code,
        isCtrlPressed = modifiers and InputEvent.CTRL_DOWN_MASK != 0,
        isMetaPressed = modifiers and InputEvent.META_DOWN_MASK != 0,
        isAltPressed = modifiers and InputEvent.ALT_DOWN_MASK != 0,
        isShiftPressed = modifiers and InputEvent.SHIFT_DOWN_MASK != 0,
        nativeEvent = AwtKeyEvent(Source, id, 0L, modifiers, code, char),
    )
}


/** Clicks [at] with the mouse's primary button, as a text field needs to take the focus. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun ImageComposeScene.click(at: Offset) {
    sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
    sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
}

/**
 * Sends [events] to the scene one by one, each in a frame of its own, as a window does: a text
 * field acts on a key with the text it was composed with.
 */
internal fun ImageComposeScene.press(events: List<KeyEvent>) {
    for (event in events) {
        sendKeyEvent(event)
        render()
    }
}

/** The pixels of the rows from [top] to [bottom] of the image, BGRA, whatever order the image keeps them in. */
internal fun Image.pixelsIn(top: Int, bottom: Int): ByteArray =
    Bitmap().use { bitmap ->
        check(bitmap.allocPixels(ImageInfo.makeN32Premul(width, height)))
        check(readPixels(bitmap))
        checkNotNull(bitmap.readPixels()).copyOfRange(top * width * 4, bottom * width * 4)
    }
