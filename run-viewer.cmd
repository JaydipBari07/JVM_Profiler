@echo off
rem Starts the viewer on http://localhost:8686 reading target\demo-data (pass --dir <path> to change).
cd /d %~dp0
if "%~1"=="" (java -jar viewer\target\profiler-viewer.jar --dir target\demo-data --port 8686) else (java -jar viewer\target\profiler-viewer.jar %*)
