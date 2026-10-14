package com.srm.core.dto.qualification;

/**
 * 资质上传入参校验的<b>唯一常量源</b>（契约 §1.7 请求体字段表，docs/api-spec.md §2 资质 Qualification）。
 *
 * 铁律（同 SupplierValidationPatterns）：后端 Bean Validation 与前端表单校验必须逐字同源，
 * 正则与固定中文 message 只在本文本里出现一次。
 *
 * <b>契约缺口登记</b>：§1.7 契约的 Body 字段表只给了枚举值域（doc_type）与"§1.6 返回的 URL"
 * （file_url），错误码矩阵也只列 `403/40302` 与 `404/40401`——<b>未对 doc_type / file_url 的
 * 校验失败给出固定 message 原文</b>（§0 错误码总表虽把 40001 关联到 §1.7，但无 message 列）。
 * 因此下方 DOC_TYPE_MSG / FILE_URL_MSG 为本代理按同族风格自拟的固定文案，集中在单一常量源，
 * 契约方给出正式文案后只改这两个常量、测试断言同批对齐（照 B2 §1.6 上传文案的处理范式）。
 * effective_date / expiry_date 的 DATE_MSG 则复用 supplier 域既有常量，避免同一日期规则两套文案漂移。
 */
public final class QualificationValidationPatterns {

    // ==================== 正则（契约 §1.7 字段表原文值域） ====================

    /** doc_type：只认两码（BUSINESS_LICENSE 营业执照 / OTHER 其他），中文/小写不接受 */
    public static final String DOC_TYPE_REGEX = "^(BUSINESS_LICENSE|OTHER)$";

    /** effective_date / expiry_date：yyyy-MM-dd 语法校验（语义合法性由 DTO 跨字段校验兜） */
    public static final String DATE_REGEX = "^\\d{4}-\\d{2}-\\d{2}$";

    // ==================== 固定中文 message ====================

    /** 契约未收录本条 message（见类头缺口登记）——待正式文案替换 */
    public static final String DOC_TYPE_MSG = "资质类型不合法（仅支持 BUSINESS_LICENSE/OTHER）";

    /** 契约未收录本条 message（见类头缺口登记）——file_url 为 §1.6 上传返回值，必填 */
    public static final String FILE_URL_MSG = "资质文件地址不能为空";

    /**
     * 日期规则复用 supplier 域既有固定文案（api-spec §2 校验表 L169：
     * "日期格式不合法或失效日期早于生效日期"），资质与供应商同名字段虽<b>不同义</b>，
     * 但<b>校验规则与文案完全一致</b>，直接引用单一事实源防止两处漂移。
     */
    public static final String DATE_MSG = com.srm.core.dto.supplier.SupplierValidationPatterns.DATE_MSG;

    private QualificationValidationPatterns() {
    }
}
