#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run with sudo: sudo bash rendezvous/pi/install-cloudflared.sh"
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  apt update
  apt install -y curl ca-certificates
fi

ARCH="$(dpkg --print-architecture)"
case "${ARCH}" in
  armhf) ASSET="cloudflared-linux-armhf.deb" ;;
  arm64) ASSET="cloudflared-linux-arm64.deb" ;;
  amd64) ASSET="cloudflared-linux-amd64.deb" ;;
  *)
    echo "Unsupported architecture for this helper: ${ARCH}"
    exit 1
    ;;
esac

URL="https://github.com/cloudflare/cloudflared/releases/latest/download/${ASSET}"
TMP="/tmp/${ASSET}"

echo "Downloading ${ASSET}..."
curl -fL "${URL}" -o "${TMP}"

echo "Installing cloudflared..."
dpkg -i "${TMP}"

echo
cloudflared --version
echo
echo "cloudflared is installed."
echo "Start the temporary QuietLink test tunnel with:"
echo "  cloudflared tunnel --url http://127.0.0.1:8787"
