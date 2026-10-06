# Кодинг с телефона на VPS + GLM

`setup.sh` ставит на Ubuntu 24.04:

- **code-server**: VS Code в браузере телефона, работает по HTTPS через Caddy
- **Claude Code CLI**, переключённый на GLM API (Z.ai, Anthropic-совместимый endpoint)
- Node.js 22, Python 3, git, tmux (с прокруткой пальцем), mosh, ripgrep
- ufw (открыты 22, 80, 443, mosh) и fail2ban
- отдельного пользователя `dev` с sudo

## Установка

1. В Termux или Termius на телефоне:
   ```bash
   ssh root@2.27.154.194
   ```
2. На сервере:
   ```bash
   curl -fsSL https://raw.githubusercontent.com/trueshine34-stack/true/ccr-535e47c9-hkwzqn/vps-setup/setup.sh -o setup.sh
   bash setup.sh
   ```
   Скрипт спросит GLM API-ключ. Ключ не отображается при вводе и не уходит в репозиторий.

   Параметры можно задать заранее:
   `GLM_MODEL=glm-5.3 DOMAIN=voice-storm.vmheaven.io bash setup.sh`

3. В конце скрипт напечатает ссылку и пароль. Они же сохраняются в `/root/mobile-dev-info.txt`.

## Работа

- **Через браузер:** откройте `https://voice-storm.vmheaven.io`, введите пароль, затем Terminal → `claude`.
  Если домен не указывает на IP сервера, скрипт сам возьмёт `2-27-154-194.sslip.io`.
- **Через SSH:** `ssh root@2.27.154.194`, затем `su - dev`, `tmux new -As main`, `claude`.
  Если связь оборвётся, сессия tmux продолжит работать.

Модель и ключ можно поменять в `/home/dev/.claude/settings.json`.
