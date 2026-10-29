#!/bin/sh
# Gradle wrapper başlatıcı. gradle-wrapper.jar yoksa otomatik indirir.
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar"

if [ ! -s "$JAR" ]; then
  echo "gradle-wrapper.jar indiriliyor..." >&2
  mkdir -p "$APP_HOME/gradle/wrapper"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$JAR" "$JAR_URL" || { rm -f "$JAR"; echo "İndirme başarısız" >&2; exit 1; }
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$JAR" "$JAR_URL" || { rm -f "$JAR"; echo "İndirme başarısız" >&2; exit 1; }
  else
    echo "curl veya wget gerekli" >&2; exit 1
  fi
fi

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD=java; fi
exec "$JAVACMD" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew \
  -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
