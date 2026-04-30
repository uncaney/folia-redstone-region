#!/bin/sh
set -eu

cd /server

if [ ! -f folia.jar ]; then
  if [ -n "${FOLIA_BUILD_URL:-}" ]; then
    echo "[entrypoint] downloading Folia from $FOLIA_BUILD_URL"
    wget -q -O folia.jar "$FOLIA_BUILD_URL"
  else
    echo "[entrypoint] ERROR: folia.jar missing and FOLIA_BUILD_URL unset" >&2
    exit 1
  fi
fi

# Plugins are mounted at /server/plugins by docker-compose
echo "[entrypoint] launching Folia"
exec java \
  -Xms1G -Xmx2G \
  -XX:+UseG1GC \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+ParallelRefProcEnabled \
  -XX:+AlwaysPreTouch \
  -XX:+DisableExplicitGC \
  -Dcom.mojang.eula.agree=true \
  -jar folia.jar nogui
