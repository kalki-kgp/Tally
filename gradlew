#!/bin/sh
# Minimal Gradle wrapper launcher.
APP_HOME=$(cd -P -- "$(dirname -- "$0")" && pwd -P)
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" \
  -Xmx64m -Xms64m \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
