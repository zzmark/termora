# 需要 zip/unzip
# $system32 = [System.Environment]::GetEnvironmentVariable("WINDIR") + "\System32"
# Invoke-WebRequest -Uri "http://stahlworks.com/dev/zip.exe" -OutFile "$system32\zip.exe"
# Invoke-WebRequest -Uri "http://stahlworks.com/dev/unzip.exe" -OutFile "$system32\unzip.exe"

.\gradlew :check-license
.\gradlew classes -x test
.\gradlew :jar :copy-dependencies :plugins:migration:build :jlink
.\gradlew :jpackage
.\gradlew :dist
