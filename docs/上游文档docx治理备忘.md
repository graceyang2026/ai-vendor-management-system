# 上游文档（DOCX）治理备忘

> 性质：待办台账。上游 DOCX（`docs/TDD.docx`、`docs/供应商管理系统需求规格说明书-补充非MVP V1.6_20261011待评审.docx`、`docs/SRM 底座功能契约文档….docx`、`docs/SRM 后端数据库表设计20260930.docx`）按用户 2026-10-10 铁律**绝对只读**，本备忘只登记差异与回写意图，任何改写动作须用户明确授权后执行。
> 建立：2026-10-13（用户指令"把 D 维度免考等内容纳入后续提交的 docx 文档治理备忘录"）。
> 当前开发基线：暂停源码开发，只动文档（api-spec / 速查 / 本备忘）。

## 一、待回写条目

| # | 目标文档 | 章节 | 差异内容 | 现行权威 | 优先级 |
|---|---|---|---|---|---|
| 1 | TDD.docx（V1.4） | §6 绩效计算 | **D 交付维度零业务免考分支**：`total_batches=0 → D=100` 并复用 `quality_exempt` 标识。TDD 现文只写了 Q 维度免考（qc_total_batches=0 分支已于 2026-10-11 修订入 V1.4），D 维度的对称扩展尚未回写，属契约先行项 | api-spec §4.1（已显式标注"待回写上游，补之前以本契约为准"） | 高（下次 TDD 升版必须带上） |
| 2 | TDD.docx | §6 `PerformanceEvaluationDraft` | 契约已定性其为**后端内部暂存表，不暴露任何草稿 API**；TDD 原文未明说此边界 | api-spec §4.2 注记 + 用户 2026-10-10「以 TDD 为准」裁决 | 中 |
| 3 | SRM 后端数据库表设计.md→已删，现存 DOCX 版 | supplier_qualification / audit_log | 本两周 DDL 变更未同步进表设计文档：①资质 4 审核列+`approved_key` 生成列+`uk_supplier_approved_doc`；②`audit_log.supplier_id` 冗余列；③`supplier_qualification.version`（DDL④） | `schema.sql` 为建表唯一事实源（表设计 DOCX 落后于 schema.sql） | 中（下次表设计文档升版对齐） |
| 4 | SRS V1.6 | 资质审核路径 | V1.6 已按 TDD 口径重写"审计员不得绕过申请流程直接变更状态"（2026-10-11 完成）；但 V1.6 未覆盖 20261012 裁决的**资质双路径模型**（准入连带批复 vs 存续期 #11 单独审批、方案 A 全批 REJECTED） | api-spec §2 #7/#9/#11 | 低（SRS 属业务愿景层，可待下次评审一并注记） |
| 5 | TDD.docx（V1.4）+ 表设计 DOCX | 状态机 / 并发控制 | **乐观锁扩大至全部状态迁移写操作**（20261013 二次裁决）：①§0 并发审核资源由两实体改为全量四实体；②`LifecycleRequestResponse` 补 `version` 出参（候选 A）；③供应商 #6 `submit` 新增 `SupplierSubmitRequest{version}`（候选 B，原“无 body”作废）。上游文档均未描述“状态迁移必须带版本”的约束 | api-spec §0/§2/§3（已落地）；属**契约层补齐**，`schema.sql` 三表 `version DEFAULT 1` 早已存在无需 DDL 变更 | 中（下次 TDD 升版建议新增“并发控制与乐观锁”小节；实现层提醒：实体必须标 `@Version`） |

## 二、已闭环条目（仅存档，无需动作）

- ✅ SRS §4 资质控制机制与 TDD 的上游冲突：2026-10-11 用户升版 V1.6 按 TDD 口径重写，三处同轨（api-spec §2 注记在案）。
- ✅ Q 维度零业务免考：已写入 TDD V1.4（2026-10-11 修订）。
- ✅ 审计 action 枚举/底座契约（登录-权限-异常-审计）：以 api-spec §5 + 已实现代码为准，无上游差异挂账。

## 三、治理纪律（防漂移）

1. DOCX 只读；发现差异一律记本备忘 + 以 `api-spec.md`（接口）/ `schema.sql`（表）为工程权威源。
2. 回写动作 = 用户授权 + 明确指定文档与措辞后才执行；执行时保留原件另存新版，不覆盖。
3. 文档内"版本修订记录"台账机制已于 2026-10-11 废止，md 类变更历史由 git 承载；本备忘是**跨文档待办**唯一台账。

> 冲突时以 `docs/api-spec.md` 与 `srm-backend/src/main/resources/schema.sql` 为准。
