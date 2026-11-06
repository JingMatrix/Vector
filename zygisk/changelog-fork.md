Vector v2.2.1 is a fork build: upstream canary 3112 plus daemon-startup fixes for devices whose userland is toybox-only (verified on a PICO 4, Android 10).

- `service.sh` probes `unshare --propagation`, prefers the root solution's busybox, falls back to a private mount tree, and logs the path it took to `/data/local/tmp/vector-service.log`.
- The daemon's `/data/resource-cache` tmpfs is mounted only inside a namespace verified to be private, so it can no longer propagate to the host.
- A replayed service stage no longer starts a second daemon.
- `action.sh` prefers an installed manager.

This fork's manager is signed with its own key; uninstall a manager from another build before installing this one.
