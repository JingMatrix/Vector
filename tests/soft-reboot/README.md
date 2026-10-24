# KernelSU emulated soft-reboot regression tests

Run from the repository root on Linux:

```sh
python3 tests/soft-reboot/run_tests.py
```

Requires Python 3, GCC, dash, util-linux (`unshare`, `mount`, `umount`), and
user/mount namespace support. If the host restricts unprivileged user namespaces,
use `sudo python3 tests/soft-reboot/run_tests.py`. Set `VECTOR_TEST_SHELL` to an
alternative shell executable to test its parser and execution behavior.

The suite runs the production cleanup script against real child processes and
bind mounts inside private user, network, and mount namespaces. Only Android
filesystem prefixes and the reference init PID are relocated in the temporary
script copy. It does not communicate with an Android device.

Coverage includes daemon identity independent of its name, unrelated processes,
deleted module directories, wrapper replacement, stacked and busy mounts,
preservation of other modules' mounts, Android compiler path variants, repeated
cleanup, first-update ordering, argument forwarding, and refusing startup when
cleanup fails. This is not an Android version/ABI compatibility test.
