package com.search.browser

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * The search box, able to complete a site's address inline as Chrome's
 * omnibox does: typing "you" shows "youtube.com" with "tube.com" selected,
 * Enter goes there, and anything else typed carries on from "you".
 *
 * The hard part is the keyboard. Gboard and others edit the word being typed
 * in place (a composing region), and know nothing of text the app added after
 * it - left there, the next letter lands before the suggestion and the word
 * doubles up ("youttube.com"). So, as Chrome's own field does, every edit the
 * keyboard sends first takes the suggestion away, and only then is applied;
 * the activity offers a new one once the edit is in. Backspace with a
 * suggestion showing removes just the suggestion.
 *
 * With nothing suggested this is a plain EditText: every call passes straight
 * through. Settings > Search Security > Complete addresses as you type, off
 * by default.
 */
class AutocompleteEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : androidx.appcompat.widget.AppCompatEditText(context, attrs, defStyleAttr) {

    /** The suffix shown selected after what was typed, or null. */
    private var completion: String? = null

    /** True while this view itself is adding or removing a suggestion. */
    var isApplying = false
        private set

    val isCompleting: Boolean get() = completion != null

    /** The text as typed, without a suggestion. */
    val typedText: String
        get() {
            val t = text?.toString() ?: ""
            val c = completion ?: return t
            return if (t.endsWith(c)) t.substring(0, t.length - c.length) else t
        }

    /** Shows [suffix] after the typed text, selected, so typing replaces it and Enter keeps it. */
    fun complete(suffix: String) {
        val e = text ?: return
        if (suffix.isEmpty() || completion != null) return
        val typedLen = e.length
        isApplying = true
        try {
            e.append(suffix)
            completion = suffix
            setSelection(typedLen, e.length)
        } finally {
            isApplying = false
        }
    }

    /** Takes the suggestion away, leaving what was typed. True if there was one. */
    fun dropCompletion(): Boolean {
        val c = completion ?: return false
        completion = null
        val e = text ?: return true
        val start = e.length - c.length
        if (start >= 0 && e.subSequence(start, e.length).toString() == c) {
            isApplying = true
            try { e.delete(start, e.length) } finally { isApplying = false }
        }
        return true
    }

    override fun setText(text: CharSequence?, type: android.widget.TextView.BufferType?) {
        // Text set from code (search opening, Clear, a suggestion's arrow)
        // replaces any suggestion with it.
        completion = null
        super.setText(text, type)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // The cursor moved off the suggestion (a tap into the text): what
        // is there now is simply text.
        val c = completion ?: return
        if (isApplying) return
        val len = text?.length ?: return
        if (selStart != len - c.length || selEnd != len) completion = null
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                dropCompletion()
                return super.commitText(text, newCursorPosition)
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                dropCompletion()
                return super.setComposingText(text, newCursorPosition)
            }

            override fun setComposingRegion(start: Int, end: Int): Boolean {
                dropCompletion()
                return super.setComposingRegion(start, end)
            }

            // Backspace on a suggestion takes only the suggestion.
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (dropCompletion()) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (dropCompletion()) return true
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event?.keyCode == KeyEvent.KEYCODE_DEL && completion != null) {
                    if (event.action == KeyEvent.ACTION_DOWN) dropCompletion()
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}
