#!/usr/bin/env python3
"""Exercise the production packaging task with a small Java-record APK, no NDK build."""
import argparse
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, required=True, help="New directory for logs and resulting DEX files")
parser.add_argument("--offline", action="store_true", help="Use cached Gradle dependencies only")
args = parser.parse_args()
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
root = Path(__file__).resolve().parents[2]
script = (root / "zygisk/build.gradle.kts").read_text()
root_build = (root / "build.gradle.kts").read_text()
versions = (root / "gradle/libs.versions.toml").read_text()
# Exercise the exact production task and registration, rather than a test reimplementation.
def section(source, beginning, end):
    try:
        start = source.index(beginning)
        return source[start:source.index(end, start)]
    except ValueError as error:
        raise SystemExit(f"Production Gradle extraction failed: expected {beginning!r} through {end!r}") from error


def match(pattern, source, label):
    result = re.search(pattern, source)
    if result is None:
        raise SystemExit(f"Cannot read {label} from production build configuration")
    return result.group(1)


implementation = section(script, "/** Package AGP", "\ndependencies {")
registration = section(script, "        // Keep prepared output separate", "        // Using Sync")


def setting(name):
    return match(r"val " + name + r' = ("[^"\n]+"|[0-9]+)', root_build, name).strip('"')

text = (root / "tests/recordtag/fixture/build.gradle.kts.in").read_text()
for key, value in {
    "PREPARE_FRAMEWORK_DEX": implementation,
    "REGISTER_FRAMEWORK_DEX": registration,
    "AGP": match(r'agp = "([^"]+)"', versions, "AGP version"),
    "COMPILE_SDK": setting("androidCompileSdkVersion"),
    "BUILD_TOOLS": setting("androidBuildToolsVersion"),
    "MIN_SDK": setting("androidMinSdkVersion"),
    "MULTIDEX": match(r"multiDexEnabled = (true|false)", script, "multidex setting"),
}.items():
    token = "@" + key + "@"
    if token not in text:
        raise SystemExit(f"Fixture template is missing {token}")
    text = text.replace(token, value)
remaining = re.findall(r"@[A-Z][A-Z0-9_]*@", text)
if remaining:
    raise SystemExit(f"Unresolved fixture placeholders: {remaining}")

# Keep the generated build and its inputs alongside logs for failure diagnosis.
fixture = output / "fixture"
fixture.mkdir()
for name in ("settings.gradle.kts", "fixture-rules.pro"):
    shutil.copyfile(root / "tests/recordtag/fixture" / name, fixture / name)
shutil.copytree(root / "tests/recordtag/fixture/src", fixture / "src")
(fixture / "build.gradle.kts").write_text(text)

def run(name, tasks, negative=False, expected="global synthetics are missing"):
    with (output / (name + ".log")).open("w") as log:
        result = subprocess.run(["bash", str(root / "gradlew"), "-p", str(fixture),
                                 *(["--offline"] if args.offline else []),
                                 "--console=plain", *tasks], stdout=log, stderr=subprocess.STDOUT)
    if negative:
        diagnostic = (output / (name + ".log")).read_text()
        if result.returncode == 0 or expected not in diagnostic:
            raise SystemExit("Negative regression failed; see " + str(output))
        if (fixture / "build/intermediates/framework_dex/debug/mergeDexDebug/classes.dex").exists():
            raise SystemExit("Failed packaging left a usable-looking output")
    elif result.returncode:
        raise SystemExit("Build failed; see " + str(output / (name + ".log")))

run("missing-support-rejected", ["prepareFrameworkDexDebug", "-PmonoDex"], negative=True)
run("debug", ["testFrameworkZipDebug", "verifyFrameworkClassesDebug"])
run("debug-repeat", ["testFrameworkZipDebug", "verifyFrameworkClassesDebug"])
if ":prepareFrameworkDexDebug UP-TO-DATE" not in (output / "debug-repeat.log").read_text():
    raise SystemExit("Repeated debug build did not reuse prepared DEX; see debug-repeat.log")
previous = (fixture / "build/intermediates/framework_dex/debug/mergeDexDebug/classes.dex").read_bytes()
probe = fixture / "src/main/java/org/matrix/vector/recordtest/AddedProbe.java"
probe.write_text("package org.matrix.vector.recordtest; public class AddedProbe { public static int value() { return 123; } }")
run("debug-changed", ["testFrameworkZipDebug", "verifyFrameworkClassesDebug"])
if previous == (fixture / "build/intermediates/framework_dex/debug/mergeDexDebug/classes.dex").read_bytes():
    raise SystemExit("Source change did not regenerate prepared DEX")
shutil.copyfile(fixture / "build/intermediates/framework_dex/debug/mergeDexDebug/classes.dex", output / "debug-vector.dex")
run("release", ["testFrameworkZipRelease", "verifyFrameworkClassesRelease"])
run("release-repeat", ["testFrameworkZipRelease", "verifyFrameworkClassesRelease"])
if ":prepareFrameworkDexRelease UP-TO-DATE" not in (output / "release-repeat.log").read_text():
    raise SystemExit("Repeated release build did not reuse prepared DEX")
