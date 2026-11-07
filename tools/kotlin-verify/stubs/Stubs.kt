// Offline verification stubs. NOT PRODUCT CODE, NOT PART OF ANY MODULE.
//
// This sandbox cannot reach Maven Central or Google Maven, so the real
// kotlinx-coroutines and JUnit artifacts cannot be downloaded. To still be able
// to *compile and execute* the pure-Kotlin modules (:core:ink, :core:motion,
// :core:spatial, :ai:backend, :ai:runtime) and their unit tests, this harness
// supplies the two smallest possible stand-ins:
//
//   * kotlinx.coroutines.flow.Flow / flowOf - the only coroutines types the P0
//     contracts mention.
//   * org.junit.Test - a runtime-retained marker the runner discovers.
//
// They exist only on this harness classpath. Gradle builds resolve the real
// artifacts (see gradle/libs.versions.toml) and never see this directory.
package kotlinx.coroutines.flow

/** Stand-in for kotlinx.coroutines.flow.Flow. */
public interface Flow<out T>

/** Stand-in for kotlinx.coroutines.flow.flowOf. */
public fun <T> flowOf(vararg values: T): Flow<T> = object : Flow<T> {}
