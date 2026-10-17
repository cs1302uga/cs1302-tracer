@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem Resolve an explicit JAR directory without changing the caller's working directory.
set "tracer_jar=%~dp0code-tracer.jar"
if defined CODE_TRACER_JAR_DIR set "tracer_jar=%CODE_TRACER_JAR_DIR%\code-tracer.jar"
if not exist "%tracer_jar%" goto missing_jar
if exist "%tracer_jar%\" goto missing_jar

rem An explicit JAVA_HOME takes precedence over executable lookup on PATH.
if defined JAVA_HOME goto java_home
set "tracer_java="
for %%J in (java.exe) do set "tracer_java=%%~$PATH:J"
if not defined tracer_java goto missing_java
set "tracer_javac="
for %%J in (javac.exe) do set "tracer_javac=%%~$PATH:J"
if not defined tracer_javac goto missing_javac
goto launch

:java_home
set "tracer_java=%JAVA_HOME%\bin\java.exe"
if not exist "%tracer_java%" goto invalid_java_home
if exist "%tracer_java%\" goto invalid_java_home

:launch
if defined JAVA_HOME set "tracer_javac=%JAVA_HOME%\bin\javac.exe"
if not exist "%tracer_javac%" goto missing_javac
if exist "%tracer_javac%\" goto missing_javac
rem Avoid CALL and delayed expansion so arguments are not expanded a second time.
"%tracer_java%" -jar "%tracer_jar%" %*
exit /b %errorlevel%

:missing_jar
if defined CODE_TRACER_JAR_DIR goto invalid_jar_dir
>&2 echo code-tracer: Cannot find "%tracer_jar%"; keep the launcher beside code-tracer.jar or set CODE_TRACER_JAR_DIR.
exit /b 1

:invalid_jar_dir
>&2 echo code-tracer: Cannot find "%tracer_jar%"; correct CODE_TRACER_JAR_DIR.
exit /b 1

:invalid_java_home
>&2 echo code-tracer: JAVA_HOME does not select a Java executable: "%tracer_java%"
exit /b 1

:missing_javac
>&2 echo code-tracer: No javac executable selected ("%tracer_javac%"); set JAVA_HOME to a full stable JDK 21 or later, or correct PATH.
exit /b 1

:missing_java
>&2 echo code-tracer: Java was not found; set JAVA_HOME or add a full JDK 21 or later to PATH.
exit /b 1
