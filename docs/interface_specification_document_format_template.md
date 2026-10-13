# 接口契约文档生成格式模板（发给 Tech Lead / 子代理的标准 Format）

请严格按照以下 **Markdown 标准结构** 生成/补齐 `docs/api-spec.md` 或各模块的《接口与字段速查.md》文档，确保前后端与评审专家能够一目了然地查阅。

---

## 模块标准模板样例

### §X.Y [接口名称] (例如：§4.3 绩效评价复核)

#### 1. 接口基本信息
- **接口路径**: `POST /api/v1/performance/evaluations/{id}/review`
- **请求方式**: `POST`
- **鉴权与角色**: 需 Header 携带 `Authorization: Bearer <Token>`，允许角色：`ADMIN`, `AUDITOR`
- **业务约束与铁律**:
  - 只能复核处于 `PENDING_REVIEW` 状态的单据，非法状态复核直接阻断。
  - 乐观锁控制：请求头或 Request Body 需带上 `version`，并发冲突返回 `40901`。

#### 2. 请求参数 (Request)

##### Path Parameters
| 参数名 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `id` | `Long` | 是 | 绩效评价单据 ID |

##### Request Header
| Header 名称 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `Authorization` | `String` | 是 | Bearer Token |

##### Request Body (`application/json`)
```json
{
  "version": 1,
  "action": "APPROVE",
  "review_comments": "经核实事实数据无误，予以批复通过。"
}
```

##### Body 字段说明
| 字段名 | 类型 | 必填 | 枚举/校验规则 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `version` | `Integer` | 是 | `>= 1` | 乐观锁版本号（并发控制） |
| `action` | `String` | 是 | `APPROVE` / `REJECT` | 审复核动作 |
| `review_comments` | `String` | 否 | Max 500 chars | 审复核意见 |

---

#### 3. 响应参数 (Response)

##### 成功响应 (`HTTP 200 OK`)
```json
{
  "code": 20000,
  "message": "success",
  "data": {
    "id": 10024,
    "supplier_id": 5001,
    "period": "2026-Q3",
    "total_score": 92.50,
    "rating": "A",
    "quality_exempt": true,
    "mode": "manual",
    "status": "APPROVED",
    "version": 2,
    "reviewed_by": 102,
    "reviewed_at": "2026-10-13T10:15:00Z"
  }
}
```

##### Data 核心字段说明
| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `total_score` | `BigDecimal` | 最终综合得分 (Q×30% + D×25% + P×20% + S×15% + C×10%) |
| `rating` | `String` | 评级 (`A` / `B` / `C` / `D`) |
| `quality_exempt`| `Boolean` | 免考标识（`true`: 存在质量或交付零业务免考） |
| `mode` | `String` | 数据生成模式（`manual`: 手工录入 / `mock`: 系统模拟） |
| `status` | `String` | 当前单据状态（`APPROVED` / `REJECTED`） |
| `version` | `Integer` | 更新后的乐观锁版本号 |

---

#### 4. 异常响应与错误码矩阵 (Error Cases)

| HTTP 状态码 | 业务 Code | 错误信息 (Message) | 触发场景/原因 |
| :--- | :--- | :--- | :--- |
| `400 Bad Request` | `40001` | `Invalid parameter: action cannot be null` | 入参校验失败 |
| `403 Forbidden` | `40302` | `Illegal status transition: evaluation is already APPROVED` | 非法状态复核（对非 PENDING_REVIEW 状态复核） |
| `404 Not Found` | `40401` | `Performance evaluation not found` | 评估单据不存在 |
| `409 Conflict` | `40901` | `Optimistic lock conflict, please refresh and retry` | 乐观锁版本号不匹配（并发冲突） |