# Extract the directory path and change directory
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1

# Start the daemon directly in the background within a private mount namespace.
# Not every environment's unshare understands --propagation: toybox builds that
# implement only a subset of util-linux reject the flag outright, which leaves
# the daemon dead with nothing anywhere to say so. Probe the flag with a
# throwaway command and fall back to progressively simpler invocations.
if unshare --propagation slave -m true >/dev/null 2>&1; then
  unshare --propagation slave -m "$MODDIR/daemon" --system-server-max-retry=3 "$@" &
elif unshare -m true >/dev/null 2>&1; then
  unshare -m "$MODDIR/daemon" --system-server-max-retry=3 "$@" &
else
  "$MODDIR/daemon" --system-server-max-retry=3 "$@" &
fi
