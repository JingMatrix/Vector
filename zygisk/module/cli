#!/system/bin/sh

tmpDaemonApk="/data/local/tmp/daemon.apk"

# Safely check for debug APK and set classpath
if [ -r "$tmpDaemonApk" ]; then
	java_options="-Djava.class.path=$tmpDaemonApk"
else
	dex_path=""
	for DEXDIR in /data/adb/modules $(magisk --path 2>/dev/null)/.magisk/modules; do
		if [ -d "$DEXDIR/zygisk_vector" ]; then
			dex_path="$DEXDIR/zygisk_vector"
			break
		fi
	done

	if [ -z "$dex_path" ]; then
		echo "No vector module path found"
		exit 1
	fi

	dex_path="$dex_path/daemon.apk"
	java_options="-Djava.class.path=$dex_path"
fi

# Launch the cli
exec /system/bin/app_process $java_options /system/bin --nice-name=VectorCli org.matrix.vector.daemon.Cli "$@"
