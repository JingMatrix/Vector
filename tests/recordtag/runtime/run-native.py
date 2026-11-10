#!/usr/bin/env python3
"""Build and run a disposable arm64 hook probe using the selected module's actual DEX.

Requires an already built :zygisk:zipDebug or :zygisk:zipRelease, Linux NDK,
ANDROID_HOME, JAVA_HOME and an explicitly selected root-ADB device. No module
installation, policy changes, reboot or persistent hook configuration is performed.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--variant', choices=('debug', 'release'), required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--timeout', type=int, default=30, help='Device probe timeout in seconds (default: 30)')
args = parser.parse_args()
if args.timeout < 1:
    parser.error('--timeout must be a positive number of seconds')
root = Path(__file__).resolve().parents[3]
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=False)
sdk = Path(os.environ['ANDROID_HOME'])
java = Path(os.environ['JAVA_HOME'])
config = (root / 'build.gradle.kts').read_text()
def setting(name):
    match = re.search(r'val ' + name + r' = ("[^"\n]+"|[0-9]+)', config)
    if not match:
        raise SystemExit(f'Cannot find {name} in root build configuration')
    return match.group(1).strip('"')

def run(command, timeout=180):
    with (out / 'commands.log').open('a') as log:
        try:
            result = subprocess.run([str(value) for value in command], stdout=log,
                                    stderr=subprocess.STDOUT, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            raise SystemExit(f'Command timed out after {timeout}s; see {out / "commands.log"}') from error
    if result.returncode:
        raise SystemExit(f'Command failed ({result.returncode}); see {out / "commands.log"}')

variant = args.variant
native_build = 'Debug' if variant == 'debug' else 'RelWithDebInfo'
builds = list((root / f'zygisk/build/{native_build}').glob('*/arm64-v8a/native/libnative.a'))
if len(builds) != 1:
    raise SystemExit(f'Build :zygisk:zip{variant.capitalize()} first; expected one native build, found {len(builds)}')
build = builds[0].parents[1]
archives = list(build.rglob('*.a'))
include_dirs = ['native/include', 'external/lsplant/lsplant/src/main/jni/include',
                'external/dobby/include', 'external/fmt/include']
compiler = sdk / f'ndk/{setting("androidCompileNdkVersion")}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android27-clang++'
run([compiler, '-shared', '-fPIC', '-std=c++23', '-static-libstdc++', '-DFMT_HEADER_ONLY',
     '-DVERSION_CODE=0', '-DVERSION_NAME="runtime-probe"',
     *(['-DNDEBUG'] if variant == 'release' else []),
     *[arg for directory in include_dirs for arg in ('-I', root / directory)],
     Path(__file__).with_name('native_hook_probe.cpp'), '-Wl,--start-group', *archives,
     '-Wl,--end-group', '-llog', '-ldl', '-lz', '-o', out / 'libprobe.so'])
classes = out / 'classes'
classes.mkdir()
dex = out / 'dex'
dex.mkdir()
run([java / 'bin/javac', '--release', '8', '-Xlint:-options', '-d', classes, Path(__file__).with_name('NativeHookProbe.java')])
run([sdk / f'build-tools/{setting("androidBuildToolsVersion")}/d8', '--min-api', '27',
     '--output', dex, *classes.glob('*.class')])
modules = list((root / 'zygisk/release').glob(f'*-{variant.capitalize()}.zip'))
if len(modules) != 1:
    raise SystemExit(f'Expected one {variant} module ZIP')
with zipfile.ZipFile(modules[0]) as archive:
    (out / 'framework.dex').write_bytes(archive.read('framework/vector.dex'))

names = ['org.matrix.vector.impl.hooks.VectorMethodInvoker', 'org.matrix.vector.impl.hooks.VectorHookRecord']
if variant == 'release':
    mapping = (root / 'zygisk/build/outputs/mapping/release/mapping.txt').read_text()
    for index, name in enumerate(names):
        match = re.search(r'^' + re.escape(name) + r' -> ([^:]+):$', mapping, re.M)
        if not match:
            raise SystemExit(f'Release mapping missing {name}')
        names[index] = match.group(1)
adb = [sdk / 'platform-tools/adb', '-s', args.serial]
# mktemp allocates a unique directory; the finally block removes only that directory.
try:
    remote = subprocess.check_output([*map(str, adb), 'shell',
        'mktemp -d /data/local/tmp/vector-recordtag.XXXXXX'], text=True, timeout=15).strip()
except subprocess.TimeoutExpired as error:
    raise SystemExit('Timed out creating temporary device directory') from error
if not re.fullmatch(r'/data/local/tmp/vector-recordtag\.[A-Za-z0-9]+', remote):
    raise SystemExit(f'Unexpected temporary device directory: {remote!r}')
try:
    for source, target in ((out / 'framework.dex', 'framework.dex'), (dex / 'classes.dex', 'probe.dex'), (out / 'libprobe.so', 'libprobe.so')):
        run([*adb, 'push', source, remote + '/' + target])
    run([*adb, 'shell', f'timeout -s KILL {args.timeout} env CLASSPATH={remote}/probe.dex app_process /system/bin NativeHookProbe '
         f'{remote}/framework.dex {remote}/libprobe.so {names[0]} {names[1]}'], timeout=args.timeout + 15)
finally:
    failed = sys.exc_info()[0] is not None
    try:
        run([*adb, 'shell', 'rm -rf ' + remote], timeout=15)
    except SystemExit as error:
        if not failed:
            raise
        print(f'Cleanup failed for {remote}: {error}', file=sys.stderr)
print(f'PASS: {variant} native hook/original-call probe; evidence: {out}')
