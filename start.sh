#!/bin/bash
set -e

# safe.directory 자동 등록 (계정 무관 깃 오류 방지)
git config --global --add safe.directory "$PWD" 2>/dev/null || true

# 0. 깃허브 최신 코드 무조건 강제 동기화 (로컬 수정 충돌 원천 방지)
if [ "$1" != "--no-sync" ]; then
    echo "================================================================================"
    echo "🚀 [Formlimpic] 폼림픽 서버 원클릭 기동기"
    echo "================================================================================"
    echo "🔄 [0/4] 깃허브 최신 코드 자동 동기화 (origin/main)..."
    git fetch origin main || true
    git reset --hard origin/main || true
    chmod +x start.sh gradlew 2>/dev/null || true
    exec ./start.sh --no-sync "$@"
fi

echo "================================================================================"
echo "🚀 [Formlimpic] 폼림픽 서버 원클릭 기동기"
echo "================================================================================"

# 1. 기존 프로세스 완전 종료 (유령 프로세스/루트 프로세스 점유 방지)
echo "🧹 [1/4] 기존 실행 중인 서버 프로세스 정리..."
sudo systemctl stop formlimpic 2>/dev/null || true
sudo fuser -k 8080/tcp 2>/dev/null || true
sudo pkill -9 -f 'formlimpic.*jar' 2>/dev/null || true
pkill -9 -f 'formlimpic.*jar' 2>/dev/null || true

# 예전 Cloudflare Quick Tunnel 정리 (현재 접속 경로: 도메인 → Caddy(443) → 127.0.0.1:8080)
sudo systemctl disable --now formlimpic-tunnel 2>/dev/null || true
sudo rm -f /etc/systemd/system/formlimpic-tunnel.service 2>/dev/null || true
pkill -9 -f 'cloudflared tunnel --url http://localhost:80' 2>/dev/null || true
sleep 1

# 2. 로그 파일 초기화
sudo rm -f app.log tunnel.log 2>/dev/null || rm -f app.log tunnel.log
touch app.log
chmod 666 app.log 2>/dev/null || true
sudo chown $(id -u):$(id -g) app.log 2>/dev/null || true

# 3. PostgreSQL Docker 확인 및 실행 (예전 nginx 컨테이너는 --remove-orphans 로 정리)
echo "🐘 [2/4] PostgreSQL 컨테이너 기동..."
docker compose up -d --remove-orphans

# 4. 최신 코드 빌드
echo "📦 [3/4] 최신 프로젝트 빌드 (테스트 제외)..."
chmod +x ./gradlew
./gradlew build -x test

# 부하 테스트용 레이트리밋 우회 토큰 (환경변수 또는 loadtest_bypass.token 파일, 없으면 우회 비활성화)
BYPASS_TOKEN="${FORMLIMPIC_LOADTEST_BYPASS:-}"
if [ -z "$BYPASS_TOKEN" ] && [ -f "loadtest_bypass.token" ]; then
    BYPASS_TOKEN=$(tr -d '\r\n' < loadtest_bypass.token)
fi

# JVM 옵션: 힙 상한 명시 + OOM 시 반쯤 죽은 상태로 남지 않고 종료 (systemd 가 재시작)
JAVA_OPTS="${FORMLIMPIC_JAVA_OPTS:--Xmx1g -XX:+ExitOnOutOfMemoryError}"

# 5. 스프링 부트 서버 백그라운드 기동
if command -v systemctl &> /dev/null && sudo -n true 2>/dev/null; then
    echo "🌱 [4/4] 스프링 부트를 systemd 서비스로 등록 및 백그라운드 기동 (러너 프로세스 트리 격리)..."
    CURRENT_USER=$(id -un)
    CURRENT_DIR="$PWD"
    JAVA_BIN=$(command -v java || echo "/usr/bin/java")

    sudo tee /etc/systemd/system/formlimpic.service > /dev/null <<EOF
[Unit]
Description=Formlimpic Spring Boot Application
After=network.target

