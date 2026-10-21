@echo off
rem Maven wrapper (downloads Maven on first run)
setlocal
set "MVN_VER=3.9.11"
set "MVN_HOME=%USERPROFILE%\.m2\wrapper\apache-maven-%MVN_VER%"
if not exist "%MVN_HOME%\bin\mvn.cmd" (
  echo Downloading Maven %MVN_VER% ...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; $d=Join-Path $env:USERPROFILE '.m2\wrapper'; New-Item -ItemType Directory -Force -Path $d | Out-Null; $z=Join-Path $d 'maven.zip'; Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVN_VER%/apache-maven-%MVN_VER%-bin.zip' -OutFile $z; Expand-Archive -Force -Path $z -DestinationPath $d; Remove-Item $z"
  if errorlevel 1 exit /b 1
)
call "%MVN_HOME%\bin\mvn.cmd" %*
