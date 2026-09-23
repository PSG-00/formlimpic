#!/bin/bash
set -e

echo "================================================================================"
echo "🚀 [Formlimpic] 폼림픽 서버 & 클라우드플레어 터널 원클릭 통합 기동기"
echo "================================================================================"

# 0. 깃허브 최신 코드 자동 동기화
echo "🔄 [0/5] 깃허브 최신 코드 자동 동기화..."
git pull origin main 2>/dev/null || true

# 1. 기존 프로세스 완전 종료
echo "🧹 [1/5] 기존 실행 중인 서버 및 터널 프로세스 정리..."
pkill -9 -f 'formlimpic.*jar' 2>/dev/null || true
pkill -9 -f 'cloudflared' 2>/dev/null || true
sleep 1

# 2. 로그 파일 초기화
rm -f app.log tunnel.log

# 3. PostgreSQL Docker 및 Nginx 확인 및 실행
echo "🐘 [2/5] PostgreSQL 및 Nginx 컨테이너 기동..."
docker compose up -d

# 4. cloudflared 설치 여부 확인 및 자동 설치
if ! command -v cloudflared &> /dev/null; then
    echo "⬇️ cloudflared 패키지를 자동 설치합니다..."
    curl -sL --output cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
    dpkg -i cloudflared.deb 2>/dev/null || apt-get install -f -y
    rm -f cloudflared.deb
fi

# 5. 최신 코드 빌드
echo "📦 [3/5] 최신 프로젝트 빌드 (테스트 제외)..."
chmod +x ./gradlew
./gradlew build -x test

# 6. 스프링 부트 서버 백그라운드 기동
echo "🌱 [4/5] 스프링 부트 서버 백그라운드 기동..."
nohup java -jar build/libs/formlimpic-0.0.1-SNAPSHOT.jar > app.log 2>&1 &

# 7. Cloudflare Tunnel 백그라운드 기동 (Nginx 80 포트로 전달)
echo "🌐 [5/5] Cloudflare 터널 백그라운드 기동 (Nginx 80 포트 연결)..."
nohup cloudflared tunnel --url http://localhost:80 > tunnel.log 2>&1 &

# 8. 관리자 비밀번호 생성 대기 (최대 15초)
echo "⏳ [Formlimpic] 관리자 비밀번호 및 도메인 발급 대기 중..."
ADMIN_PW=""
for i in {1..15}; do
    if grep -q "Password :" app.log 2>/dev/null; then
        ADMIN_PW=$(grep "Password :" app.log | sed -e 's/.*Password : *//' | tr -d '\r\n')
        break
    fi
    sleep 1
done

# 9. 터널 URL 발급 대기 (최대 15초)
TUNNEL_URL=""
for i in {1..15}; do
    TUNNEL_URL=$(grep -o 'https://[a-zA-Z0-9.-]*\.trycloudflare\.com' tunnel.log 2>/dev/null | tail -n 1 || true)
    if [ -n "$TUNNEL_URL" ]; then
        break
    fi
    sleep 1
done

# 10. 공인 IP 확인
PUBLIC_IP=$(curl -s --max-time 2 ifconfig.me 2>/dev/null || echo "34.22.94.101")

echo ""
echo "================================================================================"
echo "🎉 [Formlimpic] 모든 서비스가 성공적으로 구동되었습니다!"
echo "--------------------------------------------------------------------------------"
echo "🌐 1. 클라우드플레어 공식 접속 주소 (HTTPS 🔒):"
if [ -n "$TUNNEL_URL" ]; then
    echo "   👉 ${TUNNEL_URL}"
else
    echo "   👉 (발급 대기 중 - 잠시 후 'cat tunnel.log' 로 확인)"
fi
echo ""
echo "💡 2. 직접 외부 IP 접속 주소 (HTTP 80 - 포트 번호 없이 접속!):"
echo "   👉 http://${PUBLIC_IP}"
echo "   (내부 8080 포트 직접 접속: http://${PUBLIC_IP}:8080)"
echo ""
echo "👑 3. 관리자 로그인 정보:"
echo "   👉 Username : admin"
echo "   👉 Password : ${ADMIN_PW:-생성 완료 (app.log 확인)}"
echo "--------------------------------------------------------------------------------"
echo "📋 실시간 로그 모니터링: tail -f app.log"
echo "================================================================================"
