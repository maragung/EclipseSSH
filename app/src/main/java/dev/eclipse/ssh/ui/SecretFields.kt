package dev.eclipse.ssh.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType

/**
 * The keyboard any field that takes a secret is given.
 *
 * The point is what it switches *off*: a password keyboard carries no autocorrect, no suggestions and
 * no learned-word dictionary, so a passphrase cannot be captured into the keyboard's own store and
 * offered back to whatever app asks for it next. That matters more here than on an ordinary text
 * field because these values are the ones the vault exists to hold, and the keyboard has a copy of
 * everything typed into it unless it is told not to. `singleLine` alone does not do this, and neither
 * does `PasswordVisualTransformation`, which only changes what is drawn.
 *
 * Shared rather than repeated at each call site so a fifth secret field cannot quietly appear without
 * it. The PIN fields use `NumberPassword` for the same reason over a numeric keypad.
 *
 * Hoisted out of `MainActivity` when the host form moved to a window of its own: the copy this
 * replaced was `private` to that file, so the two windows that own a secret field had to keep
 * agreeing by hand. There is one definition now.
 */
internal val SecretFieldKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password)

/**
 * The clipboard-paste affordance for any field created by [SecretFieldKeyboard] above.
 *
 * Long-press paste in a password field is unreliable in exactly the situation it is needed most: the
 * IME's toolbar over a `TYPE_TEXT_VARIATION_PASSWORD` field varies by keyboard, and a clip copied by
 * a password manager often carries a trailing newline a `singleLine` field cannot accept. A button
 * the user can see sidesteps both - and [onPaste] goes through the view model or the window that owns
 * the field, so the read is subject to the same audited [dev.eclipse.ssh.security.SecureClipboard]
 * boundary and newline normalization as every other clipboard access in the app.
 *
 * [what] exists for the screen reader. The host form can show a password, a key passphrase and a proxy
 * password at once, and three buttons all announcing "Paste" would be a list nobody can tell apart;
 * the backup window shows one field and names it the same way rather than special-casing itself.
 *
 * The paste *replaces* the field's contents rather than appending to them - these fields are never
 * pre-filled from storage, so whatever is in one is either empty or a typo being corrected.
 */
@Composable
internal fun SecretPasteButton(what: String, onPaste: () -> String?, into: (String) -> Unit) {
    IconButton(onClick = { onPaste()?.let(into) }) {
        Icon(Icons.Default.ContentPaste, contentDescription = "Paste $what from clipboard")
    }
}
