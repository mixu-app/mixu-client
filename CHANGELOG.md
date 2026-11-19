# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once a tagged release
exists.

## [0.0.4]

### 账号与云空间

- 邮箱验证码登录与注册接入真实账号验证；验证失败不会创建本地登录状态，无需设置密码。
- 使用 AndroidKeyStore 加密保存登录凭据，支持有效会话恢复、过期处理与退出登录；访客仍可离线导入和阅读。
- 账号余额、云空间等级、容量与成长记录读取服务器状态，无法读取时显示不可用，不再使用本地默认值冒充账号权益。
- 将五级永久云空间与当日下载额度分开展示；保留独立的当日临时下载加量政策，不将 50 MB 下载加量计入永久容量。
- 签到改为主动提交并以服务器确认的结果更新等级与记录，防止重复提交和账号切换后的旧请求干扰。
- 移除本地模拟广告奖励、补签、支付确认、订阅充值和空间状态切换；广告、补签与支付操作暂未开放。

### 验证与已知限制

- 已在受控网络传输下完成真实验证码登录、余额读取、加密保存、升级、重启恢复、旋转与退出登录验证，保持正常域名与 TLS 证书校验。
- 普通 Android 网络路径尚待验收；新的配额展示与服务器签到仍待联合设备验收。
- 云端测试账号状态的持久保存仍在完善，当前不承诺服务重启后保留全部测试状态。
- CTX-0050 的阅读位置视觉页偏差和 CTX-0051 的部分图片较多电子书打开超时仍未修复。

## [0.0.3]

### 界面与阅读体验

- 调整首页、图书馆、导航和“更多”页面，统一使用官方标志与可切换的主题样式。
- 精简阅读工具栏；通过 `Aa` 打开独立的字体、主题和排版设置，目录改为原生对话框。
- 改善手机、横屏、平板及窄屏大字体布局，保留图谱与 AI 伴读入口。
- 选文先显示复制、高亮、批注和查词操作，翻译及 AI 解释收进“更多”；按需展开所选工具。
- 查词不再为未知词伪造音标、词性或远程结果，也不自动附加剧情提示。
- 保留原始书内图片，移除阅读界面中自动添加的图片分类、编号和图注。
- 支持通过现有主题包调整界面字体与圆角；切换内置主题后，重启仍保留选择。

### 已知限制

- 部分阅读位置恢复仍可能出现视觉页偏差（CTX-0050）。
- 部分图片较多的电子书可能出现打开超时（CTX-0051）。
- 尚未完整覆盖 HTML 原型：首页收藏夹新建、改名及原型式分页仍未实现；现有图书馆管理和滚动仍可使用。
- 实体墨水屏的刷新、残影和功耗仍待真机验证，模拟设备上的界面验证不能替代这些检查。

## 早期开发记录（未按发布版本归档）

### Added

- Guest mode default session model, settings controls (themes, line spacing,
  reading/import defaults, account privacy status), and privacy-preserving structured
  logging with content firewall (zero narrative text or search query leakage).
- `docs/testing/eink-test-protocol.md`: manual E-Ink verification protocol and
  structured evidence runbook covering hardware matrix, six core test cases
  (cold start latency, page-turn ghosting, typography contrast, case board pan/redraw,
  touch targets/focus, and battery/thermal soak), log templates, and an M0/M1 baseline run.
- Repository scaffolding: `AGENTS.md` (agent rules), `repo.toml` (identity and
  contract edges), `.carryctx/config.toml` (shared CarryCtx baseline), `justfile`
  gates, CI workflow, `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`.
- `README.md` documenting the planned module layout, the non-negotiable
  boundaries (guest reading, one E-Ink APK, no cloud credentials, spoiler
  filtering), and the local toolchain prerequisites.

### Notes

- 上述早期开发记录未分配到具体发布版本，不代表当前功能清单。当前构建与验证以 `just check` 为准。
