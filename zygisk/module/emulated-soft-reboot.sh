#!/system/bin/sh

# KernelSU calls this before stop/post-fs-data/start/service. service.sh calls
# it again before starting Vector, including the first update from a version
# without this stage. All state used below already belongs to the old daemon;
# no PID file, property, socket, or application hook is added.
[ "$KSU" = true ] || exit 0
MODDIR=$(CDPATH= cd -- "${0%/*}" && pwd -P) || exit 1

fail() {
    echo "Vector: cannot prepare daemon restart: $*" >&2
    exit 1
}

# Both cleanup and the subsequent daemon must see init's mount namespace.
# Refuse an unexpected caller rather than unmounting an unrelated namespace.
init_ns=$(readlink /proc/1/ns/mnt) || fail "cannot read init mount namespace"
self_ns=$(readlink /proc/self/ns/mnt) || fail "cannot read caller mount namespace"
[ "$init_ns" = "$self_ns" ] || fail "caller is outside init mount namespace"
init_cache_dev=$(stat -Lc '%d' /proc/1/root/data/resource-cache) || fail "cannot inspect resource cache"

daemon_identity() (
    # argv[0] and comm are deliberately NOT identities: either can be random.
    # The existing launcher cd's into this module and mounts its own tmpfs over
    # resource-cache in a private namespace before exec'ing app_process.
    # Checking all three also excludes a normal CLI client in the module cwd.
    pid=$1
    case "$pid" in ''|*[!0-9]*) return 1 ;; esac
    cwd=$(readlink "/proc/$pid/cwd") || return 1
    case "$cwd" in "$MODDIR"|"$MODDIR (deleted)") ;; *) return 1 ;; esac
    executable=$(readlink "/proc/$pid/exe") || return 1
    case "$executable" in */app_process|*/app_process32|*/app_process64) ;; *) return 1 ;; esac
    namespace=$(readlink "/proc/$pid/ns/mnt") || return 1
    [ "$namespace" != "$init_ns" ] || return 1
    cache_dev=$(stat -Lc '%d' "/proc/$pid/root/data/resource-cache") || return 1
    [ "$cache_dev" != "$init_cache_dev" ] || return 1
    awk '$5 == "/data/resource-cache" && / - tmpfs / { found = 1 }
         END { exit !found }' "/proc/$pid/mountinfo"
)

process_start() (
    # comm may contain spaces or parentheses. Field 22 is field 20 after the
    # final ') '. A recycled PID must not inherit the old daemon's identity.
    IFS= read -r record < "/proc/$1/stat" || return 1
    record=${record##*) }
    set -f
    set -- $record
    [ "$#" -ge 20 ] || return 1
    shift 19
    printf '%s\n' "$1"
)

daemon_candidates() {
    # Batch the procfs walk: spawning readlink once for every Android process
    # adds seconds to this blocking stage. ls is only a candidate filter; every
    # result is independently revalidated before it is signalled.
    ls -ld /proc/[0-9]*/cwd 2>/dev/null | awk -v dir="$MODDIR" '
        index($0, " -> ") {
            split($0, sides, " -> ")
            if (sides[2] != dir && sides[2] != dir " (deleted)") next
            n = split(sides[1], fields, /[[:space:]]+/)
            split(fields[n], path, "/")
            if (path[2] == "proc" && path[3] ~ /^[0-9]+$/) print path[3]
        }'
}

stop_daemons() {
    candidates=$(daemon_candidates) || return 1
    for pid in $candidates; do
        start=$(process_start "$pid" 2>/dev/null) || continue
        daemon_identity "$pid" 2>/dev/null || continue
        [ "$(process_start "$pid" 2>/dev/null)" = "$start" ] || continue
        kill -9 "$pid" 2>/dev/null || {
            daemon_identity "$pid" 2>/dev/null && return 1
        }
        # SIGKILL is asynchronous. Do not open the next daemon while an old one
        # can still own its sockets or recreate a compiler mount.
        tries=0
        while [ "$(process_start "$pid" 2>/dev/null)" = "$start" ]; do
            IFS= read -r record < "/proc/$pid/stat" || break
            case "${record##*) }" in Z\ *|X\ *) break ;; esac
            [ "$tries" -lt 20 ] || return 1
            sleep 0.05
            tries=$((tries + 1))
        done
    done
}

visible_mount() (
    # fdinfo identifies the visible mount even if mountinfo is not ordered by
    # stack depth. The FD is closed on leaving this subshell, before umount.
    exec 3< "$1" || return 1
    mount_id=
    while read -r key value rest; do
        [ "$key" != mnt_id: ] || mount_id=$value
    done < /proc/self/fdinfo/3
    case "$mount_id" in ''|*[!0-9]*) return 1 ;; esac
    printf '%s\n' "$mount_id"
)

cleanup_target() {
    target=$1
    [ -e "$target" ] || return 0
    layers=0
    while :; do
        mount_id=$(visible_mount "$target") || return 1
        root=$(awk -v id="$mount_id" -v target="$target" '
            $1 == id && $5 == target { print $4; found = 1; exit }
            END { if (!found) exit 1 }' /proc/self/mountinfo) || return 0

        # An exact mount root proves ownership even after modules_update has
        # replaced the old wrapper inode. /data can itself be a filesystem root.
        # Only these eight compiler targets and this module's wrappers qualify.
        root=${root%\\040(deleted)}
        root=${root%//deleted}
        case "$root" in
            "$MODDIR/bin/dex2oat32"|"$MODDIR/bin/dex2oat64"|\
            "${MODDIR#/data}/bin/dex2oat32"|"${MODDIR#/data}/bin/dex2oat64") ;;
            *) return 0 ;;
        esac
        [ "$layers" -lt 16 ] || return 1
        # Lazy detach is needed if a compiler still holds a reference while
        # Android is stopping. It removes the path immediately; existing open
        # descriptors remain valid until that process exits.
        umount "$target" 2>/dev/null || umount -l "$target" || return 1
        next_id=$(visible_mount "$target") || return 1
        [ "$next_id" != "$mount_id" ] || return 1
        layers=$((layers + 1))
    done
}

stop_daemons || fail "old daemon did not exit"

# Android 10 uses runtime; Android 11+ uses art. Android 8/9 has no APEX
# compiler wrapper here. ELF class detection remains in the unchanged daemon.
for target in \
    /apex/com.android.runtime/bin/dex2oat \
    /apex/com.android.runtime/bin/dex2oatd \
    /apex/com.android.runtime/bin/dex2oat64 \
    /apex/com.android.runtime/bin/dex2oatd64 \
    /apex/com.android.art/bin/dex2oat32 \
    /apex/com.android.art/bin/dex2oatd32 \
    /apex/com.android.art/bin/dex2oat64 \
    /apex/com.android.art/bin/dex2oatd64; do
    cleanup_target "$target" || fail "cannot detach stale compiler: $target"
done

exit 0