previous = (fixture / "build/intermediates/framework_dex/release/minifyReleaseWithR8/classes.dex").read_bytes()
probe.write_text(probe.read_text().replace("return 123", "return 124"))
run("release-changed", ["testFrameworkZipRelease", "verifyFrameworkClassesRelease"])
if previous == (fixture / "build/intermediates/framework_dex/release/minifyReleaseWithR8/classes.dex").read_bytes():
    raise SystemExit("Release source change did not regenerate prepared DEX")
shutil.copyfile(fixture / "build/intermediates/framework_dex/release/minifyReleaseWithR8/classes.dex", output / "release-vector.dex")
for variant in ("debug", "release"):
    with zipfile.ZipFile(fixture / f"build/test-zips/framework-{variant}.zip") as archive:
        dex_entries = [name for name in archive.namelist() if name.endswith(".dex")]
        if dex_entries != ["framework/vector.dex"]:
            raise SystemExit(f"Unexpected or duplicate framework DEX entries: {dex_entries}")
        if archive.read(dex_entries[0]) != (output / f"{variant}-vector.dex").read_bytes():
            raise SystemExit("Packaged DEX differs from prepared single-DEX output")

# Simulate the documented NIO exception without adding a filesystem dependency.
# Only the first move is replaced; the production fallback still performs the move.
def force_non_atomic_move(source):
    # Match the operation's arguments, allowing Kotlin's normal whitespace/trailing comma styles.
    operation = r"""Files\s*\.\s*move\s*\(
        \s*staged\s*\.\s*toPath\s*\(\s*\)\s*,
        \s*output\s*\.\s*toPath\s*\(\s*\)\s*,
        \s*StandardCopyOption\s*\.\s*ATOMIC_MOVE\s*,
        \s*StandardCopyOption\s*\.\s*REPLACE_EXISTING\s*,?\s*\)"""
    pattern = re.compile(operation, re.VERBOSE)
    if len(list(pattern.finditer(source))) != 1:
        raise SystemExit("Cannot inject atomic-move failure: expected exactly one staged atomic move in production task")
    return pattern.sub('throw AtomicMoveNotSupportedException(staged.path, output.path, "fixture")', source)

forced_fallback = force_non_atomic_move(text)
(fixture / "build.gradle.kts").write_text(forced_fallback)
run("atomic-move-fallback", ["testFrameworkZipDebug", "verifyFrameworkClassesDebug", "--rerun-tasks"])
(fixture / "build.gradle.kts").write_text(text)
# Restore the Debug fixture snapshot after the Release source mutation above.
shutil.copyfile(fixture / "build/intermediates/framework_dex/debug/mergeDexDebug/classes.dex", output / "debug-vector.dex")
with zipfile.ZipFile(fixture / "build/test-zips/framework-debug.zip") as archive:
    if archive.read("framework/vector.dex") != (output / "debug-vector.dex").read_bytes():
        raise SystemExit("Atomic-move fallback packaged incorrect bytes")

archive_path = fixture / "build/test-zips/framework-debug.zip"
previous_archive = archive_path.read_bytes()
run("stale-output-rejected", ["testFrameworkZipDebug", "-PmonoDex"], negative=True)
if archive_path.read_bytes() != previous_archive:
    raise SystemExit("Failed DEX preparation unexpectedly replaced the module ZIP")
if list((fixture / "build/intermediates/framework_dex").rglob("*.pending")):
    raise SystemExit("DEX preparation left an unfinished staged output")

for failure, diagnostic in (("duplicate", "defined multiple times"), ("corrupt", "Compilation failed")):
    run(f"d8-{failure}-rejected", ["testFrameworkZipDebug", f"-PfailureApk={failure}"],
        negative=True, expected=diagnostic)
    if archive_path.read_bytes() != previous_archive:
        raise SystemExit(f"D8 {failure} failure replaced the previous ZIP")

# Two individually valid classes whose combined method references exceed one DEX.
for index in range(2):
    (probe.parent / f"Overflow{index}.java").write_text(
        f"package org.matrix.vector.recordtest; public class Overflow{index} {{\n" +
        "".join(f"public static int m{method}() {{ return {method}; }}\n" for method in range(33000)) + "}\n")
run("single-dex-overflow-rejected", ["testFrameworkZipDebug"], negative=True,
    expected="single-DEX loader's capacity")
if archive_path.read_bytes() != previous_archive:
    raise SystemExit("Single-DEX overflow replaced the previous ZIP")

print("PASS: class preservation, incremental builds, single-DEX ZIPs, missing support, corrupt DEX, duplicate classes and single-DEX overflow rejection")
print("Artifacts:", output)
