#!/usr/bin/env python3
"""Exercise the production packaging task with a small Java-record APK, no NDK build."""
import argparse
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
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
start = script.index("/** Package AGP")
implementation = script[start:script.index("\ndependencies {", start)]
start = script.index("        // Keep prepared output separate")
registration = script[start:script.index("        // Using Sync", start)]

def setting(name):
    return re.search(r"val " + name + r' = ("[^"\n]+"|[0-9]+)', root_build).group(1).strip('"')

text = (root / "tests/recordtag/fixture/build.gradle.kts.in").read_text()
for key, value in {
    "PREPARE_FRAMEWORK_DEX": implementation,
    "REGISTER_FRAMEWORK_DEX": registration,
    "AGP": re.search(r'agp = "([^"]+)"', versions).group(1),
    "COMPILE_SDK": setting("androidCompileSdkVersion"),
    "BUILD_TOOLS": setting("androidBuildToolsVersion"),
    "MIN_SDK": setting("androidMinSdkVersion"),
    "MULTIDEX": re.search(r"multiDexEnabled = (true|false)", script).group(1),
}.items():
    text = text.replace("@" + key + "@", value)

with tempfile.TemporaryDirectory(prefix="vector-recordtag-") as tmp:
    fixture = Path(tmp)
    for name in ("settings.gradle.kts", "fixture-rules.pro"):
        shutil.copyfile(root / "tests/recordtag/fixture" / name, fixture / name)
    shutil.copytree(root / "tests/recordtag/fixture/src", fixture / "src")
    (fixture / "build.gradle.kts").write_text(text)

    def run(name, tasks, negative=False):
        with (output / (name + ".log")).open("w") as log:
            result = subprocess.run(["bash", str(root / "gradlew"), "-p", str(fixture),
                                     *(["--offline"] if args.offline else []),
                                     "--console=plain", *tasks], stdout=log, stderr=subprocess.STDOUT)
        if negative:
            diagnostic = (output / (name + ".log")).read_text()
            if result.returncode == 0 or "global synthetics are missing" not in diagnostic:
                raise SystemExit("Negative regression failed; see " + str(output))
            if (fixture / "build/intermediates/framework_dex/debug/mergeDexDebug/classes.dex").exists():
                raise SystemExit("Failed packaging left a usable-looking output")
        elif result.returncode:
            raise SystemExit("Build failed; see " + str(output / (name + ".log")))

    run("missing-support-rejected", ["prepareFrameworkDexDebug", "-PmonoDex"], negative=True)
    run("debug", ["testFrameworkZipDebug"])
    shutil.copyfile(fixture / "build/intermediates/framework_dex/debug/mergeDexDebug/classes.dex", output / "debug-vector.dex")
    run("release", ["testFrameworkZipRelease"])
    shutil.copyfile(fixture / "build/intermediates/framework_dex/release/minifyReleaseWithR8/classes.dex", output / "release-vector.dex")
    for variant in ("debug", "release"):
        with zipfile.ZipFile(fixture / f"build/test-zips/framework-{variant}.zip") as archive:
            dex_entries = [name for name in archive.namelist() if name.endswith(".dex")]
            if dex_entries != ["framework/vector.dex"]:
                raise SystemExit(f"Unexpected or duplicate framework DEX entries: {dex_entries}")
            if archive.read(dex_entries[0]) != (output / f"{variant}-vector.dex").read_bytes():
                raise SystemExit("Packaged DEX differs from prepared single-DEX output")

print("PASS: missing record support rejected; debug/release each package exactly one framework/vector.dex")
print("Artifacts:", output)
