#!/bin/bash
# Recompile rapide : sync des sources repo -> lab, javac complet, jar /tmp/modid-t232.jar.
# Ne touche pas au runtime (contrairement a rebuild_lab.sh). Idempotent.
set -e
LAB=/home/user/dlb-lab
REPO=/home/user/deep-lucky-block
cd "$LAB/mod-project"
cp -a "$REPO/mod_project/src/." src/
CP="build/classes/java/main:build/resources/main:$(paste -sd: build/moddev/serverLegacyClasspath.txt):build/moddev/artifacts/neoforge-21.1.190.jar:build/moddev/artifacts/neoforge-21.1.190-client-extra-aka-minecraft-resources.jar"
find src/main/java -name "*.java" ! -name "*.disabled" > /tmp/all_src.txt
"$LAB/jdk/bin/javac" -encoding UTF-8 -proc:none -nowarn -cp "$CP" -d build/classes/java/main @/tmp/all_src.txt 2>&1 | grep -v "^Note:" | head -8
echo "classes: $(find build/classes/java/main -name '*.class' | wc -l)"
rm -f /tmp/modid-t232.jar
(cd build/classes/java/main && "$LAB/jdk/bin/jar" cf /tmp/modid-t232.jar .)
(cd build/resources/main && "$LAB/jdk/bin/jar" uf /tmp/modid-t232.jar .)
echo "jar: $(ls -la /tmp/modid-t232.jar | awk '{print $5}') octets"
echo "== RECOMPILE OK =="
