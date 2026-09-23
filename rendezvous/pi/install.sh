#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run with sudo: sudo bash rendezvous/pi/install.sh"
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PY_SERVER_SRC="${REPO_ROOT}/rendezvous/pi/server.py"
INSTALL_DIR="/opt/quietlink-rendezvous"
SERVICE_FILE="/etc/systemd/system/quietlink-rendezvous.service"

if [[ ! -f "${PY_SERVER_SRC}" ]]; then
  echo "Could not find rendezvous/pi/server.py. Run git pull and try again."
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3 is required but was not found."
  echo "Install it with: sudo apt update && sudo apt install -y python3"
  exit 1
fi

PY_MAJOR="$(python3 -c 'import sys; print(sys.version_info.major)')"
PY_MINOR="$(python3 -c 'import sys; print(sys.version_info.minor)')"
if [[ "${PY_MAJOR}" -lt 3 || ( "${PY_MAJOR}" -eq 3 && "${PY_MINOR}" -lt 8 ) ]]; then
  echo "Python 3.8+ is required. Found: $(python3 --version)"
  exit 1
fi

if ! id quietlink-rendezvous >/dev/null 2>&1; then
  useradd --system --home "${INSTALL_DIR}" --shell /usr/sbin/nologin quietlink-rendezvous
fi

install -d -o quietlink-rendezvous -g quietlink-rendezvous -m 0750 "${INSTALL_DIR}"
install -o quietlink-rendezvous -g quietlink-rendezvous -m 0640   "${PY_SERVER_SRC}" "${INSTALL_DIR}/server.py"

cat > "${SERVICE_FILE}" <<'EOF'
[Unit]
Description=QuietLink rendezvous service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=quietlink-rendezvous
Group=quietlink-rendezvous
Environment=PORT=8787
Environment=QUIETLINK_RENDEZVOUS_BUILD=pi-python-control-relay
ExecStart=/usr/bin/python3 /opt/quietlink-rendezvous/server.py
Restart=on-failure
RestartSec=2
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now quietlink-rendezvous

sleep 1
echo
echo "Python runtime: $(python3 --version)"
echo "Service status:"
systemctl --no-pager --full status quietlink-rendezvous | sed -n '1,12p' || true

echo
echo "Health check:"
if command -v curl >/dev/null 2>&1; then
  curl --fail --silent --show-error http://127.0.0.1:8787/health
  echo
else
  python3 - <<'PY'
import urllib.request
print(urllib.request.urlopen("http://127.0.0.1:8787/health", timeout=3).read().decode())
PY
fi

echo
echo "QuietLink rendezvous is running on http://127.0.0.1:8787"
echo "No Node.js runtime is required for the Raspberry Pi server."
