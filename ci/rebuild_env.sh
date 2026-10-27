#!/usr/bin/env bash
# Reconstruit le lab serveur /tmp/rt a partir des parts trackés du dépôt.
# (les snapshots /tmp et /home/user sont wipés entre les tours — relancer ce
# script en début de tour avant tout banc, ~3-4 min).
#
#   bash ci/rebuild_env.sh            # rebuild complet
#   bash ci/rebuild_env.sh --check    # vérifie seulement
#
# Ingrédients trackés :
#   ci/runtime/parts/part-*     (tar.gz ~1,3 Go : mod-project + caches Gradle)
#   ci/runtime-jre/parts/part-* (tar.gz ~250 Mo : Temurin JDK 21)
#   mod_project/src/            (sources HEAD à jour, overlays sur le runtime)
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
RT=/tmp/rt
JDK=/tmp/jdk

if [ "${1:-}" = "--check" ]; then
    echo "mod-project: $([ -d $RT/mod-project/src ] && echo OK || echo MISSING)"
    echo "gradle-home: $([ -d $RT/.gradle/caches ] && echo OK || echo MISSING)"
    echo "jdk:         $([ -x $JDK/bin/java ] && echo OK || echo MISSING)"
    exit 0
fi

# 1. Bases depuis les parts trackés (cat | tar, pas de symlink)
if [ ! -d "$RT/mod-project/src" ]; then
    mkdir -p "$RT"
    cat "$REPO/ci/runtime/parts/part-"* | tar -xzf - -C "$RT"
    # le tar contient mod-project/, .gradle/, Java_Temurin-Hotspot_jdk/ (squelettes)
    rm -rf "$RT/Java_Temurin-Hotspot_jdk"
fi

# 2. Sources HEAD (fixes Txxx) par-dessus le runtime figé (pas de rsync ici)
rm -rf "$RT/mod-project/src"
cp -a "$REPO/mod_project/src" "$RT/mod-project/src"

# 3. JDK 21 depuis runtime-jre
if [ ! -x "$JDK/bin/java" ]; then
    mkdir -p /tmp/jre_ci "$JDK"
    cat "$REPO/ci/runtime-jre/parts/part-"* | tar -xzf - -C /tmp/jre_ci
    cp -a /tmp/jre_ci/jdk/. "$JDK/"
    cp -a /tmp/jre_ci/jdk/bin/java.real "$JDK/bin/" 2>/dev/null || true
fi
# Wrapper anti-OOM vineflower (le daemon décompile avec -Xmx4G, 3,9 Go dispo)
if [ ! -f "$JDK/bin/java.real" ]; then
    mv "$JDK/bin/java" "$JDK/bin/java.real"
    cat > "$JDK/bin/java" <<'EOF'
#!/bin/bash
args=("$@")
for ((i=0;i<${#args[@]};i++)); do
    if [[ "${args[$i]}" == "-Xmx4"G ]]; then args[$i]="-Xmx2500m"; fi
done
exec /tmp/jdk/bin/java.real "${args[@]}"
EOF
    chmod +x "$JDK/bin/java"
fi

# 4. eula + dossier run
mkdir -p "$RT/mod-project/run"
echo 'eula=true' > "$RT/mod-project/run/eula.txt"

echo "[rebuild_env] OK — mod-project=$RT/mod-project jdk=$JDK"
