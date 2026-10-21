MANAGER_PACKAGE_NAME="@MANAGER_PACKAGE_NAME@"
INJECTED_PACKAGE_NAME="@INJECTED_PACKAGE_NAME@"

# The manager also ships inside this module as manager.apk and may have been
# installed as a regular package. When it has, launching its activity directly
# is more reliable than the injection path below, which needs
# ${INJECTED_PACKAGE_NAME} to start a 2D activity at all and to receive the
# injected manager dex -- neither holds in every environment (some systems
# never let the shell host an activity, and dynamic code loading restrictions
# can block the dex the way GrapheneOS does, see #805).
if pm path "$MANAGER_PACKAGE_NAME" >/dev/null 2>&1; then
  am start -n "$MANAGER_PACKAGE_NAME/.ui.MainActivity" >/dev/null 2>&1 && exit 0
fi

am start -c "${MANAGER_PACKAGE_NAME}.LAUNCH_MANAGER" "${INJECTED_PACKAGE_NAME}/.BugreportWarningActivity"
