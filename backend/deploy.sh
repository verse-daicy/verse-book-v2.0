#!/usr/bin/env bash
# ============================================================
# Sylvia 森林书屋 v4.1 一键部署脚本（Spring Boot + MySQL）
# 用法（在服务器上，先上传 Sylvia-4.1.zip 到任意目录）：
#   chmod +x deploy.sh && sudo ./deploy.sh
# 或直接： sudo bash deploy.sh
# 特点：v4.1 后端为 Spring Boot fat jar（Java 17），无需 Node；
#       前端构建产物已内置在 public/；数据库为 MySQL sylvia_v4（5 表结构不变），连接通过 .env 的 DB_* 配置
# ============================================================
set -euo pipefail

# ---------- 可配置项 ----------
ZIP="${1:-/root/sylvia/Sylvia-4.1.zip}"          # zip 所在路径（可传参覆盖）
APP_DIR="${APP_DIR:-/root/sylvia/Sylvia-4.1}"    # 部署目录
PORT="${PORT:-3000}"
DOMAIN="${DOMAIN:-mist-mo.top}"                  # 你的域名
JAVA_MAJOR=17

# ---------- 颜色输出 ----------
c_green='\033[0;32m'; c_yellow='\033[1;33m'; c_red='\033[0;31m'; c_dim='\033[2m'; c_reset='\033[0m'
info()  { echo -e "${c_green}✔${c_reset} $*"; }
warn()  { echo -e "${c_yellow}⚠${c_reset} $*"; }
err()   { echo -e "${c_red}✘${c_reset} $*" >&2; }

echo ""
echo "==============================================="
echo "  📚 Sylvia 森林书屋 v4.1 一键部署（Spring Boot）"
echo "==============================================="
echo "  部署目录 : ${APP_DIR}"
echo "  端口     : ${PORT}"
echo "  域名     : ${DOMAIN}"
echo "-----------------------------------------------"

# ---------- 0. root 检查 ----------
[ "$(id -u)" -eq 0 ] || { err "请用 root 运行：sudo bash deploy.sh"; exit 1; }

# ---------- 1. Java 17 检查/安装 ----------
if ! command -v java >/dev/null 2>&1 || [ "$(java -version 2>&1 | grep -oP 'version \"\K[0-9]+' | head -1)" -lt "$JAVA_MAJOR" ]; then
  warn "未检测到 Java ≥ ${JAVA_MAJOR}，开始安装 openjdk-17-jre-headless …"
  apt-get update -qq && apt-get install -y openjdk-17-jre-headless
fi
JAVA_BIN="$(command -v java)"
info "Java：$(java -version 2>&1 | head -1)  ${JAVA_BIN}"

# ---------- 2. 解压源码 ----------
[ -f "$ZIP" ] || { err "找不到压缩包：$ZIP（请先上传）"; exit 1; }
mkdir -p "$APP_DIR"
rm -rf "$APP_DIR.tmp"
mkdir -p "$APP_DIR.tmp"
info "解压 $ZIP …"
unzip -q "$ZIP" -d "$APP_DIR.tmp"
# zip 顶层是 sylvia-server/（含 jar + public + deploy.sh）；若多包一层则上移一层
if [ -d "$APP_DIR.tmp/sylvia-server" ]; then
  cp -r "$APP_DIR.tmp/." "$APP_DIR/"
else
  sub=$(find "$APP_DIR.tmp" -maxdepth 1 -mindepth 1 -type d | head -1)
  cp -r "$sub/." "$APP_DIR/"
fi
rm -rf "$APP_DIR.tmp"
info "源码已就位：$APP_DIR"

# ---------- 3. jar 检查 ----------
JAR="$APP_DIR/sylvia-server/sylvia-4.1.jar"
if [ ! -f "$JAR" ]; then
  err "未找到后端 jar：$JAR（请确认 zip 包含 sylvia-server/sylvia-4.1.jar）"
  exit 1
fi
if [ ! -f "$APP_DIR/sylvia-server/public/index.html" ]; then
  err "未找到前端构建产物 public/index.html（请确认 zip 包含 sylvia-server/public/）"
  exit 1
fi
info "后端 jar 与前端产物就绪"

# ---------- 4. 环境变量 ----------
ENV_FILE="$APP_DIR/sylvia-server/.env"
CLI_PORT="${PORT:-}"
if [ ! -f "$ENV_FILE" ]; then
  warn "未找到 .env，生成模板（请务必填写 DB_* 配置后重启）"
  RANDOM_SECRET=$(openssl rand -hex 24 2>/dev/null || date +%s%N | sha256sum | cut -c1-32)
  cat > "$ENV_FILE" <<EOF
