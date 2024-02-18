# Keyboard Behaviours

## IME Stacktraces

When entering a single lowercase letter `h`:

GBoard stacktrace:

```
replaceTextInternal:1026, BaseInputConnection (android.view.inputmethod)
replaceText:962, BaseInputConnection (android.view.inputmethod)
commitText:241, BaseInputConnection (android.view.inputmethod)
commitText:222, EditableInputConnection (com.android.internal.inputmethod)
commitText:207, InputConnectionWrapper (android.view.inputmethod)
lambda$commitText$17:649, RemoteInputConnectionImpl (android.view.inputmethod)
$r8$lambda$jG8e73WUDH3moNu5UWHEmrz2eOk:-1, RemoteInputConnectionImpl (android.view.inputmethod)
run:-1, RemoteInputConnectionImpl$$ExternalSyntheticLambda18 (android.view.inputmethod)
...
```

SwiftKey:

```
replaceTextInternal:1026, BaseInputConnection (android.view.inputmethod)
replaceText:962, BaseInputConnection (android.view.inputmethod)
setComposingText:743, BaseInputConnection (android.view.inputmethod)
setComposingText:161, InputConnectionWrapper (android.view.inputmethod)
lambda$setComposingText$26:805, RemoteInputConnectionImpl (android.view.inputmethod)
$r8$lambda$6QnId2PFbkbfVBe1GTvnLoJx1sU:-1, RemoteInputConnectionImpl (android.view.inputmethod)
run:-1, RemoteInputConnectionImpl$$ExternalSyntheticLambda10 (android.view.inputmethod)
...
```

Google Voice Input:

```
replaceTextInternal:1026, BaseInputConnection (android.view.inputmethod)
replaceText:962, BaseInputConnection (android.view.inputmethod)
setComposingText:743, BaseInputConnection (android.view.inputmethod)
setComposingText:161, InputConnectionWrapper (android.view.inputmethod)
lambda$setComposingText$26:805, RemoteInputConnectionImpl (android.view.inputmethod)
$r8$lambda$6QnId2PFbkbfVBe1GTvnLoJx1sU:-1, RemoteInputConnectionImpl (android.view.inputmethod)
run:-1, RemoteInputConnectionImpl$$ExternalSyntheticLambda10 (android.view.inputmethod)
...
```

Physical Keyboard:

```
replaceTextInternal:1026, BaseInputConnection (android.view.inputmethod)
replaceText:962, BaseInputConnection (android.view.inputmethod)
commitText:241, BaseInputConnection (android.view.inputmethod)
commitText:222, EditableInputConnection (com.android.internal.inputmethod)
commitText:207, InputConnectionWrapper (android.view.inputmethod)
lambda$commitText$17:649, RemoteInputConnectionImpl (android.view.inputmethod)
$r8$lambda$jG8e73WUDH3moNu5UWHEmrz2eOk:-1, RemoteInputConnectionImpl (android.view.inputmethod)
run:-1, RemoteInputConnectionImpl$$ExternalSyntheticLambda18 (android.view.inputmethod)
...
```

## The Edit

The actual text replacement that changes the text shown in your `EditText` is done on [line 1026
of `BaseInputConnection`](BaseInputConnection.java:1026).

```java
// content = real content of the EditText
// a = starting point of replacement
// b = end point of replacement
// text = new text, which could be longer than a..b!
content.replace(a, b, text);
```

### Composing Spans

Some IMEs will use a feature known as the "Composing span" to keep track of the text that the user
is currently typing. Usually this is the current word that is being typed, so that the IME can apply
text corrections to it (for example). The IME controls the composing span through calls
to `setComposingText()` in `BaseInputConnection`.

You can visualize the current composing span using a function like this:

```kotlin
/**
 *  example output:
 *    hello
 *    ^~~~~^
 */
fun formatComposingSpan(span: Spannable): String {
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
```

#### The Normal Case

Consider the following composing state, where the `^` denote the start and end of the composing
span:

```
0|1|2|3|4|5|6|7
h|e|l|l|o| |w|
            ^ ^
```

when the user types an 'o' into their keyboard, the `content.replace()` call will be made with the
following parameters:

```kotlin
// content = "hello w"
content.replace(a = 6, b = 7, text = "wo")
```

resulting in this subsequent state:

```
0|1|2|3|4|5|6|7|8
h|e|l|l|o| |w|o
            ^ ~ ^
```

#### The Redwood Special Case

With our attempted fix, we use `Editable.replace()` to overwrite the content of the text, e.g. to
uppercase it in response to guest-driven state. This has the side effect of clobbering the composing
span in our `EditText`'s editable.

In the example below, we have just called `Editable.replace()` and the composing span is gone.
We also updated the current selection though, which is denoted by `*`.

```
0|1|2|3|4|5|6|7
H|E|L|L|O| |W|
              *
```

In this case, when the user then types an 'o' into their keyboard, the `content.replace()` call will
be made with these parameters instead:

```kotlin
// content = "HELLO W"
content.replace(a = 7, b = 7, text = "wo")
```

Despite the composing span being gone from this state, the IME still "remembers" what text was in
the active span and will still try to replace the text accordingly here.

Note that `a = b = 7`, and this results in this bad state:

```
0|1|2|3|4|5|6|7|8|9
H|E|L|L|O| |W|w|o|
              ^ ~ ^
```

up until the point where our guest code uppercases everything and clobbers the composing span again:

```
0|1|2|3|4|5|6|7|8|9
H|E|L|L|O| |W|W|O|
                  *
```

#### Addressing the Problem (Wrongly)

We _could_ use `InputConnection.finishComposingText()` every time we call `Editable.replace()` where
we would pretend to be the IME and commit the text every time we make a state update. This would
effectively emulate the behaviour of GBoard and physical keyboards where characters are committed
one at a time.

This poses some problems with keyboards that enter multiple words at a time, like the default Google
voice keyboard. You can say the phrase "hello world" and the voice IME will create a single
composing span for the entire phrase, but will enter each word individually. With our state updating
logic, we would end up with a final input of "HELLOHELLO WORLD" as we would commit the text
immediately after the first word is inputted.

#### Addressing the Problem (Naively)

After calling `Editable.replace()`, we can restore the composing span
using `InputConnection.setComposingRegion(start, end)`.

This still presents two problems however:

1. An IME will still used its cached text when replacing text, so any transformations will be
   momentarily undone for each new word every time the user types.
2. This relies on the indices of the composing region being the same before and after the
   programmatic transformation. Inserting/deleting characters require a more complex implementation.