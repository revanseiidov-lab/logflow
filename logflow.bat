@echo off
rem Kullanim: logflow.bat data\access-small.log
rem Once "mvn -q package" ile jar uretilmis olmalidir.
java -jar "%~dp0target\logflow.jar" %*
