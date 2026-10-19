#!/bin/sh
set -eu
java -Duser.timezone=Asia/Shanghai -jar /artifact/app.jar --server.port=18083 --spring.profiles.active=stage4 --spring.config.additional-location=file:/config/runtime.properties > /out/application.log 2>&1 &
app_pid=$!
trap 'kill "$app_pid" 2>/dev/null || true' EXIT
java /scripts/LinuxRuntimeSmoke.java /config/runtime.properties /out/smoke.json
