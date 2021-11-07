/*
 * Copyright (C) 2021 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("MemberVisibilityCanBePrivate")

package dev.patrickgold.florisboard.ime.keyboard

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.children
import androidx.lifecycle.LiveData
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.ime.text.keyboard.KeyboardMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.properties.Delegates

/**
 * This class is a helper managing the state of the text input logic which
 * affects the keyboard view in rendering and layouting the keys.
 *
 * The state class can hold flags or small unsigned integers, all added up
 * at max 64-bit though.
 *
 * The structure of this 8-byte state register is as follows: (Lower 4 bytes are pretty experimental rn)
 *
 * <Byte 3> | <Byte 2> | <Byte 1> | <Byte 0> | Description
 * ---------|----------|----------|----------|---------------------------------
 *          |          |          |     1111 | Active [KeyboardMode]
 *          |          |          | 1111     | Active [KeyVariation]
 *          |          |        1 |          | Caps flag
 *          |          |       1  |          | Caps lock flag
 *          |          |      1   |          | Is selection active (length > 0)
 *          |          |     1    |          | Is manual selection mode
 *          |          |    1     |          | Is manual selection mode (start)
 *          |          |   1      |          | Is manual selection mode (end)
 *          |          | 1        |          | Is private mode
 *          |        1 |          |          | Is Smartbar quick actions visible
 *          |       1  |          |          | Is Smartbar showing inline suggestions
 *          |      1   |          |          | Is composing enabled
 *          |   1      |          |          | Is character half-width enabled
 *          |  1       |          |          | Is Kana Kata enabled
 *          | 1        |          |          | Is Kana small
 *
 * <Byte 7> | <Byte 6> | <Byte 5> | <Byte 4> | Description
 * ---------|----------|----------|----------|---------------------------------
 *          |          |          |     1111 | [ImeOptions.enterAction]
 *          |          |          |    1     | [ImeOptions.flagForceAscii]
 *          |          |          |   1      | [ImeOptions.flagNavigateNext]
 *          |          |          |  1       | [ImeOptions.flagNavigatePrevious]
 *          |          |          | 1        | [ImeOptions.flagNoAccessoryAction]
 *          |          |        1 |          | [ImeOptions.flagNoEnterAction]
 *          |          |       1  |          | [ImeOptions.flagNoExtractUi]
 *          |          |      1   |          | [ImeOptions.flagNoFullscreen]
 *          |          |     1    |          | [ImeOptions.flagNoPersonalizedLearning]
 *          |          |  111     |          | [InputAttributes.type]
 *          |     1111 | 1        |          | [InputAttributes.variation]
 *          |   11     |          |          | [InputAttributes.capsMode]
 *          |  1       |          |          | [InputAttributes.flagNumberDecimal]
 *          | 1        |          |          | [InputAttributes.flagNumberSigned]
 *        1 |          |          |          | [InputAttributes.flagTextAutoComplete]
 *       1  |          |          |          | [InputAttributes.flagTextAutoCorrect]
 *      1   |          |          |          | [InputAttributes.flagTextImeMultiLine]
 *     1    |          |          |          | [InputAttributes.flagTextMultiLine]
 *    1     |          |          |          | [InputAttributes.flagTextNoSuggestions]
 *
 * The resulting structure is only relevant during a runtime lifespan and
 * thus can easily be changed without worrying about destroying some saved state.
 *
 * @property rawValue The internal register used to store the flags and region ints that
 *  this keyboard state represents.
 * @property maskOfInterest The mask which is applied when comparing this state with another.
 *  Is useful if only parts of a state instance is relevant to look at.
 */
