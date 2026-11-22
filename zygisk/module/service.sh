# Extract the directory path and change directory
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1

# The daemon shadows /data/resource-cache while it runs, so its own app_process does not write the
# boot's framework idmaps into the cache every other process shares. That shadow is only safe
# inside a mount namespace nothing else can see: in the global one it blanks the RRO idmap cache
# for every process launched afterwards, including a freshly booting system_server — whose
# framework-res overlay redirects then stop resolving, and PMS refuses to start when
# config_servicesExtensionPackage does not name a package on the system image. That is a full
# system_server boot loop on GMS builds.
#
# A root manager runs this script with its own busybox, but a manual bootstrap has nothing in
# front of it except Android's utilities, and the two disagree: toybox unshare takes no
# propagation flag, busybox unshare does; and whether unshare is permitted at all depends on the
# SELinux domain the script runs in. So every branch here degrades to starting the daemon
# unshadowed rather than to not starting it, and the daemon script mounts its shadow only when
# this script confirms the namespace around it is private (VECTOR_SHADOW_OK).
if unshare -m true 2>/dev/null; then
  unshare -m /system/bin/sh -c '
      if mount -o rslave none /; then
        VECTOR_SHADOW_OK=1 exec "$0" "$@"
      else
        echo "vector: mount namespace stays shared; daemon starts without the resource-cache shadow" >&2
        VECTOR_NO_SHADOW=1 exec "$0" "$@"
      fi
  ' "$MODDIR/daemon" "$@" &
else
  echo "vector: no private mount namespace available; daemon starts without the resource-cache shadow" >&2
  VECTOR_NO_SHADOW=1 "$MODDIR/daemon" "$@" &
fi
