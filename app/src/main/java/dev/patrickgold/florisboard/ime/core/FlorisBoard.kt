package dev.patrickgold.florisboard.ime.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.preference.PreferenceManager
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.key.KeyCode
import dev.patrickgold.florisboard.ime.key.KeyData
import dev.patrickgold.florisboard.ime.key.KeyType
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.KeyboardView
import dev.patrickgold.florisboard.ime.layout.LayoutManager
import dev.patrickgold.florisboard.ime.smartbar.SmartbarManager
import dev.patrickgold.florisboard.util.initDefaultPreferences
import java.util.*

class FlorisBoard : InputMethodService() {

    private var activeKeyboardMode: KeyboardMode? = null
    private var hasCapsRecentlyChanged: Boolean = false
    private val keyboardViews = EnumMap<KeyboardMode, KeyboardView>(
        KeyboardMode::class.java)
    private var keyboardViewPanel: LinearLayout? = null
    private var oneHandedCtrlPanelStart: LinearLayout? = null
    private var oneHandedCtrlPanelEnd: LinearLayout? = null
    private val osHandler = Handler()

    var caps: Boolean = false
    var capsLock: Boolean = false

    var audioManager: AudioManager? = null
    val layoutManager = LayoutManager(this)
    var prefs: PrefHelper? = null
        private set
    val smartbarManager: SmartbarManager = SmartbarManager(this)

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View? {
        // Set default preference values if user has not used preferences screen
        initDefaultPreferences(this)

        val rootView = layoutInflater.inflate(R.layout.florisboard, null) as LinearLayout
        prefs = PrefHelper(rootView.context, PreferenceManager.getDefaultSharedPreferences(rootView.context))
        prefs!!.sync()
        layoutManager.autoFetchAssociationsFromPrefs()
        initializeOneHandedEnvironment(rootView)
        for (mode in KeyboardMode.values()) {
            val keyboardView = KeyboardView(rootView.context, this)
            keyboardViewPanel?.addView(keyboardView)
            keyboardView.visibility = View.GONE
            keyboardView.setKeyboardMode(mode)
            keyboardViews[mode] = keyboardView
        }
        rootView.findViewById<LinearLayout>(R.id.keyboard_preview).visibility = View.GONE
        setActiveKeyboardMode(KeyboardMode.CHARACTERS)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return rootView
    }

    override fun onWindowShown() {
        super.onWindowShown()
        prefs!!.sync()
        smartbarManager.isQuickActionsViewVisible = false
        updateOneHandedPanelVisibility()
    }

