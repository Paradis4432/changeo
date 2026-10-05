#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
export CHANGEO_DATABASE_PASSWORD
CHANGEO_DATABASE_PASSWORD="$(cat .runtime/db.password)"
exec "${JAVA_HOME:?Set JAVA_HOME to a Java 25 JDK}/bin/java" -jar target/changeo-0.1.0-SNAPSHOT.jar "$@"
