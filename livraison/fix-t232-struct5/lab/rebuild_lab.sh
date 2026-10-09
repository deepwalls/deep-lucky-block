#!/bin/bash
# Reconstruit l'environnement serveur T232 depuis les exports CI du repo.
# Idempotent. Usage: bash rebuild_lab.sh
set -e
LAB=/home/user/dlb-lab
REPO=/home/user/deep-lucky-block
echo "== extraction runtime serveur (ci/runtime/parts) =="
mkdir -p "$LAB"
cd "$REPO/ci/runtime"
cat parts/part-* > /tmp/runtime.tgz
cd "$LAB"
rm -rf mod-project .gradle
tar xzf /tmp/runtime.tgz mod-project .gradle
echo "== extraction JDK complet (ci/runtime-jre/parts) =="
cd "$REPO/ci/runtime-jre"
cat parts/part-* > /tmp/jdk.tgz
cd "$LAB"
rm -rf jdk
tar xzf /tmp/jdk.tgz
echo "== verification JDK =="
"$LAB/jdk/bin/java" -version 2>&1 | head -1
echo "== reecriture chemins runner -> lab =="
cd "$LAB/mod-project/build/moddev"
sed -i 's|/home/runner/.gradle|'"$LAB"'/.gradle|g; s|/home/runner/work/_temp|'"$LAB"'|g' \
    serverLegacyClasspath.txt serverRunVmArgs.txt serverRunProgramArgs.txt
echo "== recompilation COMPLETE des sources du mod (export CI partiel) =="
cd "$LAB/mod-project"
cp -a "$REPO/mod_project/src/." src/
CP="build/classes/java/main:build/resources/main:$(paste -sd: build/moddev/serverLegacyClasspath.txt):build/moddev/artifacts/neoforge-21.1.190.jar:build/moddev/artifacts/neoforge-21.1.190-client-extra-aka-minecraft-resources.jar"
find src/main/java -name "*.java" ! -name "*.disabled" > /tmp/all_src.txt
"$LAB/jdk/bin/javac" -encoding UTF-8 -proc:none -nowarn -cp "$CP" -d build/classes/java/main @/tmp/all_src.txt 2>&1 | grep -v "^Note:" | head -5
echo "classes: $(find build/classes/java/main -name '*.class' | wc -l)"
echo "== jar fusionne (correctif exploded-dir) =="
rm -f /tmp/modid-t232.jar
(cd build/classes/java/main && "$LAB/jdk/bin/jar" cf /tmp/modid-t232.jar .)
(cd build/resources/main && "$LAB/jdk/bin/jar" uf /tmp/modid-t232.jar .)
echo "== server.properties =="
cd "$LAB/mod-project/run"
python3 - << 'PYEOF'
text = open('server.properties').read()
def setp(text, k, v):
    out, done = [], False
    for line in text.split('\n'):
        if line.startswith(k + '='): out.append(f'{k}={v}'); done = True
        else: out.append(line)
    if not done: out.append(f'{k}={v}')
    return '\n'.join(out)
for k, v in [('motd', 'DLB T232 preview'), ('enable-rcon', 'true'),
             ('rcon.password', 'lablab'), ('online-mode', 'false')]:
    text = setp(text, k, v)
open('server.properties', 'w').write(text)
print('props ok')
PYEOF
echo "== boot_server.sh =="
cat > "$LAB/mod-project/boot_server.sh" << 'BEOF'
#!/bin/bash
set -e
cd /home/user/dlb-lab/mod-project/build/moddev
JAVA_BIN=/home/user/dlb-lab/jdk/bin/java
CONF=/home/user/dlb-lab/mod-project
mapfile -t VMA < <(grep -v '^#' serverRunVmArgs.txt | grep -v '^$')
mapfile -t PRA < <(grep -v '^#' serverRunProgramArgs.txt | grep -v '^$')
MODCP="/tmp/modid-t232.jar:$CONF/build/classes/java/main:$CONF/build/resources/main:$CONF/build/moddev/artifacts/neoforge-21.1.190.jar:$CONF/build/moddev/artifacts/neoforge-21.1.190-client-extra-aka-minecraft-resources.jar"
DEPCP=$(paste -sd: serverLegacyClasspath.txt)
cd "$CONF/run"
exec "$JAVA_BIN" -Xmx2200m \
  -Ddlb.chunks.workers=4 -Ddlb.chunks.pendingCap=2048 -Ddlb.debug=1 \
  "${VMA[@]}" -cp "$MODCP:$DEPCP" \
  cpw.mods.bootstraplauncher.BootstrapLauncher "${PRA[@]}" nogui
BEOF
chmod +x "$LAB/mod-project/boot_server.sh"
echo "== DONE =="
