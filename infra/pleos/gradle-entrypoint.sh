#!/bin/sh
set -eu
has_api_property=false
for argument in "$@"; do
    case "$argument" in
        -ProutyApiBaseUrl=*) has_api_property=true ;;
    esac
done
if [ -n "${ROUTY_API_BASE_URL:-}" ] && [ "$has_api_property" = false ]; then
    set -- "$@" "-ProutyApiBaseUrl=$ROUTY_API_BASE_URL"
fi
exec sh ./gradlew --no-daemon --console=plain "$@"
