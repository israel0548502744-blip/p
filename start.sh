#!/usr/bin/env bash
# Convenience wrapper — the desktop app lives in desktop/.
exec "$(dirname "$0")/desktop/start.sh" "$@"
