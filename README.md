# 求职招聘 AI · Job Find AI

> 一个智能化的多平台简历投递与管理系统。基于 [loks666/get_jobs](https://github.com/loks666/get_jobs) 二次开发，新增 **投递中心统一平台配置** 能力。

---

## ✨ 核心特性

### 🎯 投递中心一站式配置（核心新增）
- 用户**只需在投递中心填一次**，4 个平台（**Boss 直聘 / 51job / 猎聘 / 智联招聘**）的全部配置都能在此完成
- Boss 19 个字段（关键词、城市、行业、经验、学历、薪资、公司规模、融资阶段、工作类型、自定义招呼语、AI 检测、过滤死 HR 等） + Job51/Liepin/Zhilian 各 3 个字段（关键词、城市/区域、薪资），**共 28 个字段**
- 后端**自动校验每个被勾选平台的必填项**，缺一不可
- 配置即时驱动 Playwright 爬虫执行，**无需再去平台配置页分别设置**

### 🤖 AI 评分与差异化
- 接入 **MiniMax-M3** 大模型，对每个岗位进行多维度匹配评分（关键词 / 技能重合 / 经验 / 城市薪资 / 扩展段加成）
- AI 自动生成岗位定制版简历：个人简介改写、技能排序、项目/经验重点提炼

### 📄 简历中心
- 解析 PDF 简历 → 标准化 Profile
- 模板渲染：`editorial-dark-v1`（深色编辑风）/ `editorial-light-v2`（浅色编辑风）
- 同一份简历 → 自动生成多份"针对特定岗位"的版本

### 💬 AI Chat
- 多轮对话补全个人画像（基于 Profile）
- 工具调用结构化更新 Profile

### 📨 平台消息聚合
- 4 平台消息定时抓取 + SSE 实时推送
- 投递反馈回流

---

## 🧱 技术栈

**后端**
- Spring Boot 3.5.7 + Java 21
- MyBatis-Plus 3.x + SQLite
- Playwright（多平台浏览器自动化）
- SSE（实时进度推送）

**前端**
- Next.js 15 + React 19
- TypeScript + Tailwind CSS
- Framer Motion（动效）
- DM Sans / Playfair Display（编辑风字体）

**AI**
- MiniMax-M3（兼容 DeepSeek 推理模型输出 `<think>...` 块）

---

## 🚀 快速启动

### 环境要求
- JDK 17+（推荐 21）
- Node.js 20+
- pnpm
- SQLite 3

### 后端
```bash
./gradlew bootRun
# 默认监听 http://localhost:8888
```

### 前端
```bash
cd front
pnpm install
pnpm dev
# 默认监听 http://localhost:6867
```

### 数据库
首次启动自动建表（SQLite: `db/getjobs.db`）。AI 密钥、4 平台登录 cookie 等敏感信息**不要 push 到仓库**。

### 投递中心使用流程
1. **上传简历** → `/resume` 解析
2. **进入投递中心** `/delivery` → 勾选平台 → 在对应 tab 填配置
3. **提交需求** → 后端校验 + 落库 `delivery_request` + `platform_configs_json`
4. **拉取并匹配** → AI 评分注入岗位
5. **生成定制版** → AI 差异化简历
6. **启动爬虫投递** → `POST /api/delivery/{id}/run` → 4 worker 按 platform_configs 派发

---

## 📁 项目结构

```
src/main/java/com/getjobs/
├── delivery/          # 投递中心（本项目核心）
│   ├── controller/    # /api/delivery/*
│   ├── service/       # DeliveryService + ConfigOverrideApplier + DeliveryConfigOverrideHolder
│   ├── entity/        # DeliveryRequestEntity / DeliveryTargetEntity
│   └── mapper/        # MyBatis-Plus
├── worker/service/    # 4 个平台 worker + PlatformDispatchService 派发
├── resume/            # 简历解析 / 模板渲染
├── profile/           # Profile 模型（merged_view）
├── aichat/            # AI Chat 多轮对话
├── message/           # 平台消息聚合
└── application/       # ConfigService + 通用工具

front/app/
├── delivery/          # 投递中心（本项目核心）
├── resume-center/     # 简历中心
├── ai-config/         # AI 配置
├── ai-chat/           # AI Chat
├── messages/          # 平台消息
├── profile/           # 个人画像
└── dashboard/         # 仪表盘
```

---

## 🧩 投递中心的关键设计

### 数据模型
`delivery_request` 表新增字段：
```sql
platform_configs_json TEXT  -- {"boss":{...},"job51":{...},"liepin":{...},"zhilian":{...}}
config_validated      INT   -- 1=通过校验
```

### 后端流水线
```
POST /api/delivery/submit
  ↓
normalizePlatformConfigs()        # 列表/逗号/JSON 字符串归一化
  ↓
validateRequiredPlatformConfigs() # 每个被勾选平台必填项检查
  ↓
写入 DB（platform_configs_json + config_validated=1）
  ↓
POST /api/delivery/{id}/run
  ↓
executeByPlatforms()              # 按 platforms 派发
  ↓
PlatformDispatchService.executeDeliveryOverride()
  ↓
DeliveryConfigOverrideHolder.set()    # ThreadLocal 中转
  ↓
worker.executeDelivery()              # BossJobService / Job51JobService / ...
  ↓
worker.applyBoss/applyJob51/applyLiepin/applyZhilian(config, overrides)
  ↓
覆盖 config 后再启动 Playwright 爬虫
```

### Worker Override 模式
- 用户填的 overrides 通过 `ThreadLocal` 传递，**不污染全局 config bean**
- worker 在 `configService.getBossConfig()` 后立即 apply overrides
- 日志输出 `配置已按投递中心 platform_configs 覆盖：keywords=...` 便于审计

---

## ⚠️ 安全提示

- `db/getjobs.db` 包含 **AI API_KEY + 4 平台登录 cookie + 个人简历**，**绝对不要 push**
- `.gitignore` 已忽略 `db/`、`*.db`、`front/node_modules/`、`front/.next/`、`front/out/`、`*.env`、`cookie.json` 等
- 推送到 GitHub 前务必自查：`grep -rn "sk-\|api_key\|password" src/` 应无命中

---

## 📜 许可证

本项目基于上游 **GET JOBS Non-Commercial License 1.0 (GETJOBS-NC-1.0)** 进行二次开发，保留原始版权声明并增加二次开发者署名。详见 [LICENSE](LICENSE) 文件。

---

## 🙏 致谢

- 上游：[loks666/get_jobs](https://github.com/loks666/get_jobs) — commit `f809428`
- AI 模型：[MiniMax-M3](https://MiniMax.cn)
- 所有贡献者