@echo off
rem Functional test helper: starts the demo Tomcat app with the agent, writing data to target\demo-data.
rem Then run run-viewer.cmd in a second terminal and open http://localhost:8686
cd /d %~dp0
java -javaagent:agent\target\profiler-agent.jar=config=profiler.properties,output.dir=target\demo-data -jar demo\target\profiler-demo.jar
