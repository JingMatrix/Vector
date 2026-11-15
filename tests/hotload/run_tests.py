#!/usr/bin/env python3
"""Host regression test using compiled Vector classes and verbatim native callback functions.

Build :xposed:compileReleaseKotlin first. This is not an ART/LSPlant installation test,
a measurement of DEX unmapping, or an Android detector comparison. No device is used.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--gradle-cache', type=Path, default=Path('/tmp/vector-gradle'))
    parser.add_argument('--output', type=Path, default=ROOT / 'build/hotload/cleanup-tests')
    parser.add_argument('--baseline', type=Path, help='Optional preserved pre-change sources and classes')
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    records = []

    def run(name, command, expect_failure=False):
        result = subprocess.run([str(x) for x in command], cwd=ROOT,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        (out / f'{name}.log').write_text(result.stdout)
        ok = result.returncode != 0 if expect_failure else result.returncode == 0
        records.append({'name': name, 'command': [str(x) for x in command], 'exit': result.returncode,
                        'expected_failure': expect_failure, 'pass': ok})
        (out / 'results.json').write_text(json.dumps(records, indent=2) + '\n')
        print(('PASS ' if ok else 'FAIL ') + name, flush=True)
        if name.startswith('run-') or not ok:
            print(result.stdout, end='', flush=True)
        if not ok:
            raise RuntimeError(f'{name} exited {result.returncode}')
        return result.stdout

    kotlin_version = re.search(r'^kotlin = "([^"]+)"', (ROOT / 'gradle/libs.versions.toml').read_text(), re.M).group(1)
    stdlib = next((args.gradle_cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib' / kotlin_version).rglob('*.jar'))
    kotlin = ROOT / 'xposed/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes'
    java = ROOT / 'xposed/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
    services = ROOT / 'services/daemon-service/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
    android = args.sdk / 'platforms/android-37.0/android.jar'
    classes = out / 'classes'
    classes.mkdir(exist_ok=True)
    cp = os.pathsep.join(map(str, [classes, kotlin, java, services, stdlib, android]))
    run('compile-test', [args.jdk / 'bin/javac', '-cp', cp, '-d', classes,
                         ROOT / 'tests/hotload/HookCleanupTest.java',
                         ROOT / 'tests/hotload/ResourceCleanupTest.java',
                         ROOT / 'tests/hotload/stubs/android/os/SharedMemory.java'])
    run('run-resource-cleanup', [args.jdk / 'bin/java', '-ea', '-cp', cp, 'ResourceCleanupTest'])

    monitor_file = ROOT / 'external/lsplant/lsplant/src/main/jni/include/utils/jni_helper.hpp'
    monitor_text = monitor_file.read_text()
    start = monitor_text.index('class JNIMonitor {')
    monitor = monitor_text[start:monitor_text.index('\n};', start) + 3]
    fixture = (ROOT / 'tests/hotload/cleanup_fixture.cpp').read_text()
    phmap = ROOT / 'external/lsplant/lsplant/src/main/jni/external/dex_builder/external/parallel_hashmap'
    sources = {}
    variants = [('current', ROOT / 'native/src/jni/hook_bridge.cpp', kotlin)]
    if args.baseline:
        variants.insert(0, ('baseline', args.baseline / 'baseline/native/src/jni/hook_bridge.cpp',
                           args.baseline / 'baseline-classes'))
    for name, native, bytecode in variants:
        code = native.read_text()
        registry_start = code.index('struct HookItem {')
        registry = code[registry_start:code.index('// Cached JNI method', registry_start)]
        operations = []
        for result_type, function in [('jboolean', 'unhookMethod'), ('jboolean', 'replaceCallback'),
                                      ('jobjectArray', 'callbackSnapshot')]:
            start = code.index(f'VECTOR_DEF_NATIVE_METHOD({result_type}, HookBridge, {function},')
            operations.append(code[start:code.index('\n}', start) + 2])
        generated = fixture.replace('// INSERT_PRODUCTION_MONITOR', monitor)
        generated = generated.replace('// INSERT_PRODUCTION_REGISTRY', registry)
        generated = generated.replace('// INSERT_PRODUCTION_OPERATIONS', '\n\n'.join(operations))
        cpp = out / f'{name}.cpp'
        cpp.write_text(generated)
        lib = out / f'lib{name}.so'
        run('compile-native-' + name, ['g++', '-std=c++20', '-O2', '-fPIC', '-shared', '-pthread',
            '-Wall', '-Wextra', '-Werror', '-I' + str(args.jdk / 'include'),
            '-I' + str(args.jdk / 'include/linux'), '-I' + str(phmap), cpp, '-o', lib])
        runtime_cp = os.pathsep.join(map(str, [classes, bytecode, java, services, stdlib, android]))
        output = run('run-' + name, [args.jdk / 'bin/java', '-Xint', '-Xmx96m', '-XX:+UseSerialGC',
            '-cp', runtime_cp, 'HookCleanupTest', lib], expect_failure=name == 'baseline')
        if name == 'baseline':
            expected = {'empty-snapshot-allocations', 'unhook-releases-module-loader',
                        'unhook-releases-framework-callback',
                        'replacement-preserves-in-flight-and-releases-retired', 'retained-hotload-parameters'}
            failed = {line.split(':', 1)[0][5:] for line in output.splitlines() if line.startswith('FAIL ')}
            if failed != expected:
                raise AssertionError(f'Baseline failures differ from diagnosed defects: {failed}')
        sources[name] = {'native_sha256': hashlib.sha256(native.read_bytes()).hexdigest(),
                         'generated_fixture_sha256': hashlib.sha256(cpp.read_bytes()).hexdigest(),
                         'classes': {str(p.relative_to(bytecode)): hashlib.sha256(p.read_bytes()).hexdigest()
                                     for p in sorted(bytecode.rglob('*.class'))}}
    (out / 'sources.json').write_text(json.dumps(sources, indent=2) + '\n')
    print('LIMIT: host JVM and native callback operations; no ART hooks, Android apps or DEX reclamation tested.')


if __name__ == '__main__':
    main()
