# Extract the directory path and change directory
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1

# Watchdog loop: automatically restart the daemon if it crashes.
# Each iteration creates a fresh mount namespace (unshare -m), so no stale
# mount points accumulate.
#
# Rate-limiting: if the daemon crashes more than 3 times within any 60-second
# window the watchdog enters a 3-minute cooldown before retrying, then resets
# the counter.  Otherwise it restarts after a 5-second pause.
fails=0
window_start=$(date +%s)
while true; do
    unshare --propagation slave -m "$MODDIR/daemon" --system-server-max-retry=3 "$@"
    # daemon exited (crashed)
    now=$(date +%s)
    fails=$((fails + 1))
    if [ $((now - window_start)) -le 60 ] && [ $fails -gt 3 ]; then
        # exceeded crash budget → cooldown
        sleep 180
        fails=0
        window_start=$(date +%s)
    elif [ $((now - window_start)) -gt 60 ]; then
        # window expired, reset
        fails=1
        window_start=$now
        sleep 5
    else
        sleep 5
    fi
done &
