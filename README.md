# verse-book v2.0

Verse 羁绊书境 —— AI 互动书屋项目（前端部署包 + 后端源码）。

## 目录结构

| 目录 | 内容 |
|---|---|
| `frontend/` | 完整部署包：Spring Boot fat jar（`Verse-demo-1.0.jar`，Java 17）+ 前端构建产物 `public/` + 一键部署脚本 `deploy.sh` + TTS 音色库 `voices.json` |
| `backend/` | 后端源码：Spring Boot + MyBatis-Plus + MySQL（`pom.xml`、`src/`），含前端静态资源与部署脚本 |

## 快速开始

### 方式一：直接运行部署包（frontend/）

```bash
cd frontend
cp .env.example .env   # 填写 DB_PASSWORD、JWT_SECRET 及各 API key
java -jar Verse-demo-1.0.jar
```

### 方式二：从源码构建（backend/）

```bash
cd backend
cp .env.example .env   # 填写数据库与各 API key
mvn spring-boot:run    # 或 mvn package 后 java -jar
```

## 配置说明

所有敏感配置均通过环境变量 / `.env` 提供（模板见各目录 `.env.example`）：

- `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`：MySQL 数据库连接（必填）
- `JWT_SECRET`：登录态签名密钥（必填，随机长字符串）
- `LLM_API_KEY`：聊天大模型 key（不填则聊天不可用）
- `TTS_API_KEY` / `TTS_RESOURCE_ID`：火山引擎 TTS（不填则「念书」降级）
- `ARK_API_KEY`：火山方舟生图（不填则书封/插图降级）

> 本仓库为公开仓库，已移除所有内置数据库凭据与 API key，请自行准备数据库与 key。

## 部署

两个目录均附带 `deploy.sh` 一键部署脚本（Linux + systemd），详见 [frontend/README.md](frontend/README.md)。
