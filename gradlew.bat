@echo off
setlocal
set APP_HOME=%~dp0
set JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
set JAR_URL=https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar

if not exist "%JAR%" (
  echo gradle-wrapper.jar indiriliyor...
  if not exist "%APP_HOME%gradle\wrapper" mkdir "%APP_HOME%gradle\wrapper"
  powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri '%JAR_URL%' -OutFile '%JAR%'"
  if errorlevel 1 ( echo Indirme basarisiz & exit /b 1 )
)

if defined JAVA_HOME ( set JAVACMD=%JAVA_HOME%\bin\java.exe ) else ( set JAVACMD=java.exe )
"%JAVACMD%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
