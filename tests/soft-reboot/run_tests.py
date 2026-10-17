#!/usr/bin/env python3
"""Exercise the production shell code against real isolated Linux mounts/processes.

No Android reboot, daemon, or app is touched. The outer invocation creates an
unprivileged user + mount namespace. Only /proc/1 (the test's init namespace)
and Android filesystem prefixes are relocated in a temporary script copy.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'zygisk/module/emulated-soft-reboot.sh'
INSIDE = '--inside' in sys.argv


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs)


class RestartTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='vector-soft-reboot-')
        cls.root = Path(cls.tmp.name)
        cls.binary = cls.root / 'app_process64'
        helper = cls.root / 'process.c'
        helper.write_text('''#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>
int main(int argc, char **argv) {
    const char *name = argc > 1 ? argv[1] : "random_default";
    prctl(PR_SET_NAME, name, 0, 0, 0);
    size_t n = strlen(argv[0]);
    memset(argv[0], 0, n);
    strncpy(argv[0], name, n);
    for (;;) pause();
}
''')
        run('gcc', '-Wall', '-Wextra', '-Werror', str(helper), '-o', str(cls.binary))
        cls.shell = os.environ.get('VECTOR_TEST_SHELL', '/bin/dash')
        run(cls.shell, '-n', str(SOURCE))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.case = Path(tempfile.mkdtemp(dir=self.root))
        self.module = self.case / 'data/adb/modules/zygisk_vector'
        (self.module / 'bin').mkdir(parents=True)
        self.cache = self.case / 'data/resource-cache'
        self.cache.mkdir(parents=True)
        self.targets = []
        for apex, names in (('runtime', ('dex2oat', 'dex2oatd', 'dex2oat64', 'dex2oatd64')),
                            ('art', ('dex2oat32', 'dex2oatd32', 'dex2oat64', 'dex2oatd64'))):
            for name in names:
                path = self.case / ('apex/com.android.' + apex) / 'bin' / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('stock compiler ' + str(path))
                self.targets.append(path)
        for bits in (32, 64):
            (self.module / f'bin/dex2oat{bits}').write_text(f'wrapper {bits}')
        source = SOURCE.read_text().replace('/proc/1/', f'/proc/{os.getpid()}/')
        source = source.replace('/data/resource-cache', str(self.cache))
        source = source.replace('/apex/com.android.', str(self.case) + '/apex/com.android.')
        self.script = self.module / SOURCE.name
        self.script.write_text(source)
        self.children = []
        self.env = dict(os.environ, KSU='true')

    def tearDown(self):
        for p in self.children:
            if p.poll() is None:
                p.kill()
            p.wait(timeout=4)
        for target in reversed(self.targets):
            for _ in range(20):
                result = subprocess.run(['umount', '-l', str(target)], capture_output=True)
                if result.returncode:
                    break
        shutil.rmtree(self.case)

    def stage(self, **kwargs):
        return subprocess.run([self.shell, str(self.script)], env=self.env,
                              text=True, capture_output=True, timeout=10, **kwargs)

    def assert_ok(self, result):
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, '')
        self.assertEqual(result.stderr, '')

    def spawn(self, name='q7Tf9b_4', private=True, module=None, cache_mount=None, binary=None):
        cwd = module or self.module
        binary = binary or self.binary
        if cache_mount is None:
            cache_mount = private
        ready = self.case / ('ready-' + str(len(self.children)))
        launcher = self.case / ('launch-' + str(len(self.children)) + '.sh')
        launcher.write_text(
            '#!/bin/sh\ncd "$1" || exit 1\n' +
            ('mount -t tmpfs tmpfs "$2" || exit 2\n' if cache_mount else '') +
            'touch "$3"\nexec "$4" "$5"\n')
        command = [self.shell, str(launcher), str(cwd), str(self.cache), str(ready),
                   str(binary), name]
        if private:
            command = ['unshare', '-m', '--propagation', 'slave', '--'] + command
        p = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        self.children.append(p)
        for _ in range(100):
            if ready.exists() and Path(f'/proc/{p.pid}/exe').resolve() == binary:
                return p
            if p.poll() is not None:
                self.fail('fixture did not start: ' + p.stderr.read())
            time.sleep(.01)
        self.fail('fixture startup timed out')

    def bind(self, source, target):
        run('mount', '--bind', str(source), str(target))

    def own_mount(self, target=None, bits=64):
        target = target or self.targets[6]
        self.bind(self.module / f'bin/dex2oat{bits}', target)
        return target

    def test_cold_start_has_no_changes(self):
        before = Path('/proc/self/mountinfo').read_text()
        self.assert_ok(self.stage())
        self.assertEqual(Path('/proc/self/mountinfo').read_text(), before)

    def test_random_process_name_is_stopped(self):
        p = self.spawn('r4nD0m_x73')
        self.assertNotIn('vectord', Path(f'/proc/{p.pid}/cmdline').read_text())
        self.assert_ok(self.stage())
        self.assertEqual(p.wait(timeout=2), -9)

    def test_two_different_random_names_are_stopped(self):
        a = self.spawn('u94n_19')
        b = self.spawn('t39a_27')
        self.assert_ok(self.stage())
        self.assertEqual(a.wait(timeout=2), -9)
        self.assertEqual(b.wait(timeout=2), -9)

    def test_names_with_spaces_and_parentheses(self):
        p = self.spawn('v (old) ) x')
        self.assert_ok(self.stage())
        self.assertEqual(p.wait(timeout=2), -9)

    def test_same_name_in_other_directory_is_not_killed(self):
        other = self.case / 'other'
        other.mkdir()
        p = self.spawn('vectord', module=other)
        self.assert_ok(self.stage())
        self.assertIsNone(p.poll())

    def test_cli_in_module_directory_is_not_killed(self):
        p = self.spawn('VectorCli', private=False)
        self.assert_ok(self.stage())
        self.assertIsNone(p.poll())

    def test_private_namespace_without_cache_mount_is_not_killed(self):
        p = self.spawn('vectord', cache_mount=False)
        self.assert_ok(self.stage())
        self.assertIsNone(p.poll())

    def test_other_executable_with_same_cwd_and_mounts_is_not_killed(self):
        binary = self.case / 'unrelated-process'
        shutil.copyfile(self.binary, binary)
        binary.chmod(0o755)
        p = self.spawn('vectord', binary=binary)
        self.assert_ok(self.stage())
        self.assertIsNone(p.poll())

    def test_old_deleted_module_directory_is_found(self):
        p = self.spawn('z8Er_1')
        saved = {path.relative_to(self.module): path.read_bytes()
                 for path in self.module.rglob('*') if path.is_file()}
        shutil.rmtree(self.module)
        for rel, data in saved.items():
            path = self.module / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.assertTrue(os.readlink(f'/proc/{p.pid}/cwd').endswith(' (deleted)'))
        self.assert_ok(self.stage())
        self.assertEqual(p.wait(timeout=2), -9)

    def test_all_android_10_and_11_plus_compiler_paths(self):
        expected = [p.stat().st_ino for p in self.targets]
        for i, target in enumerate(self.targets):
            self.own_mount(target, bits=32 if i % 2 else 64)
        self.assert_ok(self.stage())
        self.assertEqual([p.stat().st_ino for p in self.targets], expected)

    def test_stacked_vector_mounts_are_all_removed(self):
        target = self.targets[6]
        stock = target.read_bytes()
        for _ in range(3):
            self.own_mount(target)
        self.assert_ok(self.stage())
        self.assertEqual(target.read_bytes(), stock)

    def test_foreign_top_mount_is_preserved(self):
        target = self.own_mount()
        other = self.case / 'foreign-compiler'
        other.write_text('other module')
        self.bind(other, target)
        before = Path('/proc/self/mountinfo').read_text()
        self.assert_ok(self.stage())
        self.assertEqual(target.read_text(), 'other module')
        self.assertEqual(Path('/proc/self/mountinfo').read_text(), before)

    def test_foreign_layer_under_vector_is_preserved(self):
        target = self.targets[6]
        other = self.case / 'foreign-compiler'
        other.write_text('other module')
        self.bind(other, target)
        self.own_mount(target)
        self.assert_ok(self.stage())
        self.assertEqual(target.stat().st_ino, other.stat().st_ino)

    def test_replaced_wrapper_inode_is_detached(self):
        target = self.targets[6]
        stock = target.read_bytes()
        self.own_mount(target)
        wrapper = self.module / 'bin/dex2oat64'
        wrapper.unlink()
        wrapper.write_text('updated wrapper')
        self.assertNotEqual(target.stat().st_ino, wrapper.stat().st_ino)
        self.assert_ok(self.stage())
        self.assertEqual(target.read_bytes(), stock)

    def test_busy_mount_detaches_without_invalidating_open_fd(self):
        target = self.own_mount()
        with target.open('rb') as old_fd:
            self.assert_ok(self.stage())
            self.assertTrue(target.read_text().startswith('stock compiler'))
            self.assertEqual(old_fd.read(), b'wrapper 64')

    def test_no_apex_compilers_android_8_9(self):
        for target in self.targets:
            target.unlink()
        p = self.spawn()
        self.assert_ok(self.stage())
        self.assertEqual(p.wait(timeout=2), -9)

    def test_non_ksu_stage_is_noop(self):
        self.env.pop('KSU')
        p = self.spawn()
        target = self.own_mount()
        self.assert_ok(self.stage())
        self.assertIsNone(p.poll())
        self.assertEqual(target.read_text(), 'wrapper 64')

    def test_wrong_namespace_refuses_before_touching_state(self):
        p = self.spawn()
        target = self.own_mount()
        result = subprocess.run(['unshare', '-m', '--', self.shell, str(self.script)],
                                env=self.env, text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 1)
        self.assertIn('outside init mount namespace', result.stderr)
        self.assertIsNone(p.poll())
        self.assertEqual(target.read_text(), 'wrapper 64')

    def test_idempotent_after_cleanup(self):
        self.spawn()
        self.own_mount()
        self.assert_ok(self.stage())
        before = Path('/proc/self/mountinfo').read_text()
        self.assert_ok(self.stage())
        self.assertEqual(Path('/proc/self/mountinfo').read_text(), before)

    def test_repeated_daemon_and_wrapper_replacements(self):
        target = self.targets[6]
        stock = target.read_bytes()
        for cycle in range(3):
            p = self.spawn(f'restart_{cycle}')
            self.own_mount(target)
            wrapper = self.module / 'bin/dex2oat64'
            wrapper.unlink()
            wrapper.write_text(f'updated wrapper {cycle}')
            self.assert_ok(self.stage())
            self.assertEqual(p.wait(timeout=2), -9)
            self.assertEqual(target.read_bytes(), stock)

    def test_startup_guard_cleans_first_update_and_preserves_arguments(self):
        p = self.spawn()
        self.own_mount()
        service = self.module / 'service.sh'
        service.write_bytes((ROOT / 'zygisk/module/service.sh').read_bytes())
        result_path = self.case / 'daemon-started.json'
        daemon = self.module / 'daemon'
        daemon.write_text('#!/usr/bin/env python3\nimport json, pathlib, sys\n'
                          f'pathlib.Path({str(result_path)!r}).write_text(json.dumps('
                          f'[sys.argv[1:], pathlib.Path({str(self.targets[6])!r}).read_text()]))\n')
        daemon.chmod(0o755)
        result = subprocess.run([self.shell, str(service), '--late-inject', 'argument with spaces'],
                                env=self.env, text=True, capture_output=True, timeout=10)
        self.assert_ok(result)
        self.assertEqual(p.wait(timeout=2), -9)
        args, compiler = json.loads(result_path.read_text())
        self.assertEqual(args, ['--system-server-max-retry=3', '--late-inject', 'argument with spaces'])
        self.assertTrue(compiler.startswith('stock compiler'))

    def test_first_update_cleans_before_new_zygote_namespace(self):
        p = self.spawn('first_update_19')
        target = self.own_mount()
        stock = 'stock compiler ' + str(target)
        saved = {path.relative_to(self.module): path.read_bytes()
                 for path in self.module.rglob('*') if path.is_file()}
        # KernelSU removes the old module directory then activates modules_update.
        shutil.rmtree(self.module)
        for rel, data in saved.items():
            path = self.module / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        post_fs = self.module / 'post-fs-data.sh'
        post_fs.write_bytes((ROOT / 'zygisk/module/post-fs-data.sh').read_bytes())
        result = subprocess.run([self.shell, str(post_fs)], env=self.env,
                                text=True, capture_output=True, timeout=10)
        self.assert_ok(result)
        self.assertEqual(p.wait(timeout=2), -9)
        inherited = run('unshare', '-m', '--', 'cat', str(target)).stdout
        self.assertEqual(inherited, stock)

    def test_excessive_mount_stack_blocks_startup(self):
        for _ in range(17):
            self.own_mount()
        result = self.stage()
        self.assertEqual(result.returncode, 1)
        self.assertIn('cannot detach stale compiler', result.stderr)

    def test_service_does_not_launch_when_preparation_fails(self):
        self.script.write_text('exit 42\n')
        service = self.module / 'service.sh'
        service.write_bytes((ROOT / 'zygisk/module/service.sh').read_bytes())
        result_path = self.case / 'unexpected-launch'
        daemon = self.module / 'daemon'
        daemon.write_text('#!/bin/sh\ntouch ' + str(result_path) + '\n')
        daemon.chmod(0o755)
        result = subprocess.run([self.shell, str(service)], env=self.env,
                                text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 1)
        self.assertFalse(result_path.exists())


if __name__ == '__main__':
    if not INSIDE:
        result = subprocess.run(['unshare', '-Urnm', '--', sys.executable, __file__, '--inside'])
        sys.exit(result.returncode)
    run('mount', '--make-rprivate', '/')
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(RestartTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    print(json.dumps({'tests': result.testsRun, 'failures': len(result.failures),
                      'errors': len(result.errors),
                      'source_sha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
                      'real_mounts_and_processes': True}))
    sys.exit(not result.wasSuccessful())