PORT=3000
JWT_SECRET=${RANDOM_SECRET}
# LLM_API_KEY=你的大模型Key（需自行填写，不填则聊天不可用）
# LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4
# LLM_MODEL=glm-4-flash
# 数据库连接（必填）
DB_URL=jdbc:mysql://localhost:3306/sylvia_v4?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=你的数据库密码
# TTS_API_KEY=你的TTS Key（可选，不填则「念书」不可用）
# ARK_API_KEY=你的豆包/火山方舟 Key（可选，不填则画师生图不可用，自创书用默认氛围图）
EOF
else
  info "已找到 .env，校验关键配置…"
  # v4.1 沿用旧 .env：JWT_SECRET 若还是 v4.0 占位值则换随机（会清空存量登录态，请谨慎）
  if grep -q "^JWT_SECRET=please-change-me-v4" "$ENV_FILE"; then
    warn "检测到 v4.0 占位 JWT_SECRET，请手动设置为与旧版一致的 JWT_SECRET，否则用户需要重新登录"
  fi
  if ! grep -q "^TTS_API_KEY=.\+" "$ENV_FILE"; then
    warn ".env 缺少 TTS_API_KEY：『念书』模式不可用。可从旧版 /root/Sylvia-3.x/sylvia-server/.env 找回，或到火山引擎控制台申请后填入 ${ENV_FILE}"
  fi
  if ! grep -q "^ARK_API_KEY=.\+" "$ENV_FILE"; then
    warn ".env 缺少 ARK_API_KEY：书屋画师（AI 书封/插图）不可用，自创书暂用默认氛围图。可从旧版 .env 找回或申请后填入 ${ENV_FILE}"
  fi
fi
set -a; . "$ENV_FILE" 2>/dev/null || true; set +a
[ -n "$CLI_PORT" ] && PORT="$CLI_PORT"

# ---------- 5. systemd 服务 ----------
SERVICE="sylvia-4.1"
SERVICE_FILE="/etc/systemd/system/${SERVICE}.service"
cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=Sylvia Forest Bookhouse v4.1 (Spring Boot)
After=network.target

[Service]
Type=simple
WorkingDirectory=${APP_DIR}/sylvia-server
EnvironmentFile=${ENV_FILE}
ExecStart=${JAVA_BIN} -Xms256m -Xmx512m -jar ${APP_DIR}/sylvia-server/sylvia-4.1.jar
Restart=always
RestartSec=3
Environment=PORT=${PORT}

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
# 停掉旧版 sylvia 进程（会占 3000 端口）
pm2 delete sylvia >/dev/null 2>&1 || true
pm2 delete sylvia-4.0 >/dev/null 2>&1 || true
systemctl stop ${SERVICE} >/dev/null 2>&1 || true
systemctl enable ${SERVICE} >/dev/null 2>&1 || true
systemctl start ${SERVICE}
info "已启动 systemd 服务 ${SERVICE}"
sleep 3
if curl -fsS "http://127.0.0.1:${PORT}/" >/dev/null 2>&1; then
  info "后端已就绪： http://127.0.0.1:${PORT}/"
else
  err "后端未正常响应，查看日志：journalctl -u ${SERVICE} -n 50"
  exit 1
fi

# ---------- 6. Nginx 配置（关键：/api/ 必须 proxy_buffering off） ----------
NGINX_CONF="/etc/nginx/conf.d/sylvia.conf"
if command -v nginx >/dev/null 2>&1; then
  warn "写入 Nginx 配置：$NGINX_CONF"
  cat > "$NGINX_CONF" <<EOF
server {
    listen 80;
    server_name ${DOMAIN};

    client_max_body_size 12m;

    # 前端静态资源（SPA）
    location / {
        proxy_pass http://127.0.0.1:${PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    # ★ SSE 流式回复：必须关闭 buffering，否则字会攒着不出
    location /api/ {
        proxy_pass http://127.0.0.1:${PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

    # 短链书签
    location ~ ^/s/ {
        proxy_pass http://127.0.0.1:${PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
    }
}
EOF
  nginx -t && systemctl reload nginx 2>/dev/null || service nginx reload
  info "Nginx 已配置并重载"
else
  warn "未安装 nginx，跳过（请自行配置反向代理，/api/ 需 proxy_buffering off）"
fi

# ---------- 7. 完成 ----------
echo ""
echo "==============================================="
echo "  ✅ 部署完成！"
echo "  访问： http://${DOMAIN}"
echo "  服务： systemctl status ${SERVICE}"
echo "  日志： journalctl -u ${SERVICE} -f"
echo "  配置： ${ENV_FILE}（修改后 systemctl restart ${SERVICE} 生效）"
echo "==============================================="
