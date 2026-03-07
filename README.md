# MiniKotlin to Java CPS Compiler

Compiler from a subset of Kotlin ("MiniKotlin") to Java in continuation-passing style (CPS).

This project was implemented as an internship assignment. The compiler translates MiniKotlin programs into Java code where non-`main` functions return their results through explicit continuations.

## Overview

The generated Java code follows CPS:

- every non-`main` function gets an extra `Continuation<T>` parameter,
- function results are passed to `__continuation.accept(...)`,
- function calls inside expressions are lowered into nested continuations.

The implementation focuses on correctness for the supported MiniKotlin subset and on preserving operator semantics as closely as possible.

## Supported Features

The compiler supports:

- function declarations,
- parameters and return types,
- `main` as the Java entry point,
- local variable declarations and assignments,
- `if` / `else`,
- `while`,
- `return`,
- literals: `Int`, `String`, `Boolean`,
- arithmetic operators: `+`, `-`, `*`, `/`, `%`,
- comparisons: `<`, `<=`, `>`, `>=`,
- equality: `==`, `!=`,
- logical operators: `!`, `&&`, `||`,
- nested function calls,
- function calls inside expressions,
- function calls inside `if` and `while` conditions.

## Implementation Notes

A few non-trivial cases handled by the compiler:

- **CPS transformation** for function calls inside expressions,
- **short-circuit semantics** for `&&` and `||`,
- **Kotlin-like equality** using `java.util.Objects.equals(...)`,
- **implicit `Unit` return** for functions that fall through without explicit `return`,
- **function calls in `while` conditions** lowered into recursive `Runnable` loops,
- **Java keyword sanitization** for identifiers such as `double` or `class`.

Local mutable variables are represented as single-element arrays in generated Java so they can be safely captured and updated inside lambdas.

## Example

### MiniKotlin input

```kotlin
fun factorial(n: Int): Int {
    if (n <= 1) {
        return 1
    } else {
        return n * factorial(n - 1)
    }
}

fun main(): Unit {
    var result: Int = factorial(5)
    println(result)

    var a: Int = 10 + 5
    var b: Boolean = a > 10
    println(a)
}
````

### Generated Java output

```java
public static void factorial(Integer n, Continuation<Integer> __continuation) {
    if ((n <= 1)) {
        __continuation.accept(1);
        return;
    } else {
        factorial((n - 1), (arg0) -> {
            __continuation.accept((n * arg0));
            return;
        });
    }
}

public static void main(String[] args) {
    factorial(5, (arg0) -> {
        final Integer[] result = new Integer[]{arg0};
        Prelude.println(result[0], (arg1) -> {
            final Integer[] a = new Integer[]{(10 + 5)};
            final Boolean[] b = new Boolean[]{(a[0] > 10)};
            Prelude.println(a[0], (arg2) -> {
            });
        });
    });
}
```

## Project Structure

* `samples/` - example MiniKotlin programs
* `src/main/antlr/MiniKotlin.g4` - grammar definition
* `src/main/kotlin/.../MiniKotlinCompiler.kt` - compiler implementation
* `src/test/` - tests
* `stdlib/` - helper runtime classes such as `Prelude`

## Building and Running

```bash
./gradlew build
./gradlew run
./gradlew run --args="samples/example.mini"
./gradlew test
```

## Tests

The test suite covers end-to-end compilation and execution of generated Java code, including cases such as:

* recursion,
* nested function calls,
* variable assignment after function call,
* lazy boolean operators,
* `Unit` functions without explicit `return`,
* equality on strings,
* `while` loops with function calls in conditions,
* Java keyword identifiers.

## Limitations

This compiler targets only the provided MiniKotlin subset.

The implementation is intentionally lightweight:

* terminality analysis is conservative,
* identifier sanitization handles Java keywords but not every possible naming collision,
* the focus is correctness, not optimization of generated Java code.

