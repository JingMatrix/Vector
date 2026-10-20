# Extract the directory path and change directory
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1

# The service stage has no console, and the daemon cannot log anything before it
# exists, so a start that fails is invisible by construction. Keep one line per
# boot, and mirror it to logcat when log is around as well.
TRACE_LOG=/data/local/tmp/vector-service.log
trace() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$*" >>"$TRACE_LOG" 2>/dev/null
  command -v log >/dev/null 2>&1 && log -p i -t VectorService "$*" >/dev/null 2>&1
}

# A replayed service stage (KernelSU's emulated soft reboot) leaves the old
# daemon alive, and tryLock() in the daemon does not reliably turn the second
# one away (#878): the two then claim the same proxy service and break
# injection. The survivor handles a system_server restart through its binder
# death recipient, so leaving it alone is the repair; a second daemon is what
# breaks injection.
daemon_running() {
  command -v pgrep >/dev/null 2>&1 && pgrep -f '^vectord' >/dev/null 2>&1 && return 0
  command -v pidof >/dev/null 2>&1 && pidof vectord >/dev/null 2>&1 && return 0
  return 1
}

if daemon_running; then
  trace "vectord already running, not starting a second one"
  exit 0
fi

# toybox builds that implement only a subset of util-linux reject
# --propagation outright, which leaves the daemon dead with nothing anywhere to
# say so. The root solution usually ships a fuller busybox, so prefer its
# unshare, then the system one, and only then a namespace whose propagation
# type cannot be set.
find_busybox() {
  for bb in "${MAGISKTMP:-/nonexistent}/busybox" \
    "${MAGISKTMP:-/nonexistent}/.magisk/busybox" \
    /data/adb/ksu/bin/busybox \
    /data/adb/magisk/busybox \
    /data/adb/ap/bin/busybox \
    /system/xbin/busybox; do
    [ -f "$bb" ] && [ -x "$bb" ] && { printf '%s\n' "$bb"; return 0; }
  done
  bb="$(command -v busybox 2>/dev/null)" && [ -f "$bb" ] && [ -x "$bb" ] && printf '%s\n' "$bb"
}
BB="$(find_busybox)"
DAEMON="$MODDIR/daemon"

# The daemon mounts a tmpfs over /data/resource-cache, which is only safe when
# this namespace cannot propagate mounts back to the host.
# VECTOR_MOUNTNS_ISOLATED records that service.sh has established exactly that.
if [ -n "$BB" ] && "$BB" unshare --propagation slave -m true >/dev/null 2>&1; then
  trace "starting vectord with $BB unshare --propagation slave -m"
  VECTOR_MOUNTNS_ISOLATED=1 "$BB" unshare --propagation slave -m "$DAEMON" --system-server-max-retry=3 "$@" &
elif unshare --propagation slave -m true >/dev/null 2>&1; then
  trace "starting vectord with unshare --propagation slave -m"
  VECTOR_MOUNTNS_ISOLATED=1 unshare --propagation slave -m "$DAEMON" --system-server-max-retry=3 "$@" &
elif unshare -m /system/bin/sh -c '
    mount --make-rprivate / 2>/dev/null || mount --make-rslave / 2>/dev/null
  ' >/dev/null 2>&1; then
  trace "starting vectord with unshare -m and a private mount tree"
  VECTOR_MOUNTNS_ISOLATED=1 unshare -m /system/bin/sh -c '
    mount --make-rprivate / 2>/dev/null || mount --make-rslave / 2>/dev/null || exit 1
    exec "$0" "$@"
  ' "$DAEMON" --system-server-max-retry=3 "$@" &
elif unshare -m true >/dev/null 2>&1; then
  trace "starting vectord with unshare -m (propagation unchanged, mount skipped)"
  unshare -m "$DAEMON" --system-server-max-retry=3 "$@" &
else
  trace "starting vectord without a mount namespace (resource-cache mount skipped)"
  "$DAEMON" --system-server-max-retry=3 "$@" &
fi
