#!/system/bin/sh

# On the first emulated reboot after installing this fix, the old active
# version may not have an emulated-soft-reboot.sh yet. KernelSU activates the
# update before this blocking stage, and starts Android only after it returns.
# Clean up here as well so the new zygote cannot inherit stale compiler mounts.
[ "$KSU" = true ] || exit 0
MODDIR="${0%/*}"
exec sh "$MODDIR/emulated-soft-reboot.sh"
