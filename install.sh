#!/usr/bin/env bash
# Installs the latest Graft release into every JetBrains IDE config found on this machine (macOS and Linux).
# Usage: curl -fsSL https://raw.githubusercontent.com/abstractivemachines/graft/main/install.sh | bash
set -euo pipefail

repo="abstractivemachines/graft"
tag="$(curl -fsSL "https://api.github.com/repos/$repo/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1)"
[ -n "$tag" ] || { echo "Could not determine the latest release tag." >&2; exit 1; }
version="${tag#v}"
zip_url="https://github.com/$repo/releases/download/$tag/graft-$version.zip"

case "$(uname -s)" in
  Darwin) base="$HOME/Library/Application Support/JetBrains" ;;
  Linux)  base="${XDG_DATA_HOME:-$HOME/.local/share}/JetBrains" ;;
  *) echo "Unsupported OS: $(uname -s). Install the zip via Settings | Plugins | Install Plugin from Disk." >&2; exit 1 ;;
esac

# Only 2026.2 (build 262) and newer IDEs have the MCP toolset API Graft needs.
targets=()
for dir in "$base"/*; do
  name="$(basename "$dir")"
  [[ "$name" =~ ^[A-Za-z]+(20[0-9]{2})\.([0-9]+)$ ]] || continue
  year="${BASH_REMATCH[1]}"; minor="${BASH_REMATCH[2]}"
  if (( year > 2026 || (year == 2026 && minor >= 2) )); then targets+=("$dir"); fi
done
[ ${#targets[@]} -gt 0 ] || { echo "No JetBrains IDE 2026.2 or newer found under $base." >&2; exit 1; }

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
echo "Downloading Graft $version..."
curl -fsSL -o "$tmp/graft.zip" "$zip_url"

for dir in "${targets[@]}"; do
  plugins="$dir/plugins"
  mkdir -p "$plugins"
  rm -rf "$plugins/graft"
  unzip -q -o "$tmp/graft.zip" -d "$plugins"
  echo "Installed into $plugins/graft"
done
echo "Restart the IDE(s) to load Graft, then restart your MCP client so it sees the new tools."