    private fun setActiveKeyboardMode(mode: KeyboardMode) {
        keyboardViews[activeKeyboardMode]?.visibility = View.GONE
        keyboardViews[mode]?.visibility = View.VISIBLE
        activeKeyboardMode = mode
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        smartbarManager.onStartInputView()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        smartbarManager.onFinishInputView()
    }

    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        if (!isFullscreenMode && outInsets != null) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOneHandedPanelVisibility()
    }

    private fun initializeOneHandedEnvironment(rootView: ViewGroup) {
        oneHandedCtrlPanelStart = rootView.findViewById(R.id.one_handed_ctrl_panel_start)
        oneHandedCtrlPanelEnd = rootView.findViewById(R.id.one_handed_ctrl_panel_end)

        keyboardViewPanel = rootView.findViewById(R.id.keyboard_view_panel)
        keyboardViewPanel?.addView(smartbarManager.createSmartbarView())

        rootView.findViewById<ImageButton>(R.id.one_handed_ctrl_move_start)
            .setOnClickListener { v -> onOneHandedPanelButtonClick(v) }
        rootView.findViewById<ImageButton>(R.id.one_handed_ctrl_move_end)
            .setOnClickListener { v -> onOneHandedPanelButtonClick(v) }
        rootView.findViewById<ImageButton>(R.id.one_handed_ctrl_close_start)
            .setOnClickListener { v -> onOneHandedPanelButtonClick(v) }
        rootView.findViewById<ImageButton>(R.id.one_handed_ctrl_close_end)
            .setOnClickListener { v -> onOneHandedPanelButtonClick(v) }
    }

    private fun onOneHandedPanelButtonClick(v: View) {
        when (v.id) {
            R.id.one_handed_ctrl_move_start -> {
                prefs?.oneHandedMode = "start"
            }
            R.id.one_handed_ctrl_move_end -> {
                prefs?.oneHandedMode = "end"
            }
            R.id.one_handed_ctrl_close_start,
            R.id.one_handed_ctrl_close_end -> {
                prefs?.oneHandedMode = "off"
            }
        }
        updateOneHandedPanelVisibility()
    }

    fun updateOneHandedPanelVisibility() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) {
            oneHandedCtrlPanelStart?.visibility = View.GONE
            oneHandedCtrlPanelEnd?.visibility = View.GONE
        } else {
            when (prefs?.oneHandedMode) {
                "off" -> {
                    oneHandedCtrlPanelStart?.visibility = View.GONE
                    oneHandedCtrlPanelEnd?.visibility = View.GONE
                }
                "start" -> {
                    oneHandedCtrlPanelStart?.visibility = View.GONE
                    oneHandedCtrlPanelEnd?.visibility = View.VISIBLE
                }
                "end" -> {
                    oneHandedCtrlPanelStart?.visibility = View.VISIBLE
                    oneHandedCtrlPanelEnd?.visibility = View.GONE
                }
            }
        }
    }

    fun sendKeyPress(keyData: KeyData) {
        val ic = currentInputConnection
        if (keyData.type == KeyType.CHARACTER) {
            var text = keyData.code.toChar()
            if (caps) {
                text = text.toUpperCase()
            }
            if (keyData.code == KeyCode.SPACE) {
                ic.finishComposingText()
                smartbarManager.composingText = ""
                ic.commitText(text.toString(), 1)
            } else {
                smartbarManager.composingText += text.toString()
                smartbarManager.generateCandidatesFromSuggestions()
                ic.setComposingText(smartbarManager.composingText, 1)
            }
            if (!capsLock) {
                caps = false
            }
        } else {
            when (keyData.code) {
                KeyCode.DELETE -> {
                    if (smartbarManager.composingText.isNotBlank()) {
                        smartbarManager.composingText = smartbarManager.composingText.dropLast(1)
                        ic.setComposingText(smartbarManager.composingText, 1)
                    } else {
                        ic.deleteSurroundingText(1, 0)
                    }
                }
                KeyCode.ENTER -> {
                    val action = currentInputEditorInfo.imeOptions
                    Log.d("imeOptions", action.toString())
                    Log.d("imeOptions action only", (action and 0xFF).toString())
                    if (action and EditorInfo.IME_FLAG_NO_ENTER_ACTION > 0) {
                        ic.sendKeyEvent(
                            KeyEvent(
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_ENTER
                            )
                        )
                    } else {
                        when (action and EditorInfo.IME_MASK_ACTION) {
                            EditorInfo.IME_ACTION_DONE,
                            EditorInfo.IME_ACTION_GO,
                            EditorInfo.IME_ACTION_NEXT,
                            EditorInfo.IME_ACTION_PREVIOUS,
                            EditorInfo.IME_ACTION_SEARCH,
                            EditorInfo.IME_ACTION_SEND -> {
                                ic.performEditorAction(action)
                            }
                            else -> {
                                ic.sendKeyEvent(
                                    KeyEvent(
                                        KeyEvent.ACTION_DOWN,
                                        KeyEvent.KEYCODE_ENTER
                                    )
                                )
                            }
                        }
                    }
                }
                KeyCode.LANGUAGE_SWITCH -> {
                    Toast.makeText(this, "[NYI]: Language switch",
                        Toast.LENGTH_SHORT).show()
                }
                KeyCode.SHIFT -> {
                    if (hasCapsRecentlyChanged) {
                        osHandler.removeCallbacksAndMessages(null)
                        caps = true
                        capsLock = true
                        hasCapsRecentlyChanged = false
                    } else {
                        caps = !caps
                        capsLock = false
                        hasCapsRecentlyChanged = true
                        osHandler.postDelayed({
                            hasCapsRecentlyChanged = false
                        }, 300)
                    }
                }
                KeyCode.SHOW_INPUT_METHOD_PICKER -> {
                    val im = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    im.showInputMethodPicker()
                }
                KeyCode.VIEW_CHARACTERS -> setActiveKeyboardMode(KeyboardMode.CHARACTERS)
                // TODO: Implement numeric layout
                //KeyCode.VIEW_NUMERIC -> setActiveKeyboardMode(KeyboardMode.NUMERIC)
                KeyCode.VIEW_NUMERIC -> {
                    Toast.makeText(this, "[NYI]: Numeric keyboard layout",
                        Toast.LENGTH_SHORT).show()
                }
                KeyCode.VIEW_SYMBOLS -> setActiveKeyboardMode(KeyboardMode.SYMBOLS)
                KeyCode.VIEW_SYMBOLS2 -> setActiveKeyboardMode(KeyboardMode.SYMBOLS2)
            }
        }
    }
}