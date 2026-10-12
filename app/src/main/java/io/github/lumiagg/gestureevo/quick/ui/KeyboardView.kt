package io.github.lumiagg.gestureevo.quick.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.allViews
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.VibratorUtils

enum class KeyboardType {
    FULL_KEY, DOUBLE_KEY, T9;

    companion object {
        fun valueOfSafe(value: String): KeyboardType {
            return try {
                KeyboardType.valueOf(value)
            } catch (e: IllegalArgumentException) {
                DOUBLE_KEY
            }
        }
    }
}

class KeyboardView : ConstraintLayout {
    constructor(context: Context) : super(context) {
        initialize(null)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        initialize(attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    ) {
        initialize(attrs)
    }

    private lateinit var inputPromptView: MaterialTextView
    private var inputPrompt = mutableListOf<String>()
    var onChangeListener = object : KeyboardOnChangeListener {}
    private var inputKeySequence = mutableListOf<Char>()
    var keyboardType = KeyboardType.DOUBLE_KEY
        set(value) {
            if (field != value) {
                field = value
                reloadKeyboard()
            }
        }


    private fun initialize(attrs: AttributeSet?) {
        val layoutInflater = LayoutInflater.from(context)
        val resource = when (keyboardType) {
            KeyboardType.FULL_KEY -> R.layout.quick_keyboard_double_key
            KeyboardType.DOUBLE_KEY -> R.layout.quick_keyboard_double_key
            KeyboardType.T9 -> R.layout.quick_keyboard_t9
        }
        layoutInflater.inflate(resource, this, true)
        inputPromptView = findViewById(R.id.inputPrompt)
        initKeyboard()
    }

    private fun reloadKeyboard() {
        // Remove existing child views
        removeAllViews()
        // Reinitialize view tree
        initialize(null)
    }

    @SuppressLint("SetTextI18n")
    private fun initKeyboard() {
        allViews.forEach {
            when (it) {
                is KeyboardLetterView -> {
                    val letterView = (it as KeyboardLetterView)
                    letterView.setOnClickListener {
                        when (keyboardType) {
                            KeyboardType.T9 -> {
                                val inputKey = letterView.getTopText().lowercase().firstOrNull()
                                inputKey?.let {
                                    inputKeySequence.add(it)
                                    val newPrompt = letterView.getTopText()
                                    inputPrompt.add(newPrompt)
                                }
                            }

                            KeyboardType.FULL_KEY, KeyboardType.DOUBLE_KEY -> {
                                val inputKey = letterView.getLeftText().lowercase().firstOrNull()
                                inputKey?.let {
                                    inputKeySequence.add(inputKey)
                                    val newPrompt =
                                        "${letterView.getLeftText()}${letterView.getRightText()}"
                                    inputPrompt.add(newPrompt)
                                }
                            }
                        }
                        updateInput()
                        VibratorUtils.vibrator(context, false)
                    }
                    letterView.setOnLongClickListener {
                        if (letterView.getTopText().isEmpty()) return@setOnLongClickListener true
                        inputKeySequence.add(letterView.getTopText().lowercase()[0])
                        inputPrompt.add(letterView.getTopText())
                        updateInput()
                        VibratorUtils.vibrator(context, true)
                        return@setOnLongClickListener true
                    }
                }

                is KeyboardFuncView -> {
                    it.setOnClickListener { funcView ->
                        val view = funcView as KeyboardFuncView
                        keyboardFuncClickDispatch(view)
                    }
                    it.setOnLongClickListener { funcView ->
                        val view = funcView as KeyboardFuncView
                        keyboardFuncLongClickDispatch(view)
                        return@setOnLongClickListener true
                    }
                }
            }
        }
    }

    private fun keyboardFuncClickDispatch(view: KeyboardFuncView) {
        when (view.func) {
            FuncEnum.BACKSPACE -> {
                inputKeySequence.removeLastOrNull()
                inputPrompt.removeLastOrNull()
                updateInput()
            }

            FuncEnum.ENTER -> {
                onChangeListener.onEnter(false)
            }

            FuncEnum.SETTINGS -> {
                view.isSelected = !view.isSelected
                if (view.isSelected) {
                    view.setIcon(R.drawable.baseline_save_24)
                } else {
                    view.setIcon(R.drawable.baseline_settings_24)
                }
                onChangeListener.onSettings(false)
            }

            FuncEnum.None -> {}
        }
    }

    private fun keyboardFuncLongClickDispatch(view: KeyboardFuncView) {
        when (view.func) {
            FuncEnum.BACKSPACE -> {
                inputKeySequence.clear()
                inputPrompt.clear()
                updateInput()
            }

            FuncEnum.ENTER -> {
                onChangeListener.onEnter(true)
            }

            FuncEnum.SETTINGS -> {
                onChangeListener.onSettings(true)
            }

            FuncEnum.None -> {}
        }
    }

    private fun updateInput() {
        inputPromptView.text = inputPrompt.joinToString("'")
        onChangeListener.onInput(inputKeySequence.joinToString(""))
    }

    fun setInputPrompt(msg: String) {
        inputPromptView.text = msg
    }

    fun resumeContent(keys: String) {
        keys.split("/").forEach {
            inputKeySequence.add(it.getOrElse(0) { ' ' })
        }
        updateInput()
        inputKeySequence.clear()
    }

    fun getInputKeys(): String {
        return inputKeySequence.joinToString("/")
    }

    private fun generatePermutations(letters: List<String>): List<List<String>> {
        if (letters.isEmpty()) return listOf()
        else if (letters.size == 1) {
            return letters[0].map { listOf(it.toString()) }
        }

        val permutations = mutableListOf<List<String>>()
        for (i in letters[0].indices) {
            val remainingLetters = letters.toMutableList()
            val currentLetter = remainingLetters[0][i].toString()
            remainingLetters[0] = remainingLetters[0].removeRange(i, i + 1)

            val subPermutations =
                generatePermutations(remainingLetters.subList(1, remainingLetters.size))
            for (subPermutation in subPermutations) {
                if (currentLetter !in subPermutation.joinToString("")) {
                    permutations.add(listOf(currentLetter) + subPermutation)
                }
            }
        }

        return permutations
    }

    fun clearInput() {
        inputKeySequence.clear()
        inputPrompt.clear()
        updateInput()
    }

    fun clearInputWithoutRet() {
        inputKeySequence.clear()
        inputPrompt.clear()
    }
}

interface KeyboardOnChangeListener {
    fun onInput(inputSequence: String) {
    }

