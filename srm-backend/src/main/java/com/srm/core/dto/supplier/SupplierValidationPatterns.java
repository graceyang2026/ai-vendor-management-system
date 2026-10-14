package com.srm.core.dto.supplier;

/**
 * 供应商入参校验的<b>唯一常量源</b>（docs/api-spec.md §2 校验表 L158-172，裁决 20261010）。
 *
 * 铁律：后端 Bean Validation 与前端表单正则必须逐字同源，两侧不得各写一套；
 * 因此正则与固定中文 message 只在本文本里出现一次，Create/Update 两个 DTO 共用，
 * 避免"建档一套规则、编辑另一套规则"的漂移。message 必须与契约表格逐字一致
 * （验收点 §1.7：40001 随 HTTP 200 返回，错信息在统一响应体 code/message 里）。
 */
public final class SupplierValidationPatterns {

    // ==================== 正则（契约 §2 校验表原文） ====================

    /** name / contact_name：首尾非空白，含中间共 2~64 字符 */
    public static final String NAME_REGEX = "^\\S.{0,62}\\S$";

    /** tax_no：GB 32100 十八位，数字 + 大写字母，不含 I/O/Z/S/V */
    public static final String TAX_NO_REGEX = "^[0-9A-HJ-NPQRTUWXY]{18}$";

    /** type：只认 SupplierType 三码，中文文案不接受（裁决 20261012 存码显中文） */
    public static final String TYPE_REGEX = "^(MANUFACTURING|SERVICE_OUTSOURCING|DISTRIBUTOR)$";

    /** contact_phone：中国大陆 11 位手机号（MVP 不强求座机） */
    public static final String PHONE_REGEX = "^1[3-9]\\d{9}$";

    /** contact_email：选填，填则必须合法；空串视为未填写放行 */
    public static final String EMAIL_REGEX = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    /** effective_date / expiry_date：yyyy-MM-dd 语法校验（语义合法性见 DATE_MSG 的跨字段校验） */
    public static final String DATE_REGEX = "^\\d{4}-\\d{2}-\\d{2}$";

    // ==================== 固定中文 message（逐字照抄契约表格） ====================

    public static final String NAME_MSG = "供应商名称格式不合法（不得为空格且长度需为2~64字符）";
    public static final String TAX_NO_MSG = "统一社会信用代码格式不合法（必须为18位有效字符）";
    public static final String TYPE_MSG = "供应商类型不合法";
    public static final String CONTACT_NAME_MSG = "联系人姓名格式不合法（不得为空格且长度需为2~64字符）";
    public static final String CONTACT_PHONE_MSG = "联系电话格式不合法（必须为11位有效手机号）";
    public static final String CONTACT_EMAIL_MSG = "联系邮箱格式不合法";
    public static final String DATE_MSG = "日期格式不合法或失效日期早于生效日期";

    private SupplierValidationPatterns() {
    }
}
