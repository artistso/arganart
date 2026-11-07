package art.argan.app

import android.app.Application
import art.argan.ai.runtime.BackendRegistry

/**
 * Application shell. Owns the single [BackendRegistry] instance.
 *
 * This is the app's only dependency-injection wiring for P0. A framework DI
 * container is explicitly rejected for now (see DECISIONS.md): there is exactly
 * one graph, it is small, and a hand-written registry is testable without
 * annotation processing.
 */
public class ArganApplication : Application() {

    /** Built lazily so a debug launch does not construct backends it will not use. */
    public val backends: BackendRegistry by lazy { BackendRegistry.createDefault() }
}
