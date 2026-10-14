#!/usr/bin/env python3
"""Linux host regression test: compile the real wrapper and emulate its FD server.

Run with python3 dex2oat/tests/test_wrapper_recursion.py (requires g++).
Only Android logging and the Android linker path are replaced for host execution.
No root, mounts, Android device, or installed Vector instance is needed.
"""

import array
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import threading
import unittest
import uuid


class WrapperRecursionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.address = "vector-test-" + uuid.uuid4().hex
        cls.wrapper = cls.root / "wrapper"
        cls.stock = cls.root / "stock"
        cls.hook = cls.root / "hook.so"
        linker = cls.root / "linker"

        (cls.root / "logging.h").write_text(
            '#include <cstdio>\n#include <cerrno>\n'
            '#define LOGD(...) ((void)0)\n'
            '#define LOGI(...) ((void)0)\n'
            '#define LOGE(...) fprintf(stderr, __VA_ARGS__)\n'
            '#define PLOGE(...) fprintf(stderr, __VA_ARGS__)\n'
        )
        source = (Path(__file__).resolve().parents[1] / "src/main/cpp/dex2oat.cpp").read_text()
        source = source.replace("5291374ceda0aef7c5d86cd2a4f6a3ac", cls.address)
        for name in ("linker64", "linker"):
            source = source.replace("/apex/com.android.runtime/bin/" + name, str(linker))
        (cls.root / "wrapper.cpp").write_text(source)
        (cls.root / "linker.cpp").write_text(
            '#include <unistd.h>\nint main(int, char **argv) { execv(argv[1], argv + 1); return 99; }\n'
        )
        (cls.root / "stock.cpp").write_text(
            '#include <cstdio>\nint main() { puts("stock compiler reached"); return 0; }\n'
        )
        (cls.root / "hook.cpp").write_text('extern "C" void test_hook() {}\n')
        for name in ("wrapper", "linker", "stock"):
            subprocess.run(
                ["g++", "-std=c++20", "-Wall", "-Wextra", str(cls.root / (name + ".cpp")),
                 "-o", str(cls.root / name)], check=True
            )
        subprocess.run(
            ["g++", "-shared", "-fPIC", str(cls.root / "hook.cpp"), "-o", str(cls.hook)],
            check=True
        )

    def run_wrapper(self, recursive=False, inherited_marker=False):
        requests = []
        errors = []
        stop = threading.Event()
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
            server.bind("\0" + self.address)
            server.listen()
            server.settimeout(0.1)

            def serve():
                while not stop.is_set():
                    try:
                        client, _ = server.accept()
                    except socket.timeout:
                        continue
                    try:
                        with client:
                            client.settimeout(1)
                            command = client.recv(4, socket.MSG_WAITALL)
                            index = struct.unpack("i", command)[0]
                            requests.append(index)
                            path = self.hook if index >= 4 else (
                                self.wrapper if recursive else self.stock
                            )
                            fd = os.open(path, os.O_RDONLY)
                            try:
                                client.sendmsg([b"\x01"], [
                                    (socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [fd]))
                                ])
                                client.sendall(struct.pack("i", 1))
                            finally:
                                os.close(fd)
                    except Exception as error:
                        errors.append(error)
                        return

            thread = threading.Thread(target=serve, daemon=True)
            thread.start()
            env = os.environ.copy()
            env.pop("VECTOR_DEX2OAT_WRAPPER_PID", None)
            if inherited_marker:
                env["VECTOR_DEX2OAT_WRAPPER_PID"] = str(os.getpid())
            try:
                result = subprocess.run(
                    [str(self.wrapper)], env=env, capture_output=True, text=True, timeout=5
                )
            finally:
                stop.set()
                thread.join(timeout=2)
            self.assertFalse(thread.is_alive())
            self.assertEqual(errors, [])
        return result, requests

    def test_stock_compiler_still_runs(self):
        result, requests = self.run_wrapper()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("stock compiler reached", result.stdout)
        self.assertEqual(len(requests), 2)

    def test_wrapper_fd_stops_before_requesting_more_descriptors(self):
        result, requests = self.run_wrapper(recursive=True)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("Refusing recursive dex2oat wrapper execution", result.stderr)
        self.assertEqual(len(requests), 2)

    def test_parent_marker_does_not_block_child_compiler(self):
        result, requests = self.run_wrapper(inherited_marker=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("stock compiler reached", result.stdout)
        self.assertEqual(len(requests), 2)


if __name__ == "__main__":
    unittest.main()
