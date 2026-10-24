#!/usr/bin/env python3
"""Run compiled production reconnection classes with deterministic Android transport doubles.

Build :daemon:compileReleaseKotlin and :zygisk:compileReleaseKotlin first.
This checks lifecycle logic, not Binder kernel transport or Zygisk injection on a device.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--gradle-cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/reattach-tests')
    args = parser.parse_args()
    classes = args.output.resolve() / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    paths = [classes]
    for module in ['daemon', 'zygisk', 'xposed', 'services/daemon-service', 'services/manager-service']:
        paths += [ROOT / module / 'build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes',
                  ROOT / module / 'build/intermediates/javac/release/compileReleaseJavaWithJavac/classes']
    stdlib = args.gradle_cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'
    paths.append(sorted(stdlib.glob('*/*/*.jar'))[-1])
    paths.append(sorted((args.sdk / 'platforms').glob('android-*/android.jar'))[-1])
    paths += list((ROOT / 'hiddenapi/stubs/build/libs').glob('*.jar'))
    cp = os.pathsep.join(map(str, paths))
    sources = sorted((ROOT / 'tests/reattach/stubs').rglob('*.java'))
    sources.append(ROOT / 'tests/reattach/ReattachTest.java')
    subprocess.run([str(args.jdk / 'bin/javac'), '-cp', cp, '-d', str(classes),
                    *map(str, sources)], check=True)
    for api in [26, 28, 30, 31, 33, 34, 35, 36, 37]:
        subprocess.run([str(args.jdk / 'bin/java'), '-ea', '-cp', cp, 'ReattachTest', str(api)],
                       check=True)
    native = (ROOT / 'zygisk/src/main/cpp/ipc_bridge.cpp').read_text()
    start = native.index('lsplant::ScopedLocalRef<jobject> IPCBridge::RequestSystemServerBinder(')
    end = native.index('std::tuple<int, size_t> IPCBridge::FetchFrameworkDex(', start)
    functions = native[start:end].replace('std::this_thread::sleep_for', 'fixture::sleep_for')
    fixture = (ROOT / 'tests/reattach/handshake_fixture.cpp').read_text()
    generated = args.output / 'handshake.cpp'
    generated.write_text(fixture.replace('// PRODUCTION_FUNCTIONS', functions))
    executable = args.output / 'handshake'
    subprocess.run(['c++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter',
                    str(generated), '-o', str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
    print('Native source SHA-256:', hashlib.sha256(native.encode()).hexdigest())
    print('LIMIT: API branches use Android doubles, not a device compatibility matrix.')


if __name__ == '__main__':
    main()
