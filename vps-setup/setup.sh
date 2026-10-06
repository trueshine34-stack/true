#!/usr/bin/env bash
# Mobile coding stack for an Ubuntu 24.04 VPS:
#   code-server (VS Code in the browser) behind Caddy with automatic HTTPS,
#   Claude Code CLI wired to the GLM (Z.ai) Anthropic-compatible API,
#   tmux/mosh for SSH from the phone, ufw + fail2ban.
#
# Usage (as root on the server):
#   curl -fsSL https://raw.githubusercontent.com/trueshine34-stack/true/main/vps-setup/setup.sh | sudo bash
#
# Optional env vars (asked interactively if missing):
#   GLM_API_KEY    Z.ai API key (required)
#   GLM_MODEL      model name                     (default: glm-5.3)
#   GLM_BASE_URL   Anthropic-compatible endpoint  (default: https://api.z.ai/api/anthropic)
#   DOMAIN         domain for HTTPS               (default: voice-storm.vmheaven.io, falls back to <ip>.sslip.io)
#   DEV_USER       linux user to code as          (default: dev)
#   CODE_PASSWORD  code-server password           (default: random)
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root: sudo bash setup.sh" >&2
  exit 1
fi
export DEBIAN_FRONTEND=noninteractive

GLM_MODEL="${GLM_MODEL:-glm-5.3}"
GLM_BASE_URL="${GLM_BASE_URL:-https://api.z.ai/api/anthropic}"
DOMAIN="${DOMAIN:-voice-storm.vmheaven.io}"
DEV_USER="${DEV_USER:-dev}"

log() { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[!] %s\033[0m\n' "$*"; }

if [ -z "${GLM_API_KEY:-}" ]; then
  read -rsp "GLM (Z.ai) API key: " GLM_API_KEY </dev/tty
  echo
fi
[ -n "$GLM_API_KEY" ] || { echo "GLM_API_KEY is required" >&2; exit 1; }
CODE_PASSWORD="${CODE_PASSWORD:-$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)}"

log "System packages"
apt-get update -y
apt-get upgrade -y
apt-get install -y \
  git curl wget ca-certificates gnupg jq unzip zip build-essential \
  python3 python3-pip python3-venv \
  tmux mosh htop ripgrep fd-find \
  ufw fail2ban debian-keyring debian-archive-keyring apt-transport-https

log "Domain check"
PUBLIC_IP="$(curl -fsS4 https://api.ipify.org || hostname -I | awk '{print $1}')"
RESOLVED_IP="$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}' || true)"
if [ "$RESOLVED_IP" != "$PUBLIC_IP" ]; then
  warn "$DOMAIN resolves to '${RESOLVED_IP:-nothing}', not $PUBLIC_IP"
  DOMAIN="${PUBLIC_IP//./-}.sslip.io"
  warn "Using $DOMAIN instead"
fi

log "User $DEV_USER"
if ! id "$DEV_USER" >/dev/null 2>&1; then
  useradd -m -s /bin/bash "$DEV_USER"
fi
usermod -aG sudo "$DEV_USER"
echo "$DEV_USER ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/90-$DEV_USER"
chmod 440 "/etc/sudoers.d/90-$DEV_USER"
DEV_HOME="$(getent passwd "$DEV_USER" | cut -d: -f6)"
if [ -f /root/.ssh/authorized_keys ]; then
  install -d -m 700 -o "$DEV_USER" -g "$DEV_USER" "$DEV_HOME/.ssh"
  install -m 600 -o "$DEV_USER" -g "$DEV_USER" /root/.ssh/authorized_keys "$DEV_HOME/.ssh/authorized_keys"
fi
install -d -o "$DEV_USER" -g "$DEV_USER" "$DEV_HOME/projects"

log "Node.js 22"
if ! command -v node >/dev/null 2>&1 || [ "$(node -v | cut -d. -f1 | tr -d v)" -lt 20 ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi

as_dev() { sudo -u "$DEV_USER" -H bash -lc "$1"; }

log "Claude Code CLI (GLM backend)"
as_dev 'mkdir -p ~/.npm-global && npm config set prefix ~/.npm-global'
grep -q 'npm-global' "$DEV_HOME/.bashrc" || echo 'export PATH="$HOME/.npm-global/bin:$PATH"' >> "$DEV_HOME/.bashrc"
grep -q 'npm-global' "$DEV_HOME/.profile" || echo 'export PATH="$HOME/.npm-global/bin:$PATH"' >> "$DEV_HOME/.profile"
as_dev 'npm install -g @anthropic-ai/claude-code'

install -d -m 700 -o "$DEV_USER" -g "$DEV_USER" "$DEV_HOME/.claude"
jq -n --arg url "$GLM_BASE_URL" --arg key "$GLM_API_KEY" --arg model "$GLM_MODEL" '{
  env: {
    ANTHROPIC_BASE_URL: $url,
    ANTHROPIC_AUTH_TOKEN: $key,
    ANTHROPIC_MODEL: $model,
    ANTHROPIC_DEFAULT_OPUS_MODEL: $model,
    ANTHROPIC_DEFAULT_SONNET_MODEL: $model,
    ANTHROPIC_DEFAULT_HAIKU_MODEL: $model,
    API_TIMEOUT_MS: "3000000",
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1"
  }
}' > "$DEV_HOME/.claude/settings.json"
# Skip the interactive login/onboarding: auth comes from the GLM token above.
if [ -f "$DEV_HOME/.claude.json" ]; then
  jq '.hasCompletedOnboarding = true' "$DEV_HOME/.claude.json" > "$DEV_HOME/.claude.json.tmp"
  mv "$DEV_HOME/.claude.json.tmp" "$DEV_HOME/.claude.json"
else
  echo '{"hasCompletedOnboarding": true}' > "$DEV_HOME/.claude.json"
fi
chown "$DEV_USER:$DEV_USER" "$DEV_HOME/.claude/settings.json" "$DEV_HOME/.claude.json"
chmod 600 "$DEV_HOME/.claude/settings.json" "$DEV_HOME/.claude.json"

log "tmux config (mouse/touch scrolling)"
cat > "$DEV_HOME/.tmux.conf" <<'TMUX'
set -g mouse on
set -g history-limit 50000
set -g default-terminal "tmux-256color"
set -sg escape-time 10
TMUX
chown "$DEV_USER:$DEV_USER" "$DEV_HOME/.tmux.conf"

log "code-server"
if ! command -v code-server >/dev/null 2>&1; then
  curl -fsSL https://code-server.dev/install.sh | sh
fi
install -d -o "$DEV_USER" -g "$DEV_USER" "$DEV_HOME/.config" "$DEV_HOME/.config/code-server"
cat > "$DEV_HOME/.config/code-server/config.yaml" <<YAML
bind-addr: 127.0.0.1:8080
auth: password
password: "$CODE_PASSWORD"
cert: false
YAML
chown "$DEV_USER:$DEV_USER" "$DEV_HOME/.config/code-server/config.yaml"
chmod 600 "$DEV_HOME/.config/code-server/config.yaml"
systemctl enable "code-server@$DEV_USER"
systemctl restart "code-server@$DEV_USER"

log "Caddy (HTTPS reverse proxy)"
if ! command -v caddy >/dev/null 2>&1; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y
  apt-get install -y caddy
fi
cat > /etc/caddy/Caddyfile <<CADDY
$DOMAIN {
	encode gzip
	reverse_proxy 127.0.0.1:8080
}
CADDY
systemctl enable caddy
systemctl reload caddy || systemctl restart caddy

log "Firewall + fail2ban"
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 60000:61000/udp
ufw --force enable
systemctl enable --now fail2ban

log "GLM API check"
HTTP_CODE="$(curl -s -o /tmp/glm-check.json -w '%{http_code}' "$GLM_BASE_URL/v1/messages" \
  -H "x-api-key: $GLM_API_KEY" -H "Authorization: Bearer $GLM_API_KEY" \
  -H 'anthropic-version: 2023-06-01' -H 'content-type: application/json' \
  -d "$(jq -n --arg m "$GLM_MODEL" '{model:$m,max_tokens:16,messages:[{role:"user",content:"ping"}]}')" || true)"
if [ "$HTTP_CODE" = "200" ]; then
  echo "GLM API OK ($GLM_MODEL)"
else
  warn "GLM API returned HTTP $HTTP_CODE:"
  cat /tmp/glm-check.json || true
  echo
  warn "Check the key / model name in $DEV_HOME/.claude/settings.json"
fi
rm -f /tmp/glm-check.json

INFO=/root/mobile-dev-info.txt
cat > "$INFO" <<INFOTXT
VS Code in browser : https://$DOMAIN/?folder=$DEV_HOME/projects
code-server pass   : $CODE_PASSWORD
SSH                : ssh $DEV_USER@$PUBLIC_IP   (keys copied from root)
Claude Code (GLM)  : open a terminal, run: claude
GLM model          : $GLM_MODEL   (edit $DEV_HOME/.claude/settings.json to change)
INFOTXT
chmod 600 "$INFO"

log "Done"
cat "$INFO"
echo
echo "Saved to $INFO"
