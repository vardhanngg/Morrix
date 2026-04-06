#!/bin/bash
# Render injects PORT (usually 10000). Tomcat defaults to 8080.
# This patches server.xml at runtime so Tomcat listens on the right port.

PORT=${PORT:-8080}

sed -i "s/port=\"8080\"/port=\"${PORT}\"/" /usr/local/tomcat/conf/server.xml

echo "[Morix] Starting Tomcat on port ${PORT}"
exec catalina.sh run
