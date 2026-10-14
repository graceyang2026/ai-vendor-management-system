package com.srm.core.dto.file;

/**
 * 模块一 §1.6 文件上传的<b>唯一</b>约束与文案常量源（与 {@code dto/supplier/SupplierValidationPatterns}
 * 同思路：规则与固定文案只写一处，杜绝"控制器一套、服务另一套"的漂移；本类的取值同时被
 * {@code FileStorageServiceImpl}（业务校验）与 {@code GlobalExceptionHandler}（容器层超限兜底）引用，
 * 保证"超限"在服务层与容器层两条路径上回传<b>完全相同</b>的 code 与 message）。
 *
 * 契约出处：{@code docs/接口契约文档-供应商与绩效-20261013.md} §1.6 与 {@code docs/api-spec.md}「文件上传」：
 * 仅允许 {@code pdf/jpg/jpeg/png}；单文件不超过 10MB；超出限制或类型不符返回业务码 {@code 40001}。
 *
 * <b>契约缺口如实记录（用户裁决 2026-10-14 晚已复核，维持待治理状态）</b>：裁决口径为
 * "以 api-spec.md 第 2 节校验规则表中规定的'固定文案'为唯一权威准则"。经逐条核对：§2 校验规则表
 * （字段规则表 + 「文件上传」小节 L237）只给出触发场景与业务码 {@code 40001}，<b>未收录本三条
 * message 的固定文案</b>——按裁决指令"契约确实未收录的条目保留现文案"，三条文案维持现状不改，
 * 缺口已登记 docs/record/会话记录-backend-20261014.md，待 tech-lead 走文档治理；改动即视为改契约。
 *
 * <p>本类只放编译期常量（{@code static final} 字面量），因此扩展名白名单不在这里——
 * 它是运行期 {@code Set.of(...)} 初始化，留在服务实现里，避免常量类因带 {@code <clinit>} 而进覆盖率统计。
 */
public final class FileUploadConstraints {

    /** 业务上限：10MB = 10*1024*1024 字节；<b>恰好 10MB 合法</b>（契约措辞是"不超过"，非"小于"） */
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** multipart 字段名（契约 §1.6 请求参数表） */
    public static final String PART_NAME = "file";

    public static final String MESSAGE_FILE_MISSING = "请选择要上传的文件";
    public static final String MESSAGE_TYPE_NOT_ALLOWED = "文件类型不支持，仅支持 pdf/jpg/jpeg/png 格式";
    public static final String MESSAGE_SIZE_EXCEEDED = "文件大小不能超过10MB";

    private FileUploadConstraints() {
    }
}