class KeyboardState private constructor(
    initValue: ULong,
    private var maskOfInterest: ULong,
) : LiveData<KeyboardState>() {

    companion object {
        const val M_KEYBOARD_MODE: ULong =                  0x0Fu
        const val O_KEYBOARD_MODE: Int =                    0
        const val M_KEY_VARIATION: ULong =                  0x0Fu
        const val O_KEY_VARIATION: Int =                    4

        const val F_CAPS: ULong =                           0x00000100u
        const val F_CAPS_LOCK: ULong =                      0x00000200u
        const val F_IS_SELECTION_MODE: ULong =              0x00000400u
        const val F_IS_MANUAL_SELECTION_MODE: ULong =       0x00000800u
        const val F_IS_MANUAL_SELECTION_MODE_START: ULong = 0x00001000u
        const val F_IS_MANUAL_SELECTION_MODE_END: ULong =   0x00002000u
        const val F_IS_PRIVATE_MODE: ULong =                0x00008000u
        const val F_IS_QUICK_ACTIONS_VISIBLE: ULong =       0x00010000u
        const val F_IS_SHOWING_INLINE_SUGGESTIONS: ULong =  0x00020000u
        const val F_IS_COMPOSING_ENABLED: ULong =           0x00040000u

        const val F_IS_CHAR_HALF_WIDTH: ULong =             0x00200000u
        const val F_IS_KANA_KATA: ULong =                   0x00400000u
        const val F_IS_KANA_SMALL: ULong =                  0x00800000u

        const val STATE_ALL_ZERO: ULong =                   0uL

        const val INTEREST_ALL: ULong =                     ULong.MAX_VALUE
        const val INTEREST_NONE: ULong =                    0uL
        const val INTEREST_TEXT: ULong =                    0xFF_FF_FF_FF_00_FF_FF_FFu
        const val INTEREST_MEDIA: ULong =                   0x00_00_00_00_FF_00_00_00u

        const val BATCH_ZERO: Int =                         0

        fun new(
            value: ULong = STATE_ALL_ZERO,
            maskOfInterest: ULong = INTEREST_ALL
        ) = KeyboardState(value, maskOfInterest)
    }

    private var rawValue by Delegates.observable(initValue) { _, _, _ -> dispatchState() }
    private val batchEditCount = AtomicInteger(BATCH_ZERO)

    val imeOptions: ImeOptions = ImeOptions(this)
    val inputAttributes: InputAttributes = InputAttributes(this)

    init {
        dispatchState()
    }

    override fun setValue(value: KeyboardState?) {
        error("Do not use setValue() directly")
    }

    override fun postValue(value: KeyboardState?) {
        error("Do not use postValue() directly")
    }

    /**
     * Dispatches the new state to all observers if [batchEditCount] is [BATCH_ZERO] (= no active batch edits).
     */
    private fun dispatchState() {
        if (batchEditCount.get() == BATCH_ZERO) {
            try {
                super.setValue(this)
            } catch (e: Exception) {
                super.postValue(this)
            }
        }
    }

    /**
     * Begins a batch edit. Any modifications done during an active batch edit will not be dispatched to observers
     * until [endBatchEdit] is called. At any time given there can be multiple active batch edits at once. This
     * method is thread-safe and can be called from any thread.
     */
    fun beginBatchEdit() {
        batchEditCount.incrementAndGet()
    }

    /**
     * Ends a batch edit. Will dispatch the current state if there are no more other batch edits active. This method is
     * thread-safe and can be called from any thread.
     */
    fun endBatchEdit() {
        batchEditCount.decrementAndGet()
        dispatchState()
    }

    /**
     * Performs a batch edit by executing the modifier [block]. Any exception that [block] throws will be caught and
     * re-thrown after correctly ending the batch edit.
     */
    inline fun batchEdit(block: (KeyboardState) -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        beginBatchEdit()
        try {
            block(this)
        } catch (e: Throwable) {
            throw e
        } finally {
            endBatchEdit()
        }
    }

    /**
     * Resets this state register.
     *
     * @param newValue Optional, used to initialize the register value after the reset.
     *  Defaults to [STATE_ALL_ZERO].
     */
    fun reset(newValue: ULong = STATE_ALL_ZERO) {
        rawValue = newValue
    }

    /**
     * Resets this state register.
     *
     * @param newState A reference to a state which register value should be copied after
     *  the reset.
     */
    fun reset(newState: KeyboardState) {
        rawValue = newState.rawValue
    }

    /**
     * Updates this state based on the info passed from [editorInfo].
     *
     * @param editorInfo The [EditorInfo] used to initialize all flags and regions relevant
     *  to the info this object provides.
     */
    fun update(editorInfo: EditorInfo) {
        beginBatchEdit()
        imeOptions.update(editorInfo)
        inputAttributes.update(editorInfo)
        endBatchEdit()
    }

    internal fun getFlag(f: ULong): Boolean {
        return (rawValue and f) != STATE_ALL_ZERO
    }

    internal fun setFlag(f: ULong, v: Boolean) {
        rawValue = if (v) { rawValue or f } else { rawValue and f.inv() }
    }

    internal fun getRegion(m: ULong, o: Int): Int {
        return ((rawValue shr o) and m).toInt()
    }

    internal fun setRegion(m: ULong, o: Int, v: Int) {
        rawValue = (rawValue and (m shl o).inv()) or ((v.toULong() and m) shl o)
    }

    fun isEqualTo(other: KeyboardState): Boolean {
        return (other.rawValue and maskOfInterest) == (rawValue and maskOfInterest)
    }

    fun isDifferentTo(other: KeyboardState): Boolean {
        return !isEqualTo(other)
    }

    override fun hashCode(): Int {
        var result = rawValue.hashCode()
        result = 31 * result + maskOfInterest.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KeyboardState

        if (rawValue != other.rawValue) return false
        if (maskOfInterest != other.maskOfInterest) return false
        if (imeOptions != other.imeOptions) return false
        if (inputAttributes != other.inputAttributes) return false

        return true
    }

    var keyVariation: KeyVariation
        get() = KeyVariation.fromInt(getRegion(M_KEY_VARIATION, O_KEY_VARIATION))
        set(v) { setRegion(M_KEY_VARIATION, O_KEY_VARIATION, v.toInt()) }

    var keyboardMode: KeyboardMode
        get() = KeyboardMode.fromInt(getRegion(M_KEYBOARD_MODE, O_KEYBOARD_MODE))
        set(v) { setRegion(M_KEYBOARD_MODE, O_KEYBOARD_MODE, v.toInt()) }

    var caps: Boolean
        get() = getFlag(F_CAPS)
        set(v) { setFlag(F_CAPS, v) }

    var capsLock: Boolean
        get() = getFlag(F_CAPS_LOCK)
        set(v) { setFlag(F_CAPS_LOCK, v) }

    var isSelectionMode: Boolean
        get() = getFlag(F_IS_SELECTION_MODE)
        set(v) { setFlag(F_IS_SELECTION_MODE, v) }

    var isManualSelectionMode: Boolean
        get() = getFlag(F_IS_MANUAL_SELECTION_MODE)
        set(v) { setFlag(F_IS_MANUAL_SELECTION_MODE, v) }

    var isManualSelectionModeStart: Boolean
        get() = getFlag(F_IS_MANUAL_SELECTION_MODE_START)
        set(v) { setFlag(F_IS_MANUAL_SELECTION_MODE_START, v) }

    var isManualSelectionModeEnd: Boolean
        get() = getFlag(F_IS_MANUAL_SELECTION_MODE_END)
        set(v) { setFlag(F_IS_MANUAL_SELECTION_MODE_END, v) }

    var isCursorMode: Boolean
        get() = !isSelectionMode
        set(v) { isSelectionMode = !v }

    var isPrivateMode: Boolean
        get() = getFlag(F_IS_PRIVATE_MODE)
        set(v) { setFlag(F_IS_PRIVATE_MODE, v) }

    val isRawInputEditor: Boolean
        get() = inputAttributes.type == InputAttributes.Type.NULL

    val isRichInputEditor: Boolean
        get() = inputAttributes.type != InputAttributes.Type.NULL

    var isQuickActionsVisible: Boolean
        get() = getFlag(F_IS_QUICK_ACTIONS_VISIBLE)
        set(v) { setFlag(F_IS_QUICK_ACTIONS_VISIBLE, v) }

    var isShowingInlineSuggestions: Boolean
        get() = getFlag(F_IS_SHOWING_INLINE_SUGGESTIONS)
        set(v) { setFlag(F_IS_SHOWING_INLINE_SUGGESTIONS, v) }

    var isComposingEnabled: Boolean
        get() = getFlag(F_IS_COMPOSING_ENABLED)
        set(v) { setFlag(F_IS_COMPOSING_ENABLED, v) }

    var isKanaKata: Boolean
        get() = getFlag(F_IS_KANA_KATA)
        set(v) { setFlag(F_IS_KANA_KATA, v) }

    var isCharHalfWidth: Boolean
        get() = getFlag(F_IS_CHAR_HALF_WIDTH)
        set(v) { setFlag(F_IS_CHAR_HALF_WIDTH, v) }

    var isKanaSmall: Boolean
        get() = getFlag(F_IS_KANA_SMALL)
        set(v) { setFlag(F_IS_KANA_SMALL, v) }

    interface OnUpdateStateListener {
        /**
         * Adds the ability for Views to intercept a update keyboard state dispatch.
         *
         * @param newState Reference to the new state.
         *
         * @return True if the update was intercepted (and thus the child views have to
         *  be manually updated if needed, false if no interception happened.
         */
        fun onInterceptUpdateKeyboardState(newState: KeyboardState): Boolean = false

        /**
         * A new keyboard state is dispatched to all views in this view tree.
         *
         * @param newState Reference to the new state.
         */
        fun onUpdateKeyboardState(newState: KeyboardState)
    }
}

fun View.updateKeyboardState(newState: KeyboardState) {
    val intercepted: Boolean
    if (this is KeyboardState.OnUpdateStateListener) {
        intercepted = this.onInterceptUpdateKeyboardState(newState)
        this.onUpdateKeyboardState(newState)
    } else {
        intercepted = false
    }
    if (this is ViewGroup && !intercepted) {
        this.children.forEach { it.updateKeyboardState(newState) }
    }
}
