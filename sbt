#!/bin/sh
java -Xms256M -Xmx1024M -XX:MaxPermSize=128M -jar `dirname $0`/sbt-launch.jar "$@"
