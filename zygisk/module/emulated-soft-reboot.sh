# ksud runs this stage at the start of an emulated soft reboot, before `stop`.
# The daemon is detached from the service.sh that started it, so `stop` does not
# reach it while the same cycle runs service.sh again; without this, the second
# soft reboot leaves two daemons claiming the same proxy service name.

# -f is required: app_process sets the nice name in argv only, so comm stays
# "main" and `pkill vectord` matches nothing. The ^ anchor keeps the pattern
# from matching a shell whose own command line contains the word.
pids=$(pgrep -f '^vectord')
[ -n "$pids" ] && kill -9 $pids

exit 0
