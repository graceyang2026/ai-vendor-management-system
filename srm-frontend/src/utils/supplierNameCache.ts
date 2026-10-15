/**
 * 供应商 ID → 名称 前端映射缓存（模块三 F2/F3 列表列专用；用户 2026-10-15 裁决 B3 配套口径：
 * 绩效出参只有 supplier_id（契约附录 A4.2），供应商名经既有 §1.3 列表数据预置或 §1.4 详情懒查缓存补齐，
 * **不新增后端接口**）。
 *
 * 设计约束：
 * - 模块级 reactive Map，业务员/审核员两个面板共享，避免各自重复拉取；
 * - 失败不缓存（他人私有单 40302、注销 40401 等），列展示回退 `#<id>` 占位，下次进入视图自动重试；
 * - 并发去重：同一 ID 的在途请求复用，防列表渲染风暴；
 * - 只读缓存不参与任何权限判断，真正的行级隔离在后端（§0）。
 */

import { reactive } from 'vue'
import { getSupplierDetailApi } from '@/api/supplier'
import type { SupplierItem } from '@/types/supplier'

const supplierNameById = reactive(new Map<number, string>())
const inflightById = new Map<number, Promise<void>>()

/** §1.3 列表数据预置（录入页目标供应商下拉加载成功后顺带灌缓存，列表列命中免查详情）。 */
export function primeSupplierNames(list: Pick<SupplierItem, 'id' | 'name'>[]): void {
  for (const item of list) {
    supplierNameById.set(item.id, item.name)
  }
}

/** 同步取显示名：缓存命中返回名称；未命中返回 `#<id>` 占位（调用方应先 ensureSupplierNames 预热）。 */
export function resolveSupplierName(id?: number | null): string {
  if (id === undefined || id === null) return '-'
  return supplierNameById.get(id) ?? `#${id}`
}

/**
 * 批量补齐缓存：对未命中且无在途请求的 ID 逐个调 §1.4 GET /suppliers/{id}（ALL 角色，§0 隔离）。
 * 单条失败静默吞掉（保持 #id 占位），不打断列表渲染；成功后 reactive Map 触发列自动刷新。
 */
export async function ensureSupplierNames(ids: (number | undefined | null)[]): Promise<void> {
  const pending: Promise<void>[] = []
  for (const id of ids) {
    if (id === undefined || id === null) continue
    if (supplierNameById.has(id)) continue
    const inflight = inflightById.get(id)
    if (inflight) {
      pending.push(inflight)
      continue
    }
    const task = getSupplierDetailApi(id)
      .then((res) => {
        if (res.data) {
          supplierNameById.set(id, res.data.name)
        }
      })
      .catch(() => {
        // 40302/40401 等：私有在途区/已注销，保持 #id 占位；不入缓存，下次视图刷新可重试
      })
      .finally(() => {
        inflightById.delete(id)
      })
    inflightById.set(id, task)
    pending.push(task)
  }
  await Promise.all(pending)
}
