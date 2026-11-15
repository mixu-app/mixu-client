# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once a tagged release
exists.

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