[Service]
Type=simple
User=${CURRENT_USER}
WorkingDirectory=${CURRENT_DIR}
Environment="PATH=${PATH}"
Environment="FORMLIMPIC_LOADTEST_BYPASS=${BYPASS_TOKEN}"
ExecStart=/bin/bash -c 'exec "${JAVA_BIN}" ${JAVA_OPTS} -jar build/libs/formlimpic-0.0.1-SNAPSHOT.jar > app.log 2>&1'
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

    sudo systemctl daemon-reload
    sudo systemctl reset-failed formlimpic 2>/dev/null || true
    sudo systemctl restart formlimpic
else
    echo "🌱 [4/4] 스프링 부트 서버 백그라운드 기동..."
    FORMLIMPIC_LOADTEST_BYPASS="$BYPASS_TOKEN" nohup java ${JAVA_OPTS} -jar build/libs/formlimpic-0.0.1-SNAPSHOT.jar > app.log 2>&1 &
fi

# 6. 관리자 비밀번호 생성 대기 (최대 30초)
echo "⏳ [Formlimpic] 관리자 비밀번호 발급 대기 중..."
ADMIN_PW=""
for i in {1..30}; do
    if grep -q "Password :" app.log 2>/dev/null; then
        ADMIN_PW=$(grep "Password :" app.log | sed -e 's/.*Password : *//' | tr -d '\r\n')
        break
    fi
    sleep 1
done

# 7. 접속 주소 (Caddy 가 443 → 127.0.0.1:8080 으로 프록시)
PUBLIC_URL="${FORMLIMPIC_PUBLIC_URL:-https://formlimpic.rescene.online}"

echo ""
echo "================================================================================"
echo "🎉 [Formlimpic] 모든 서비스가 성공적으로 구동되었습니다!"
echo "--------------------------------------------------------------------------------"
echo "🌐 1. 접속 주소 (HTTPS 🔒, Caddy → 127.0.0.1:8080):"
echo "   👉 ${PUBLIC_URL}"
echo ""
echo "👑 2. 관리자 로그인 정보 (유출 방지용 일회성 신규 비밀번호):"
echo "   👉 Username : admin"
echo "   👉 Password : ${ADMIN_PW:-생성 완료 (app.log 확인)}"
echo ""
echo "🧪 3. 부하 테스트 레이트리밋 우회: $([ -n "$BYPASS_TOKEN" ] && echo '활성화' || echo '비활성화 (loadtest_bypass.token 없음)')"
echo "--------------------------------------------------------------------------------"
echo "📋 실시간 로그 모니터링: tail -f app.log"
echo "🔧 서비스 상태 점검: sudo systemctl status formlimpic"
echo "================================================================================"

# 8. 디스코드 배포 완료 웹훅 알림 (설정된 경우)
DISCORD_WEBHOOK=""
if [ -n "$DISCORD_DEPLOY_WEBHOOK" ]; then
    DISCORD_WEBHOOK="$DISCORD_DEPLOY_WEBHOOK"
elif [ -f "deploy_webhook.url" ]; then
    DISCORD_WEBHOOK=$(cat deploy_webhook.url | tr -d '\r\n')
fi

if [ -n "$DISCORD_WEBHOOK" ]; then
    echo "📢 [Formlimpic] 디스코드 채널로 배포 완료 알림 전송 중..."
    NOW_KST=$(TZ="Asia/Seoul" date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S')

    PAYLOAD=$(cat <<EOF
{
  "embeds": [
    {
      "title": "🚀 [Formlimpic] 서버 자동 배포 완료!",
      "description": "최신 코드가 빌드되어 정상 기동되었습니다.",
      "color": 15553206,
      "fields": [
        {
          "name": "🌐 접속 주소 (HTTPS 🔒)",
          "value": "${PUBLIC_URL}",
          "inline": false
        },
        {
          "name": "👑 새로 발급된 관리자 비밀번호",
          "value": "ID: \`admin\` | PW: \`${ADMIN_PW:-생성 완료}\`",
          "inline": false
        },
        {
          "name": "⏱️ 배포 시각",
          "value": "${NOW_KST} (KST)",
          "inline": true
        }
      ],
      "footer": {
        "text": "formlimpic · JUST FOR PRACTICE"
      }
    }
  ]
}
EOF
)
    curl -s -H "Content-Type: application/json" -X POST -d "$PAYLOAD" "$DISCORD_WEBHOOK" > /dev/null || true
    echo "✅ 디스코드 알림 전송 완료!"
fi
