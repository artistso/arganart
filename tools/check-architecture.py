#!/usr/bin/env python3
"""Mechanical enforcement of ARCHITECTURE.md's non-negotiable rules.

Run by `./gradlew check` (see build.gradle.kts) and by CI. Fails the build on a
violation rather than leaving rule-keeping to review.

Checks
------
R1  No network for generation: :app must not declare android.permission.INTERNET,
    and no source file may open a socket / use an HTTP client.
R2  No text prompt field: no declaration that both (a) lives in a prompt/request
    type and (b) is a String named like text/prompt/description.
R3  UI never talks to a model: :feature:* and :app may not depend on :ai:runtime
    (concrete backends) and may not import a runtime implementation package.
R4  Ink runs with AI disabled: :core:* are pure JVM modules, depend on no Android
    or AI module, and import no android/androidx/Compose type.
R6  Hot paths allocate nothing: sampling entry points expose an allocation-free
    variant (sampleInto / a caller-owned destination), asserted by signature.

Plus module-graph checks: every `include(...)` in settings.gradle.kts must appear in
ALLOWED_MODULES, and every `implementation/project(...)` edge must be in ALLOWED_EDGES.

Exit codes: 0 = clean, 1 = violations.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------------------
# The locked module graph. Changing either table is an architectural change and
# must be accompanied by an ARCHITECTURE.md update in the same commit.
# ---------------------------------------------------------------------------

ALLOWED_MODULES = {
    ":app",
    ":core:ink",
    ":core:motion",
    ":core:spatial",
    ":core:diagnostics",
    ":ai:backend",
    ":ai:runtime",
    ":feature:canvas",
    ":feature:motion",
    ":data",
    ":design",
}

MODULE_DIRS = {
    ":app": "app",
    ":core:ink": "core/ink",
    ":core:motion": "core/motion",
    ":core:spatial": "core/spatial",
    ":core:diagnostics": "core/diagnostics",
    ":ai:backend": "ai/backend",
    ":ai:runtime": "ai/runtime",
    ":feature:canvas": "feature/canvas",
    ":feature:motion": "feature/motion",
    ":data": "data",
    ":design": "design",
}

# Allowed inbound dependencies, by direction of the arrow in ARCHITECTURE.md.
ALLOWED_EDGES = {
    # The shell wires everything; that is its only job.
    (":app", ":design"),
    (":app", ":feature:canvas"),
    (":app", ":feature:motion"),
    (":app", ":core:ink"),
    (":app", ":core:motion"),
    (":app", ":core:spatial"),
    (":app", ":ai:backend"),
    (":app", ":ai:runtime"),
    (":app", ":data"),
    (":app", ":core:diagnostics"),
    # Core is pure and depends on nothing in the product.
    (":ai:backend", ":core:motion"),
    (":ai:runtime", ":ai:backend"),
    (":feature:canvas", ":design"),
    (":feature:canvas", ":core:ink"),
    (":feature:motion", ":design"),
    (":feature:motion", ":core:motion"),
    (":design", ":core:ink"),
}

PURE_JVM_MODULES = {
    ":core:ink", ":core:motion", ":core:spatial", ":core:diagnostics",
    ":ai:backend", ":ai:runtime",
}
CORE_MODULES = {":core:ink", ":core:motion", ":core:spatial", ":core:diagnostics"}
UI_MODULES = {":feature:canvas", ":feature:motion", ":app"}

# :feature:* and :design see only the *interfaces*. :app is the composition root:
# it is the one place allowed to name a concrete backend, and only to construct
# the registry. This keeps rule 3 (UI never *talks* to a model) while still
# allowing the app to be wired.
COMPOSITION_ROOT = ":app"
COMPOSITION_ROOT_ALLOWED_IMPORT = "art.argan.ai.runtime.BackendRegistry"

AI_RUNTIME_IMPORT = re.compile(r"^import\s+art\.argan\.ai\.runtime\.")
ANDROID_IMPORT = re.compile(r"^import\s+(android|androidx|com\.android)\.")
FORBIDDEN_NETWORK = re.compile(
    r"(java\.net\.(Socket|URL|HttpURLConnection|ServerSocket|DatagramSocket)"
    r"|okhttp3|HttpClient|ktor\.client|retrofit2|WebSocket)"
)
# R2: a String field/param whose name suggests a natural-language prompt.
PROMPT_FIELD = re.compile(
    r"\b(?:val|var|fun)\s+(?:\w+\s*:\s*)?(prompt|promptText|description|userText|textPrompt)\b",
    re.IGNORECASE,
)
# Only declaration sites inside request-shaped files are considered, so a UI
# label called `description` is not a false positive.
PROMPT_SCOPE_HINT = re.compile(r"(Generation|Request|Spec|Intent)", re.IGNORECASE)


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.notes: list[str] = []

    def error(self, rule: str, where: str, message: str) -> None:
        entry = f"[{rule}] {where}: {message}"
        if entry not in self.errors:
            self.errors.append(entry)

    def ok(self, rule: str, message: str) -> None:
        self.notes.append(f"[{rule}] OK: {message}")

    def emit(self) -> int:
        for note in self.notes:
            print(note)
        if self.errors:
            print()
            for err in self.errors:
                print(f"FAIL {err}", file=sys.stderr)
            print(f"\n{len(self.errors)} architectural violation(s).", file=sys.stderr)
            return 1
        print("\nAll architectural rules satisfied.")
        return 0


def kotlin_files(module: str) -> list[Path]:
    root = REPO / MODULE_DIRS[module] / "src"
    if not root.exists():
        return []
    return sorted(root.rglob("*.kt"))


def check_module_graph(report: Report) -> None:
    settings = (REPO / "settings.gradle.kts").read_text(encoding="utf-8")
    included = set(re.findall(r'include\("([^"]+)"\)', settings))

    for mod in sorted(included - ALLOWED_MODULES):
        report.error("GRAPH", "settings.gradle.kts", f"unexpected module {mod}")
    for mod in sorted(ALLOWED_MODULES - included):
        report.error("GRAPH", "settings.gradle.kts", f"missing module {mod}")

    before = len(report.errors)
    for mod in sorted(ALLOWED_MODULES):
        build_file = REPO / MODULE_DIRS[mod] / "build.gradle.kts"
        if not build_file.exists():
            report.error("GRAPH", MODULE_DIRS[mod], "build.gradle.kts missing")
            continue
        text = build_file.read_text(encoding="utf-8")
        deps = set(re.findall(r'project\("([^"]+)"\)', text))
        for dep in sorted(deps):
            if (mod, dep) not in ALLOWED_EDGES:
                report.error(
                    "GRAPH", f"{MODULE_DIRS[mod]}/build.gradle.kts",
                    f"edge {mod} -> {dep} is not permitted by ARCHITECTURE.md",
                )
        if mod in PURE_JVM_MODULES and "com.android." in text:
            report.error(
                "R4", f"{MODULE_DIRS[mod]}/build.gradle.kts",
                "pure-JVM module must not apply an Android plugin",
            )
        if mod != COMPOSITION_ROOT and mod in UI_MODULES and 'project(":ai:runtime")' in text:
            report.error(
                "R3", f"{MODULE_DIRS[mod]}/build.gradle.kts",
                "UI module depends on concrete backends; use :ai:backend interfaces",
            )


def check_rule_1_network(report: Report) -> None:
    before = len(report.errors)
    manifest = REPO / "app" / "src" / "main" / "AndroidManifest.xml"
    if manifest.exists():
        text = manifest.read_text(encoding="utf-8")
        if "android.permission.INTERNET" in text:
            report.error(
                "R1", "app/src/main/AndroidManifest.xml",
                "INTERNET permission declared; rule 1 permits network only for an "
                "explicit, user-toggled model download in P7",
            )
    for mod in ALLOWED_MODULES:
        for path in kotlin_files(mod):
            for line in path.read_text(encoding="utf-8").splitlines():
                if line.strip().startswith(("//", "*", "/*")):
                    continue
                if FORBIDDEN_NETWORK.search(line):
                    report.error("R1", rel(path), f"network API in generation path: {line.strip()}")
    if len(report.errors) == before:
        report.ok("R1", "no INTERNET permission, no socket/HTTP client in any module")


def check_rule_2_no_prompt(report: Report) -> None:
    before = len(report.errors)
    for mod in ALLOWED_MODULES:
        for path in kotlin_files(mod):
            text = path.read_text(encoding="utf-8")
            if not PROMPT_SCOPE_HINT.search(path.name):
                continue
            for line in text.splitlines():
                stripped = line.strip()
                if stripped.startswith(("//", "*", "/*")):
                    continue
                match = PROMPT_FIELD.search(stripped)
                if match and "String" in stripped:
                    report.error(
                        "R2", rel(path),
                        f"text field on a request-shaped type: {stripped}",
                    )
    if len(report.errors) == before:
        report.ok("R2", "no text prompt field on any request/request-shaped type")


def check_rule_3_ui_boundary(report: Report) -> None:
    before = len(report.errors)
    for mod in sorted(UI_MODULES):
        for path in kotlin_files(mod):
            for line in path.read_text(encoding="utf-8").splitlines():
                stripped = line.strip()
                if not AI_RUNTIME_IMPORT.match(stripped):
                    continue
                if mod == COMPOSITION_ROOT:
                    allowed = f"import {COMPOSITION_ROOT_ALLOWED_IMPORT}"
                    if stripped == allowed or stripped == allowed + " as " + stripped.split(" as ")[-1]:
                        continue
                    if stripped.startswith(allowed + " "):
                        continue
                    report.error(
                        "R3", rel(path),
                        f"composition root may only import {COMPOSITION_ROOT_ALLOWED_IMPORT}, "
                        f"found: {stripped}",
                    )
                    continue
                report.error(
                    "R3", rel(path),
                    "UI imports a concrete backend; depend on :ai:backend interfaces",
                )
    if len(report.errors) == before:
        report.ok("R3", "features see interfaces only; app's only runtime import is the registry")


def check_rule_4_core_purity(report: Report) -> None:
    before = len(report.errors)
    for mod in sorted(CORE_MODULES):
        for path in kotlin_files(mod):
            for line in path.read_text(encoding="utf-8").splitlines():
                if ANDROID_IMPORT.match(line.strip()):
                    report.error(
                        "R4", rel(path),
                        f"{mod} must stay pure Kotlin: {line.strip()}",
                    )
    if len(report.errors) == before:
        report.ok("R4", "core modules import no Android/Compose types")


def check_rule_6_hot_paths(report: Report) -> None:
    """Sampling APIs must offer an allocation-free form.

    Heuristic but useful: any type with a `sample(`/`sampleAt(` method must also
    declare an `...Into(` destination-taking method.
    """
    before = len(report.errors)
    for mod in sorted(CORE_MODULES):
        for path in kotlin_files(mod):
            text = path.read_text(encoding="utf-8")
            if re.search(r"\bfun\s+sample\w*\(", text) and "Into(" not in text:
                report.error(
                    "R6", rel(path),
                    "sampling API has no allocation-free ...Into variant",
                )
    if len(report.errors) == before:
        report.ok("R6", "every sampling API exposes an allocation-free destination-taking form")


def check_phase_scope(report: Report) -> None:
    """P0 must contain no drawing/animation/generation feature code.

    Word-boundary matched against *declarations* only, so documentation that
    names a later phase (as this repo's READMEs and ARCHITECTURE.md do) is fine.
    """
    if not (REPO / "docs" / "phases" / "P00-scaffolding.md").exists():
        return
    later_phase_declarations = {
        "MotionEvent": "stylus input (P1)",
        "BrushParams": "brush model (P1)",
        "class Stroke": "stroke model (P1)",
        "MotionRibbon": "motion ribbon (P2)",
        "SpatialResolver": "spatial resolver (P3)",
        "GenerativeBrush": "generative brushes (P4)",
        "GenericnessMeter": "genericness meter (P5)",
        "StyleFingerprintVectors": "fingerprint computation (P6)",
        "OnnxSession": "model runtime (P7)",
    }
    found = False
    before = len(report.errors)
    for mod in ALLOWED_MODULES:
        for path in kotlin_files(mod):
            text = path.read_text(encoding="utf-8")
            for needle, what in later_phase_declarations.items():
                if re.search(rf"\b{re.escape(needle)}\b(?!.*(intentionally|deliberately|P\d))", text):
                    # allow mentions inside comments
                    for line in text.splitlines():
                        if needle in line and not line.strip().startswith(("//", "*", "/*")):
                            report.error("SCOPE", rel(path), f"{what} present in P0: {line.strip()}")
                            found = True
                            break
    if len(report.errors) == before:
        report.ok("SCOPE", "no code from any later phase is present")


def rel(path: Path) -> str:
    return str(path.relative_to(REPO))


def main() -> int:
    report = Report()
    check_module_graph(report)
    check_rule_1_network(report)
    check_rule_2_no_prompt(report)
    check_rule_3_ui_boundary(report)
    check_rule_4_core_purity(report)
    check_rule_6_hot_paths(report)
    check_phase_scope(report)
    return report.emit()


if __name__ == "__main__":
    sys.exit(main())
