# System-server reconnection tests

Build `:daemon:compileReleaseKotlin` and `:zygisk:compileReleaseKotlin`, then run:

```sh
python3 tests/reattach/run_tests.py \
  --jdk /path/to/jdk-21 \
  --sdk /path/to/android-sdk \
  --gradle-cache /path/to/gradle-user-home
```

Requires Python 3 and a C++17 compiler. The Java fixture loads the compiled
production classes with controlled Android/Binder doubles. It checks live
registration, caller authentication, duplicate handshakes, queued old death
notifications, PID reuse, partial restore retries, existing module contexts,
remote-preference snapshots and callbacks, and restored hot-reload targets.
The same assertions run for selected API branches from 26 through 37; this is
not an Android device or minimum-SDK compatibility matrix.

The native fixture extracts the production rendezvous and registration functions
from `ipc_bridge.cpp`. It checks absent, ordinary, and throwing services followed
by successful registration for both proxy names, plus bounded failure. Only
sleep and transport are replaced by deterministic doubles.

Device validation must additionally establish that restarting only the Vector
daemon changes its PID while preserving the kernel boot ID and the PID/start time
of `system_server` and zygote, then confirm application injection still works.
This recovery requires Vector's bridge to have already been injected; it cannot
inject an untouched running `system_server` or repair an external APEX mount failure.
