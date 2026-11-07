#!/usr/bin/env bash
# Offline verification of the pure-Kotlin modules.
#
# WHY THIS EXISTS
# ---------------
# This repo's CI (below) is the authority: `./gradlew assembleDebug lint test`.
# But `:core:ink`, `:core:motion`, `:core:spatial`, `:ai:backend` and `:ai:runtime`
# are plain JVM Kotlin with no Android dependency, so they can be compiled and
# their unit tests executed anywhere a Kotlin compiler and a JDK exist - with no
# Android SDK and no Gradle. This script is how P0's "the pure core compiles and
# its tests pass" claim is backed with evidence in an environment where Android
# tooling is unavailable.
#
# It uses a JUnit-compatible shim (tools/kotlin-verify/stubs) for the two
# third-party types the tests touch. Gradle uses the real artifacts.
#
# Usage: tools/kotlin-verify/verify.sh [--verbose]
# Requires: KOTLINC (path to a kotlinc script) and JAVA_HOME, or an environment
# where `kotlinc` and `java` are on PATH.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

KOTLINC="${KOTLINC:-kotlinc}"
KOTLIN_LIB="$(cd "$(dirname "$(command -v "$KOTLINC")")/../lib" && pwd)"
out_dir="tools/.kotlin-verify"
rm -rf "$out_dir"
mkdir -p "$out_dir/classes" "$out_dir/test-classes"

src_dirs=(
  "core/ink/src/main/kotlin"
  "core/motion/src/main/kotlin"
  "core/spatial/src/main/kotlin"
  "core/diagnostics/src/main/kotlin"
  "ai/backend/src/main/kotlin"
  "ai/runtime/src/main/kotlin"
)

test_dirs=(
  "core/ink/src/test/kotlin"
  "core/motion/src/test/kotlin"
  "core/spatial/src/test/kotlin"
  "core/diagnostics/src/test/kotlin"
  "ai/backend/src/test/kotlin"
  "ai/runtime/src/test/kotlin"
)

echo "== compile main sources =="
"$KOTLINC" \
  -no-stdlib -cp "$KOTLIN_LIB/kotlin-stdlib.jar" \
  -d "$out_dir/classes" \
  tools/kotlin-verify/stubs/Stubs.kt \
  "${src_dirs[@]}" 2>&1 | sed '/^warning:/d'

echo "== stage tests (import rewrite only) =="
# The only edit made to test sources is the assertion import; every test body is
# compiled and executed exactly as committed. That is what makes the harness
# trustworthy as evidence: it cannot hide a wrong expectation.
mkdir -p "$out_dir/staged"
for dir in "${test_dirs[@]}"; do
  dst="$out_dir/staged/$dir"
  mkdir -p "$dst"
  find "$dir" -name '*.kt' -print0 | while IFS= read -r -d '' f; do
    sed -e 's/^import kotlin\.test\.Test$/import org.junit.Test/' \
        -e 's/^import kotlin\.test\./import runnersupport./' \
        "$f" > "$dst/$(basename "$f")"
  done
done

echo "== compile tests =="
"$KOTLINC" \
  -no-stdlib -cp "$KOTLIN_LIB/kotlin-stdlib.jar:$out_dir/classes" \
  -d "$out_dir/test-classes" \
  tools/kotlin-verify/stubs/Stubs.kt \
  tools/kotlin-verify/stubs/JunitTest.kt \
  tools/kotlin-verify/stubs/Assertions.kt \
  "$out_dir/staged" 2>&1 | sed '/^warning:/d'

echo "== compile runner =="
"$KOTLINC" \
  -no-stdlib -cp "$KOTLIN_LIB/kotlin-stdlib.jar" \
  -d "$out_dir/runner-classes" \
  tools/kotlin-verify/stubs/Runner.kt 2>&1 | sed '/^warning:/d'

echo "== run tests =="
mapfile -t test_class_names < <(
  find "$out_dir/test-classes" -name '*Test.class' -not -path '*/org/junit/*' \
    | sed "s|$out_dir/test-classes/||; s|\.class$||; s|/|.|g" | sort
)
echo "test classes: ${#test_class_names[@]}"

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$java_bin" \
  -cp "$KOTLIN_LIB/kotlin-stdlib.jar:$out_dir/classes:$out_dir/test-classes:$out_dir/runner-classes" \
  runnersupport.Runner "${test_class_names[@]}"
