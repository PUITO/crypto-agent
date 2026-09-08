#!/bin/sh
# CI may regenerate; local: install gradle 8.7 or use Android Studio
DIR="$(cd "$(dirname "$0")" && pwd)"
if command -v gradle >/dev/null 2>&1; then
  exec gradle -p "$DIR" "$@"
fi
echo "Please open in Android Studio or install Gradle 8.7" >&2
exit 1
