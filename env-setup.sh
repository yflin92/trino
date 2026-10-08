#!/usr/bin/env bash
# Shared environment setup for Trino SQLite fleet hunters/triage.
# GIT-BRANCH CHANNEL VERSION: /agentfs is NOT shared across sessions, so the
# cross-session channel is the git repo yflin92/trino. You BUILD THE PLUGIN
# LOCALLY (~30s) rather than reading a prebuilt one from a shared dir.
#
# Source this (`. env-setup.sh`) then use the helper functions.
#
# Constraints discovered by the coordinator:
#  - Docker overlay2 does NOT work on the virtiofs root. The daemon's data-root
#    MUST live on a tmpfs. We mount a 5G tmpfs at /mnt/docker-tmpfs.
#  - vfs would copy every layer and not fit, so overlay2-on-tmpfs is required.
#  - Native sqlite3 here is 3.45.1; the driver's bundled engine is 3.53.4.
#    Record both in any finding where version-dependent behaviour matters.
#  - Do NOT bind-mount the build tree directly; copy the plugin dir to
#    /tmp/plugin first (done by prep_files).
set -u

# --- config ---
PR_SHA="7ce55e6b32342d27fec58c7009f85abb1b0c6fe6"
PR_BRANCH="cs_mplujSNDv3/sqlite-connector"
REPO_URL="https://github.com/yflin92/trino"
IMAGE="trinodb/trino:483"
SRC_DIR="${SRC_DIR:-/tmp/trino-pr}"          # PR source checkout
BUILD_PLUGIN="$SRC_DIR/plugin/trino-sqlite/target/trino-sqlite-483"
LOCAL_PLUGIN="/tmp/plugin/trino-sqlite-483"
DATA_DIR="/tmp/data"
CATALOG_DIR="/tmp/catalog"
CONF_DIR="/tmp/trino-conf"

start_docker() {
  if docker info 2>/dev/null | grep -q overlay2; then echo "docker already up (overlay2)"; return 0; fi
  grep -q "$(hostname)" /etc/hosts 2>/dev/null || echo "127.0.0.1 $(hostname)" | sudo tee -a /etc/hosts >/dev/null 2>&1
  mountpoint -q /mnt/docker-tmpfs 2>/dev/null || { sudo mkdir -p /mnt/docker-tmpfs; sudo mount -t tmpfs -o size=5G tmpfs /mnt/docker-tmpfs; }
  sudo dockerd --config-file /dev/null --storage-driver=overlay2 --data-root=/mnt/docker-tmpfs --group docker >/tmp/dockerd.log 2>&1 &
  for i in $(seq 1 30); do docker info 2>/dev/null | grep -q overlay2 && { echo "docker up"; break; }; sleep 2; done
}

# Clone the PR source and build the plugin locally. Idempotent.
build_plugin() {
  if [ ! -d "$SRC_DIR/plugin/trino-sqlite" ]; then
    git clone --filter=blob:none --depth 1 -b "$PR_BRANCH" "$REPO_URL" "$SRC_DIR"
  fi
  # Verify we are on the pinned SHA
  local got; got=$(git -C "$SRC_DIR" rev-parse HEAD)
  [ "$got" = "$PR_SHA" ] || echo "WARNING: source HEAD $got != pinned $PR_SHA"
  if [ ! -d "$BUILD_PLUGIN" ]; then
    ( cd "$SRC_DIR/plugin/trino-sqlite"
      # pom edits: build against released 483 (PR targets 484-SNAPSHOT)
      sed -i -e 's#<version>484-SNAPSHOT</version>#<version>483</version>#' pom.xml
      sed -i -e 's#<relativePath>../../pom.xml</relativePath>#<relativePath/>#' pom.xml
      # io.airlift:guice -> com.google.inject:guice classifier=classes
      perl -0pi -e 's#<groupId>io.airlift</groupId>\s*<artifactId>guice</artifactId>#<groupId>com.google.inject</groupId>\n            <artifactId>guice</artifactId>\n            <classifier>classes</classifier>#' pom.xml
      JAVA_HOME=/usr/local/java/jdk-25 mvn -B package -Dmaven.test.skip=true -Dair.check.skip-all=true )
  fi
  [ -d "$BUILD_PLUGIN" ] && echo "plugin built at $BUILD_PLUGIN" || { echo "BUILD FAILED"; return 1; }
}

prep_files() {
  build_plugin || return 1
  rm -rf "$LOCAL_PLUGIN"; mkdir -p /tmp/plugin; cp -r "$BUILD_PLUGIN" /tmp/plugin/
  mkdir -p "$DATA_DIR"; chmod 777 "$DATA_DIR"
  mkdir -p "$CATALOG_DIR" "$CONF_DIR"
  docker image inspect "$IMAGE" >/dev/null 2>&1 || docker pull "$IMAGE"
  if [ ! -f "$CONF_DIR/jvm.config" ]; then
    docker run --rm --entrypoint cat "$IMAGE" /etc/trino/jvm.config \
      | sed -e 's/^-XX:InitialRAMPercentage=80/-Xms1G/' -e 's/^-XX:MaxRAMPercentage=80/-Xmx2G/' > "$CONF_DIR/jvm.config"
  fi
}

# make_catalog <name> <dbfile.db>
make_catalog() {
  cat > "$CATALOG_DIR/$1.properties" <<EOF
connector.name=sqlite
connection-url=jdbc:sqlite:/data/$2
EOF
}

# start_trino [container_name]  -> mounts ALL catalogs in /tmp/catalog
start_trino() {
  local cname="${1:-trino}"
  docker rm -f "$cname" >/dev/null 2>&1
  local mounts=()
  for f in "$CATALOG_DIR"/*.properties; do [ -e "$f" ] || continue; mounts+=( -v "$f:/etc/trino/catalog/$(basename "$f"):ro" ); done
  docker run -d --name "$cname" --ulimit nofile=131072:131072 \
    -v "$LOCAL_PLUGIN:/usr/lib/trino/plugin/sqlite:ro" \
    -v "$CONF_DIR/jvm.config:/etc/trino/jvm.config:ro" \
    "${mounts[@]}" -v "$DATA_DIR:/data" "$IMAGE" >/dev/null
  for i in $(seq 1 40); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$cname" 2>/dev/null)" = "healthy" ] && { echo "$cname healthy"; return 0; }
    sleep 4
  done
  echo "WARNING: $cname not healthy; docker logs $cname"; return 1
}

tq()  { docker exec "${TRINO_CONTAINER:-trino}" trino --execute "$1" --output-format CSV_HEADER 2>&1; }
tqs() { docker exec "${TRINO_CONTAINER:-trino}" trino --session "$1" --execute "$2" --output-format CSV_HEADER 2>&1; }

echo "env-setup loaded (git-branch channel). Functions: start_docker, build_plugin, prep_files, make_catalog, start_trino, tq, tqs"
echo "Typical: start_docker; prep_files; make_catalog mycat my.db; start_trino; tq 'SHOW SCHEMAS FROM mycat'"
