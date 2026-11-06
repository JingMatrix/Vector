# Hot-reload resource lifetime tests

Build `:xposed:compileReleaseKotlin`, then run:

```sh
python3 tests/hotload/run_tests.py \
  --jdk /path/to/jdk-21 \
  --sdk /path/to/android-sdk \
  --gradle-cache /path/to/gradle-user-home
```

Requires Python 3, G++, the Android 37 SDK and the checked-out LSPlant submodule.
The tests load compiled production Kotlin classes and compile the native callback
operations extracted from `hook_bridge.cpp` with LSPlant's actual JNI monitor.

Coverage includes retained inactive handles, failed and successful replacement,
in-flight callback snapshots, retained reload parameters, empty callback snapshots,
and 3,000 atomic replacements. The executor tests check FIFO order, 8,000 concurrent
submissions, 100 drained batches, worker termination, interrupt isolation, and task,
reporter and thread-start failure recovery. DEX input tests cover ordered mapping,
successful consumption, partial mapping failure and loader/cleanup exceptions.

`--baseline /path/to/baseline` optionally runs the callback/handle checks against
preserved pre-change classes in `baseline-classes/` and native source in
`baseline/native/src/jni/hook_bridge.cpp`. The expected old failures are checked
explicitly; unrelated failures do not count as a successful negative control.

These host fixtures do not install ART hooks or prove ART-owned DEX reclamation.
`SharedMemory` is a controlled ownership/mapping double. Repeated on-device
hot reloads are needed to verify actual hook continuity and Android resources.
