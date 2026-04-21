#!/bin/sh
# Gradle Wrapper
GRADLE_USER_HOME="${HOME}/.gradle"
JAVA_OPTS="-Xmx512m"
exec java $JAVA_OPTS -jar "${GRADLE_USER_HOME}/wrapper/dists/gradle-8.4-bin/*/gradle-8.4/lib/gradle-launcher-8.4.jar" "$@"
