#!/bin/bash
# Official launcher script for /home/tablut/tablut/runmyplayer.sh
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

if [ "$#" -lt 3 ]; then
    echo "Usage: ./runmyplayer.sh <WHITE|BLACK> <timeout_seconds> <server_ip>"
    exit 1
fi

ROLE="$1"
TIMEOUT="$2"
SERVER_IP="$3"

TARGET_CLASSES="$DIR/target/classes"
LIB_DIR="$DIR/lib/*"

if [ ! -f "$TARGET_CLASSES/it/unibo/ai/tablut/Main.class" ]; then
    echo "Compiling Java sources (ACO Swarm Engine)..."
    mkdir -p "$TARGET_CLASSES"
    find "$DIR/src/main/java" -name "*.java" > "$DIR/sources.txt"
    javac -cp "$LIB_DIR" -d "$TARGET_CLASSES" @"$DIR/sources.txt"
    rm -f "$DIR/sources.txt"
fi

exec java -Xmx4G -XX:+UseG1GC -cp "$TARGET_CLASSES:$LIB_DIR" it.unibo.ai.tablut.Main "$ROLE" "$TIMEOUT" "$SERVER_IP"
