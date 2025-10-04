#!/bin/bash

if [ -z "$APP_HOME" ]; then
  echo "ERROR: APP_HOME environment variable is not set."
  exit 1
fi

exec "$APP_HOME/bin/java" -jar "$(dirname "$0")/container-kitty-launcher-1.0-SNAPSHOT.jar"
