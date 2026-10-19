#!/bin/sh
set -eu
mkdir /work
cp -R /source/. /work/
cd /work
test ! -d /work/.m2
printf 'EMPTY_MAVEN_CACHE\n' > /out/state.txt
mvn --version > /out/toolchain.txt 2>&1
mvn -B -ntp -Dmaven.repo.local=/work/.m2 -Punit package > /out/unit.log 2>&1
printf 'UNIT_COMPLETE\n' >> /out/state.txt
mvn -B -ntp -Dmaven.repo.local=/work/.m2 -Pintegration -Dspring.config.additional-location=file:/config/integration.properties test > /out/integration.log 2>&1
printf 'INTEGRATION_COMPLETE\n' >> /out/state.txt
