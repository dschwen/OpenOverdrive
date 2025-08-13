#!/usr/bin/env sh

##############################################################################
# Gradle start up script for UN*X
##############################################################################

DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_WRAPPER_JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$GRADLE_WRAPPER_JAR" ]; then
  echo "Gradle wrapper JAR not found. Android Studio will download it on sync."
fi

exec "$DIR/gradle/wrapper/gradle-wrapper" "$@"

