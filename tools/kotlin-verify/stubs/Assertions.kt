// Offline assertion shim. NOT PRODUCT CODE, NOT PART OF ANY MODULE.
//
// The harness rewrites `import kotlin.test.assertX` to `import runnersupport.assertX`
// so the pure-Kotlin unit tests can run without the kotlin-test artifact. The
// test bodies in the repo are unmodified idiomatic kotlin.test code; Gradle
// compiles and runs them against the real artifact.
package runnersupport

public class AssertionErrorWithMessage(message: String) : AssertionError(message)

public fun assertEquals(expected: Any?, actual: Any?, message: String? = null) {
    if (expected != actual) {
        throw AssertionErrorWithMessage(buildString {
            append("expected <$expected> but was <$actual>")
            if (message != null) append(" - ").append(message)
        })
    }
}

public fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float, message: String? = null) {
    if (kotlin.math.abs(expected - actual) > absoluteTolerance) {
        throw AssertionErrorWithMessage(buildString {
            append("expected <$expected>+/-$absoluteTolerance but was <$actual>")
            if (message != null) append(" - ").append(message)
        })
    }
}

public fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double, message: String? = null) {
    if (kotlin.math.abs(expected - actual) > absoluteTolerance) {
        throw AssertionErrorWithMessage(buildString {
            append("expected <$expected>+/-$absoluteTolerance but was <$actual>")
            if (message != null) append(" - ").append(message)
        })
    }
}

public fun assertNotEquals(illegal: Any?, actual: Any?, message: String? = null) {
    if (illegal == actual) {
        throw AssertionErrorWithMessage(buildString {
            append("expected not <$illegal> but was <$actual>")
            if (message != null) append(" - ").append(message)
        })
    }
}

public fun assertTrue(actual: Boolean, message: String? = null) {
    if (!actual) {
        throw AssertionErrorWithMessage(message ?: "expected true but was false")
    }
}

public fun assertFalse(actual: Boolean, message: String? = null) {
    if (actual) {
        throw AssertionErrorWithMessage(message ?: "expected false but was true")
    }
}

public fun assertSame(expected: Any?, actual: Any?, message: String? = null) {
    if (expected !== actual) {
        throw AssertionErrorWithMessage(message ?: "expected the same instance, but were different")
    }
}

public fun fail(message: String? = null): Nothing =
    throw AssertionErrorWithMessage(message ?: "fail() was called")

public inline fun <reified T : Throwable> assertFailsWith(
    message: String? = null,
    block: () -> Unit,
): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionErrorWithMessage(
            "expected ${T::class.simpleName} but caught ${e::class.simpleName}: $e" +
                (message?.let { " - $it" } ?: ""),
        )
    }
    throw AssertionErrorWithMessage(
        "expected ${T::class.simpleName} to be thrown, but nothing was" +
            (message?.let { " - $it" } ?: ""),
    )
}

public fun <T : Any> assertNotNull(actual: T?, message: String? = null): T {
    if (actual == null) {
        throw AssertionErrorWithMessage(message ?: "expected not null but was null")
    }
    return actual
}

public fun assertNull(actual: Any?, message: String? = null) {
    if (actual != null) {
        throw AssertionErrorWithMessage(message ?: "expected null but was <$actual>")
    }
}
