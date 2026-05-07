Trading Bot Backend Release

Files:
- tradingbot-backend.jar: standalone Java backend
- run-backend-release.sh: local runner
- tradingbot-backend.service: systemd service template
- install-linux-systemd-backend.sh: installer for the Linux PC
- bootstrap-linux-pc.sh: dependency installer for Ubuntu/Debian
- .env.example: example env config

Recommended install path on Linux:
/opt/tradingbot/backend

Basic run:
java -jar tradingbot-backend.jar

Systemd:
1. Create /opt/tradingbot/backend
2. Copy these files there
3. Copy tradingbot-backend.service to /etc/systemd/system/
4. systemctl daemon-reload
5. systemctl enable --now tradingbot-backend