    fun onEnter(longClick: Boolean) {}
    fun onSettings(longClick: Boolean) {}
}

fun fullKeyToDoubleKey(pinyin: String): String {
    val pinyinDK = mutableListOf<Char>()
    pinyin.forEach {
        val dk = when (it) {
            'q' -> 'q'
            'w' -> 'q'
            'e' -> 'e'
            'r' -> 'e'
            't' -> 't'
            'y' -> 't'
            'u' -> 'u'
            'i' -> 'u'
            'o' -> 'o'
            'p' -> 'o'
            'a' -> 'a'
            's' -> 'a'
            'd' -> 'd'
            'f' -> 'd'
            'g' -> 'g'
            'h' -> 'g'
            'j' -> 'j'
            'k' -> 'j'
            'z' -> 'z'
            'x' -> 'z'
            'c' -> 'c'
            'v' -> 'c'
            'b' -> 'b'
            'n' -> 'b'
            else -> it
        }
        pinyinDK.add(dk)
    }
    return pinyinDK.joinToString("")
}

fun fullKeyToT9(pinyin: String): String {
    val pinyinT9 = mutableListOf<Char>()
    pinyin.forEach {
        val t9 = when (it) {
            'a', 'b', 'c' -> '2'
            'd', 'e', 'f' -> '3'
            'g', 'h', 'i' -> '4'
            'j', 'k', 'l' -> '5'
            'm', 'n', 'o' -> '6'
            'p', 'q', 'r', 's' -> '7'
            't', 'u', 'v' -> '8'
            'w', 'x', 'y', 'z' -> '9'
            else -> it
        }
        pinyinT9.add(t9)
    }
    return pinyinT9.joinToString("")
}
