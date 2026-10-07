#!/usr/bin/env bash
set -euo pipefail

container_name="${TRIGGER_WEBAPP_CONTAINER:-flunav-assistant-trigger-webapp-1}"

echo "Waiting for the next Trigger magic link from ${container_name}."
echo "Submit the email form at http://localhost:8030, then this command will print the link and exit."

while IFS= read -r line; do
    line="${line//\\\//\/}"
    if [[ "${line}" =~ (https?://[^[:space:]\"]+) ]]; then
        url="${BASH_REMATCH[1]}"
        url="${url%\)}"
        url="${url%,}"
        url="${url%.}"
        if [[ "${url}" == *"?"* ]]; then
            printf '%s\n' "${url}"
            exit 0
        fi
    fi
done < <(docker logs --follow --since 0s "${container_name}" 2>&1)
