package ca.derekellis.keyboard

import android.os.Bundle
import android.text.Spannable
import android.text.TextUtils
import android.util.Log
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity() {
  private val editText by lazy { findViewById<EditText>(R.id.editTextText) }

  private val flow = MutableSharedFlow<TextFieldState>(extraBufferCapacity = Int.MAX_VALUE)
  private var state = TextFieldState()
  private var isUpdating = false

  private fun uppercase(state: TextFieldState): TextFieldState =
    state.copy(text = state.text.uppercase())

  private fun insertDashes(state: TextFieldState): TextFieldState {
    TODO()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(R.layout.activity_main)
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
      val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
      insets
    }

    lifecycleScope.launch {
      flow
        .onEach { delay(50.milliseconds) }
        .map {
          it.copy(text = it.text.uppercase())
        }.collect {
          stateChanged(it)
        }
    }

    editText.addTextChangedListener(onTextChanged = { _, _, _, _ ->
      textChanged()
    }, afterTextChanged = { editable ->
//      editable?.getSpans<android.view.inputmethod.ComposingText>()
//      TextUtils.dumpSpans(editable, {
//        Log.d("Text", it)
//      }, "")
    })
  }

  private fun textChanged() {
    if (isUpdating) return

    val newState = state.userEdit(
      text = editText.text?.toString().orEmpty(),
      selectionStart = editText.selectionStart,
      selectionEnd = editText.selectionEnd,
    )
    if (!state.contentEquals(newState)) {
      this.state = newState
      flow.tryEmit(newState)
    }
  }

  private fun stateChanged(state: TextFieldState) {
    if (state.userEditCount < this.state.userEditCount) return

    check(!isUpdating)
    try {
      isUpdating = true
      this.state = state
      val inputConnection = editText.onCreateInputConnection(EditorInfo()) as InputConnectionWrapper

      Log.d("InputConnection", inputConnection::class.java.name)
      editText.editableText?.let {
        val composingStart = BaseInputConnection.getComposingSpanStart(it)
        val composingEnd = BaseInputConnection.getComposingSpanEnd(it)
        Log.d("Span Before!", formatComposingSpan(it))

        // Do the replacement!
        it.replace(0, it.length, state.text)

        if (composingStart != -1 && composingEnd != -1) {
          inputConnection.setComposingRegion(composingStart, composingEnd)
          inputConnection.setComposingText(
            it.subSequence(composingStart, composingEnd),
            composingEnd,
          )
        }
        Log.d("Span After!", formatComposingSpan(it))
      }
      editText.setSelection(state.selectionStart, state.selectionEnd)
    } finally {
      isUpdating = false
    }
  }

  private fun formatComposingSpan(span: Spannable): String {
    val composingStart = BaseInputConnection.getComposingSpanStart(span)
    val composingEnd = BaseInputConnection.getComposingSpanEnd(span)
    val composingRange = composingStart..composingEnd

    return buildString {
      appendLine(span.toString())
      for (i in 0..span.length) {
        when (i) {
          !in composingRange -> append(" ")
          composingStart, composingEnd -> append("^")
          in composingRange -> append("~")
        }
      }
      append("\n")
    }
  }
}