# 海乐抢占助手 (Haile Bot)

[![Version](https://img.shields.io/badge/version-v4.2.0-2563eb)](https://haile-bot.pages.dev/haile-bot-v4.apk)
[![Platform](https://img.shields.io/badge/Android-8.0%2B%20Standalone-059669)](https://haile-bot.pages.dev/haile-bot-v4.apk)
[![Backend](https://img.shields.io/badge/Python-3.10%2B%20%7C%20FastAPI-475569)](./server.py)
[![License](https://img.shields.io/badge/license-MIT-64748b)](./LICENSE)

面向全国高校与公寓通用场景的 **海乐生活（Haier Haile Life）** 洗衣房空机监测、多机并池抢占与收衣提醒工具，提供 **独立 Android 原生客户端（`HaileBot-v4.apk`，约 235 KB，无需电脑后台）** 与 **本地 FastAPI Web 控制台** 双端同源体验。

> **安全与合规边界声明**
> - 本项目仅用于个人学习交流与宿舍日常洗烘空机监测辅助，**绝不提供自动扣款支付、免单绕过、余额篡改或高频并发攻击功能**。
> - 系统仅在检测到目标设备空闲或开放官方预约排号时创建待支付/预约单；所有订单支付均需用户本人在海乐生活官方 App、微信小程序或支付宝小程序内手动完成。
> - 账号凭证仅保存在用户设备本地（Android 应用私有沙盒或本地 Git 忽略文件），不上传任何第三方服务器。

---

## 下载与快速体验

### 方式一：Android 手机独立运行（推荐）

无需额外部署服务器，APK 内置完整本地抢占守护引擎与前台常驻倒计时通知服务：

- **最新安装包直链（v4.2.0）**：[下载 HaileBot-v4.apk](https://haile-bot.pages.dev/haile-bot-v4.apk)
- **应用内在线更新**：已安装 `v3.1.0+` / `v4.0.0+` 的用户点击顶栏或控制中心「检查更新」，即可在应用内单飞下载并无损覆盖升级（保留已登录账号与偏好配置）。

### 方式二：桌面 / 局域网 Python Web 运行

适合在 macOS / Linux / Windows 本机或宿舍常驻设备运行，通过浏览器访问响应式单页控制台：

```bash
git clone https://github.com/Evan-Joseph/haile-bot.git
cd haile-bot
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python server.py
```

启动后服务默认绑定本机回环地址与 `8010` 端口（可通过环境变量 `HAILE_BIND_HOST` 与 `HAILE_PORT` 自定义）。

---

## 全国高校通用选楼与常用设备

点击界面左上角 **楼栋名称** 即可呼出「切换洗衣房点位」面板，支持四种零门槛方式适配任意海乐营业点：

1. **首次登录自动识别**：输入手机号与短信验证码完成首次登录后，系统自动读取账号名下海乐历史订单与常用设备，自动定位并切换至你所在的宿舍洗衣房。
2. **最近使用设备直达**：面板内实时展示账号最近使用过的洗衣机/烘干机及当前空闲/剩余分钟数，点击即可一键切楼并打开对应洗烘套餐。
3. **交互式地图与高校检索**：
   - 支持 **手机/浏览器 GPS 一键定位**（内置 `WGS-84 → GCJ-02` 坐标转换，匹配国内地图与海乐测绘坐标系）；
   - 支持直接搜索学校、校区或大学城名称（如 `浙大紫金港`、`山东大学`、`广州大学城`），拖动地图十字准星即可实时加载周边楼栋与空机台数。
4. **自定义点位 ID / 机身码解析**：支持输入任意海乐数字 `positionId`，或直接粘贴微信扫机身二维码得到的链接、15 位 `IMEI`、`GTB/GYB` 设备编码自动解析所属楼栋。

---

## 核心特性

- **多机并池竞速抢占（Pool Preemption）**：
  - 点击「一键抢任意最快洗衣机」，仅占用 **1** 个账号活跃槽位即可同时盯守全楼候选洗衣机；任一机器率先空闲或开放预约，自动按预设偏好匹配该机专属 `goodsItemId` 与免费洗前筒自洁项完成锁单。
  - 若待支付订单超时未付款，引擎自动重开多机并池继续守护。
- **服务端时钟偏移校准与自适应脉冲调度**：
  - 基于上游 HTTP `Date` 响应头与半程 RTT 执行 EWMA 时钟偏移校准（`±600s` 安全限幅），消除终端系统时间快慢造成的抢拍误差。
  - 采用 `cruise → approach → burst → overtime` 四段式自适应调度：距离释放 `>60s` 低频只读巡航，进入 `T-10s` 开启脉冲窗口，超时未释放平滑转入 `1.2s` 尾窗探针（应对滚筒脱水延时解锁）。
  - 写请求严格走单次发送 + `recover_pending_order`（`underway` 与 `trade/list` 双源反查守卫），网络抖动下绝不盲目重放产生重复单。
- **多通道官方支付跳转与预约排号全流程**：
  - 锁单成功后支持一键拉起 **海乐生活官方 App**、**微信小程序**（官方白名单首页直达）或 **支付宝小程序收银台**（携带 `orderNo` 直达支付页）。
  - 针对预约排号单（`reserve_waiting`），支持在卡片内直接输入短信核验码、重发核验码、一键向未取衣前序同学发送官方取衣提醒及确认放衣关门。
- **洗烘偏好记忆与收衣提醒**：
  - 自动记忆常用洗涤程序、洗前筒自洁开关与烘干时长。
  - 已付款运行中订单不占用抢占槽位，预计结束前 3 分钟及洗烘完成瞬间各推送一次收衣提醒；顶栏铃铛支持一键全局静音（通知与震动同步静默）。

---

## 工程结构

```text
.
├── server.py                 # FastAPI Web 服务入口
├── haile_bot.py              # 上游协议常量、设备档位构建与本地账号存储
├── requirements.txt          # Python 运行依赖
├── HaileBot-v4.apk           # 独立 Android 原生客户端签名安装包（v4.2.0）
├── haile_v3/                 # Python 后端核心模块
│   ├── app.py                # REST API、SSE 实时事件流、点位搜索/解析与 OTA 接口
│   ├── engine.py             # 单机/并池抢占状态机、历史设备发现、收衣提醒与断点恢复
│   ├── domain.py             # 设备四态分类、时钟校准、WGS84→GCJ02 转换与脉冲调度算法
│   ├── recommendation.py     # 设备释放时间评估与优先度排序
│   └── api.py                # 上游 HTTP 客户端：DoH 解析、写超时双源反查与按账号连接池隔离
├── static/                   # 零构建模块化前端（Web 与 Android WebView 共用）
│   ├── index.html            # 语义化单页骨架与多抽屉容器
│   ├── css/app.css           # 浅色/深色自适应设计系统与无横向溢出响应式布局
│   ├── js/                   # store.js / map.js / ota.js / render.js / app.js
│   └── ota.json              # 纯静态 OTA 版本元数据
├── android/                  # 零 Gradle 纯命令行构建的独立 Android 客户端
│   ├── build_apk.sh          # 一键编译打包脚本（aapt2 + javac + d8 + zipalign + apksigner）
│   ├── AndroidManifest.xml   # 权限声明、ForegroundService 与 FileProvider 配置
│   └── src/top/hailebot/v3/  # Java 原生守护服务、本地 HTTP/SSE 服务、定位桥接与核心引擎
└── tests/                    # 单元测试套件（84 passed）
```

---

## 源码构建与测试

### 运行单元测试

```bash
.venv/bin/python -B -m pytest -q tests
```

### 从源码构建 Android APK

依赖 JDK 17+ 与 Android SDK（`build-tools` + `platforms`），无需安装 Gradle Wrapper：

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"   # Linux 通常为 $HOME/Android/Sdk
./android/build_apk.sh
adb install -r HaileBot-v4.apk
```

---

## 开源许可

本项目基于 [MIT License](./LICENSE) 开源。项目与海尔集团及海乐生活官方无任何商业隶属或背书关系，请合理、文明使用公共洗烘设施，收到锁单或洗烘完成提醒后及时前往操作与取衣。
