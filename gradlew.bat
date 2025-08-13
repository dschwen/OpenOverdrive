@ECHO OFF
SET DIR=%~dp0
SET WRAPPERJAR=%DIR%\gradle\wrapper\gradle-wrapper.jar

IF NOT EXIST "%WRAPPERJAR%" (
  ECHO Gradle wrapper JAR not found. Android Studio will download it on sync.
)

"%DIR%\gradle\wrapper\gradle-wrapper" %*

