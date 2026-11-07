// Offline test runner. NOT PRODUCT CODE.
//
// Discovers @org.junit.Test methods and invokes them, reporting a JUnit-style
// summary. Used only by tools/kotlin-verify/verify.sh so that the pure-Kotlin
// modules can be executed in a sandbox with no Maven access. Gradle CI runs the
// same tests through the real JUnit runner.
package runnersupport

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

public object Runner {
    @JvmStatic
    public fun main(args: Array<String>) {
        val testAnnotation = Class.forName("org.junit.Test")
        var passed = 0
        val failures = mutableListOf<String>()

        for (className in args) {
            val klass = Class.forName(className)
            val instance = klass.getDeclaredConstructor().newInstance()
            val methods = klass.declaredMethods
                .filter { m -> m.isAnnotationPresent(testAnnotation as Class<out Annotation>) }
                .filter { m -> m.parameterCount == 0 && !Modifier.isStatic(m.modifiers) }
                .sortedBy { it.name }
            println("${klass.simpleName}  (${methods.size} tests)")
            for (method in methods) {
                method.isAccessible = true
                try {
                    method.invoke(instance)
                    passed++
                    println("  PASS  ${method.name}")
                } catch (e: InvocationTargetException) {
                    val cause = e.targetException
                    failures += "${klass.simpleName}.${method.name}: $cause"
                    println("  FAIL  ${method.name}\n        ${cause}")
                }
            }
        }

        println()
        println("tests passed: $passed, failed: ${failures.size}")
        if (failures.isNotEmpty()) {
            failures.forEach { println("FAILURE $it") }
            kotlin.system.exitProcess(1)
        }
    }
}
