@echo off
cd /d "%~dp0"
if not exist target\hof-distributor.jar call mvn -q -B package
start "" javaw -jar target\hof-distributor.jar
