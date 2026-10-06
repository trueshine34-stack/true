#!/usr/bin/env bash
# Sets up an Ubuntu 24.04 server for coding with GLM (via the Z.ai API) from a phone.
# Usage (as root):  ZAI_API_KEY=xxx bash glm-coding-setup.sh
# Optional:         GLM_MODEL=glm-5.3  GLM_FAST_MODEL=glm-4.5-air
set -euo pipefail

GLM_MODEL="${GLM_MODEL:-glm-5.3}"
GLM_FAST_MODEL="${GLM_FAST_MODEL:-glm-4.5-air}"

if [[ $EUID -ne 0 ]]; then echo "Run as root"; exit 1; fi
if [[ -z "${ZAI_API_KEY:-}" ]]; then
  read -rsp "Z.ai API key (https://z.ai/manage-apikey/apikey-list): " ZAI_API_KEY; echo
fi

echo "==> System packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y curl git tmux mosh build-essential unzip ripgrep jq ca-certificates python3 python3-pip python3-venv

echo "==> Node.js 22"
if ! command -v node >/dev/null || [[ "$(node -v | cut -d. -f1)" != "v22" ]]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi

echo "==> Claude Code CLI (agent that will talk to GLM)"
npm install -g @anthropic-ai/claude-code

echo "==> Point the CLI at Z.ai's Anthropic-compatible endpoint"
mkdir -p /root/.claude
cat > /root/.claude/settings.json <<JSON
{
  "env": {
    "ANTHROPIC_BASE_URL": "https://api.z.ai/api/anthropic",
    "ANTHROPIC_AUTH_TOKEN": "${ZAI_API_KEY}",
    "ANTHROPIC_MODEL": "${GLM_MODEL}",
    "ANTHROPIC_DEFAULT_OPUS_MODEL": "${GLM_MODEL}",
    "ANTHROPIC_DEFAULT_SONNET_MODEL": "${GLM_MODEL}",
    "ANTHROPIC_DEFAULT_HAIKU_MODEL": "${GLM_FAST_MODEL}",
    "API_TIMEOUT_MS": "3000000",
    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"
  }
}
JSON
chmod 600 /root/.claude/settings.json
echo '{"hasCompletedOnboarding": true}' > /root/.claude.json.tmp
[[ -f /root/.claude.json ]] || mv /root/.claude.json.tmp /root/.claude.json
rm -f /root/.claude.json.tmp

echo "==> 'glm' command: opens (or reattaches) a persistent tmux session with the agent"
mkdir -p /root/projects
cat > /usr/local/bin/glm <<'SH'
#!/usr/bin/env bash
# glm [dir]  -> start/attach tmux session "glm" running the coding agent in dir
DIR="${1:-/root/projects}"
if tmux has-session -t glm 2>/dev/null; then exec tmux attach -t glm; fi
exec tmux new-session -s glm -c "$DIR" "claude; exec bash"
SH
chmod +x /usr/local/bin/glm

echo "==> tmux tuned for touch screens"
cat > /root/.tmux.conf <<'TM'
set -g mouse on
set -g history-limit 50000
set -g status-style bg=colour235,fg=colour250
TM

echo "==> Firewall rules for SSH + mosh (only if ufw is active)"
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow OpenSSH
  ufw allow 60000:61000/udp
fi

echo
echo "Done. Node $(node -v), $(claude --version 2>/dev/null || echo 'claude installed')"
echo "From the phone:  mosh root@<server-ip>   then type:  glm"
