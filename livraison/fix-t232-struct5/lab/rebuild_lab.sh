#!/usr/bin/env bash
# T232 : reconstruit /home/user/dlb-lab a partir des parts exportees dans git.
set -e
cd /home/user/deep-lucky-block
git fetch origin arena/7cdbe3b3-deep-lucky-block -q || true
BR=origin/arena/7cdbe3b3-deep-lucky-block
# 1) JRE (deja present localement si existant)
mkdir -p /home/user/dlb-lab
if [ ! -x /home/user/dlb-lab/jdk/bin/java ]; then
  if git ls-tree -r "$BR" --name-only ci/runtime-jre/parts/ 2>/dev/null | grep -q part; then
    git ls-tree -r "$BR" --name-only ci/runtime-jre/parts/ | while read p; do git cat-file -p "$BR:$p"; done > /tmp/jdk.tgz
    tar -xzf /tmp/jdk.tgz -C /home/user/dlb-lab
  fi
fi
# 2) runtime complet (parts ci/runtime/parts/part-* : tar unique decoupe)
if git ls-tree -r "$BR" --name-only ci/runtime/parts/ 2>/dev/null | grep -q part; then
  git ls-tree -r "$BR" --name-only ci/runtime/parts/ | while read p; do git cat-file -p "$BR:$p"; done > /tmp/runtime2.tgz
  # le tar v1 contenait mod-project/ + .gradle/ ; le JDK pouvait etre inclus (jdk.tgz non extrait ici)
  rm -rf /tmp/rtc && mkdir -p /tmp/rtc
  tar -xzf /tmp/runtime2.tgz -C /tmp/rtc
  ls /tmp/rtc
fi
echo "rebuild termine"
