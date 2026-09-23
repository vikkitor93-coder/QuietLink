# QuietLink Raspberry Pi test rendezvous

This setup is for **milestone 7 testing**. It does not by itself complete milestone 8.

The Raspberry Pi implementation now uses **Python 3 only**. Node.js is not required on the Pi.

## Why Python on the Pi

The current test Pi is Raspberry Pi OS Bullseye on 32-bit `armhf`. Bullseye's normal repository provides Node 12, which is too old for the Node implementation's supported runtime. Rather than require an OS upgrade or unsupported Node build, QuietLink includes a standard-library Python implementation of the **same rendezvous API**:

- `GET /health`
- `GET /dev`
- `POST /v1/register`
- `POST /v1/poll`
- `POST /v1/leave`

Android does not care whether the server is the Node implementation or the Pi Python implementation.

## 1. Update the QuietLink checkout

```bash
cd ~/QuietLink
git pull --ff-only
```

## 2. Verify Python

```bash
python3 --version
```

Python 3.8+ is required. Raspberry Pi OS Bullseye normally satisfies this.

If Python is missing:

```bash
sudo apt update
sudo apt install -y python3
```

## 3. Install the rendezvous service

```bash
sudo bash rendezvous/pi/install.sh
```

The installer:

- copies the Python server to `/opt/quietlink-rendezvous/server.py`
- creates a restricted `quietlink-rendezvous` system user
- installs a systemd service
- enables and starts the service
- performs a local health check
- does **not** require npm or Node.js

## 4. Verify locally

```bash
curl http://127.0.0.1:8787/health
```

Expected fields include:

```text
"service":"quietlink-online"
"status":"ok"
"phase":"rendezvous-bootstrap"
"build":"pi-python-test"
```

You can also check:

```bash
systemctl status quietlink-rendezvous --no-pager
```

## 5. Permanent production tunnel

The permanent production deployment now uses a named Cloudflare Tunnel.

Current public hostname:

`https://rendezvousquietlinkvikman.dpdns.org`

The Cloudflare published application route maps that hostname to:

`http://127.0.0.1:8787`

The Pi runs `cloudflared` as an enabled systemd service, so no router port-forward is required.

Verify both services:

```bash
systemctl is-active quietlink-rendezvous
systemctl is-active cloudflared
```

Both should report `active`.

Then verify:

```bash
curl http://127.0.0.1:8787/health
curl https://rendezvousquietlinkvikman.dpdns.org/health
```

Both should return QuietLink health JSON. Never commit/paste the Cloudflare tunnel token.

Do not send SSH passwords, router passwords, tunnel credentials, private keys, or other secrets. Only the temporary public HTTPS test URL is needed.

## Privacy

The Python server disables the default HTTP access log because that would otherwise include client IP addresses.

The `/dev` endpoint exposes only:

- aggregate room/peer counts
- aggregate counters
- generic operational event names
- build/phase state

It does not expose room tokens, peer tokens, candidate endpoint values, device names, chat, or media.

The server temporarily holds candidates in memory only while registrations are live; registrations expire after about 45 seconds unless refreshed.

## Stop/remove

Stop:

```bash
sudo systemctl stop quietlink-rendezvous
```

Disable:

```bash
sudo systemctl disable quietlink-rendezvous
```

Installed files are under:

```text
/opt/quietlink-rendezvous
```

## 5. Install a temporary HTTPS tunnel helper

QuietLink includes an architecture-aware helper for `armhf`, `arm64`, and `amd64`:

```bash
sudo bash rendezvous/pi/install-cloudflared.sh
```

Then verify:

```bash
cloudflared --version
```

Start the milestone-7 Quick Tunnel:

```bash
cloudflared tunnel --url http://127.0.0.1:8787
```

Keep that terminal running. It will print a temporary HTTPS URL ending in `.trycloudflare.com`.

From another device/network, append `/health` to that URL. You should see the same QuietLink health JSON as the local Pi check.

Quick Tunnels are for temporary testing only. Milestone 8 will replace this with a stable production tunnel/hostname.

## Network exposure model

The Pi rendezvous server binds to `127.0.0.1:8787` only.

That means:

- port 8787 is **not** intentionally exposed to other devices on the home LAN
- no router port-forward is required
- the Pi's private LAN IP is not used by the Android app
- `cloudflared` is the only component that needs to reach the local rendezvous process
- the temporary public Quick Tunnel exposes only this HTTP service, not the rest of the Raspberry Pi or home network

For milestone 7, the Android app should use a **local developer/test rendezvous URL override** containing the temporary HTTPS `trycloudflare.com` URL. Do not commit the temporary tunnel URL to GitHub or the public update feed.


## DuckDNS + Nginx Proxy Manager

This is a good option for QuietLink and can replace the temporary Cloudflare Quick Tunnel.

### If Nginx Proxy Manager runs on the same Raspberry Pi

Keep QuietLink bound to the default:

`127.0.0.1:8787`

Create an NPM Proxy Host:

- Domain: your chosen DuckDNS hostname
- Scheme: `http`
- Forward Hostname / IP: `127.0.0.1`
- Forward Port: `8787`
- Request a Let's Encrypt certificate
- Force SSL after the certificate is working

No router port-forward is needed specifically for port 8787. Only the existing NPM HTTPS exposure is used.

### If Nginx Proxy Manager runs on another LAN machine

The remote NPM host cannot reach QuietLink while QuietLink is bound to `127.0.0.1`.

Set the systemd service environment to bind QuietLink to the Pi's LAN interface, then restrict port 8787 so only the NPM host can reach it.

The Pi server supports:

`BIND_HOST=<Pi LAN IP>`

Prefer the Pi's specific LAN IP over `0.0.0.0`.

After changing the bind host:

```bash
sudo systemctl daemon-reload
sudo systemctl restart quietlink-rendezvous
```

Then configure NPM to forward to:

`http://<Pi LAN IP>:8787`

Do not forward port 8787 on the router.

### GitHub / secrets

For milestone-7 testing, the DuckDNS/NPM hostname should be entered into QuietLink through a developer-only local rendezvous override. It does not need to be committed to GitHub.

Never commit:

- DuckDNS account token
- NPM passwords
- router credentials
- private keys
- internal LAN IPs if avoidable
- tunnel/API credentials

A permanent public rendezvous hostname itself is not secret and may later become part of production configuration, but credentials never belong in the repository.
