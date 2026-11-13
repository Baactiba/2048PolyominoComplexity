@echo off
setlocal

set "JAR=%~dp02048OminoComplexity.jar"
if not exist "%JAR%" goto :no_jar

set "JAVAW="
if exist "C:\Program Files\Java\jdk-26.0.1\bin\javaw.exe" set "JAVAW=C:\Program Files\Java\jdk-26.0.1\bin\javaw.exe"
if not defined JAVAW if exist "C:\Program Files\Common Files\Oracle\Java\javapath\javaw.exe" set "JAVAW=C:\Program Files\Common Files\Oracle\Java\javapath\javaw.exe"
if not defined JAVAW if exist "C:\Program Files\Java\jdk-20\bin\javaw.exe" set "JAVAW=C:\Program Files\Java\jdk-20\bin\javaw.exe"
if not defined JAVAW if exist "C:\Program Files\Java\jdk-17.0.1\bin\javaw.exe" set "JAVAW=C:\Program Files\Java\jdk-17.0.1\bin\javaw.exe"
if not defined JAVAW (
    for %%I in (javaw.exe) do set "JAVAW=%%~$PATH:I"
)

if not defined JAVAW goto :no_java

start "" "%JAVAW%" -Xmx64G -jar "%JAR%"
exit /b 0

:no_jar
echo Could not find "%JAR%"
pause
exit /b 1

:no_java
echo Could not locate javaw.exe. Please ensure Java 17 or higher is installed.
pause
exit /b 1