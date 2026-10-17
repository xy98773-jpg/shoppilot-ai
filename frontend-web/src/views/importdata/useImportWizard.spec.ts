// 导入向导状态机单测（`M3-B10` 验收点：上传 → 预览 → 映射 → 校验 的步骤推进）。
//
// 打桩 `@/api/importdata`：本用例只验证「步骤迁移 + 各步错误数」，不打真后端
// （真栈联调证据在 `frontend-web/tools/b10/`）。
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/importdata', () => ({
  uploadBatch: vi.fn(),
  previewBatch: vi.fn(),
  putMapping: vi.fn(),
  validateBatch: vi.fn(),
  confirmBatch: vi.fn(),
  listBatches: vi.fn(),
  getBatch: vi.fn(),
  listBatchErrors: vi.fn(),
  exportBatchErrors: vi.fn(),
  listTemplates: vi.fn(),
}))

import { confirmBatch, previewBatch, putMapping, uploadBatch, validateBatch } from '@/api/importdata'
import type { ImportBatchUpload, ImportPreview, ValidationResult } from '@/types/importdata'
import { WIZARD_STEPS, useImportWizard } from './useImportWizard'

const uploadFixture: ImportBatchUpload = {
  batch_id: 9001,
  batch_no: 'IMP-20261018-0001',
  biz_type: 'SKU',
  file_name: 'sku.csv',
  file_hash: 'abc',
  file_size: 128,
  status: 'UPLOADED',
  created_at: '2026-10-18T00:00:00.000Z',
}

const previewFixture: ImportPreview = {
  batch_id: 9001,
  status: 'PARSED',
  biz_type: 'SKU',
  template_version: 'v1',
  total_rows: 3,
  preview_rows: 3,
  columns: [
    { index: 0, source_name: 'SKU编码', mapped_field: 'sku_code' },
    { index: 1, source_name: '名称', mapped_field: null },
  ],
  preview: [
    { row_no: '1', SKU编码: 'SKU-A', 名称: '甲' },
    { row_no: '2', SKU编码: 'SKU-B', 名称: '乙' },
    { row_no: '3', SKU编码: 'SKU-C', 名称: '丙' },
  ],
  suggested_mapping: { sku_code: 'SKU编码' },
  sheets: [],
  sheet_name: null,
  encoding: 'UTF-8',
}

const validationFixture: ValidationResult = {
  batch_id: 9001,
  status: 'VALIDATED',
  total_rows: 3,
  error_rows: 1,
  warning_rows: 2,
  errors: [
    {
      row_no: 2,
      error_level: 'ERROR',
      error_code: 'CAT-3001',
      error_field: 'sku_code',
      error_message: 'SKU 编码不存在',
    },
  ],
}

describe('useImportWizard 步骤推进', () => {
  beforeEach(() => {
    vi.mocked(uploadBatch).mockResolvedValue(uploadFixture)
    vi.mocked(previewBatch).mockResolvedValue(previewFixture)
    vi.mocked(putMapping).mockResolvedValue({
      batch_id: 9001,
      status: 'MAPPED',
      field_mapping_json: { sku_code: 'SKU编码' },
    })
    vi.mocked(validateBatch).mockResolvedValue(validationFixture)
    vi.mocked(confirmBatch).mockResolvedValue({
      data: {
        batch_id: 9001,
        status: 'COMPLETED',
        total_rows: 3,
        success_rows: 3,
        failed_rows: 0,
        started_at: null,
        finished_at: null,
      },
      replayed: false,
    })
  })

  it('上传 → 预览 → 映射 → 校验 → 确认，五步依次推进并记录错误数', async () => {
    const wizard = useImportWizard()
    expect(wizard.step.value).toBe(0)
    expect(WIZARD_STEPS[wizard.step.value]).toBe('上传')

    // 1) 上传（内含 preview）：0 → 1
    const file = new File(['sku_code\nSKU-A'], 'sku.csv', { type: 'text/csv' })
    await wizard.upload(file, 'SKU')
    expect(wizard.step.value).toBe(1)
    expect(wizard.batchId.value).toBe(9001)
    expect(wizard.preview.value?.preview_rows).toBe(3)
    // 自动映射建议填入表单
    expect(wizard.mapping.sku_code).toBe('SKU编码')
    expect(wizard.errorCounts.value.previewRows).toBe(3)

    // 2) 预览 → 映射（纯前端翻页，无请求）
    wizard.goMapping()
    expect(wizard.step.value).toBe(2)

    // 3) 提交映射：2 → 3
    await wizard.submitMapping()
    expect(wizard.step.value).toBe(3)
    expect(vi.mocked(putMapping)).toHaveBeenCalledWith(9001, {
      field_mapping_json: { sku_code: 'SKU编码' },
    })

    // 4) 校验：3 → 4，错误/警告数来自服务端结果
    await wizard.runValidate()
    expect(wizard.step.value).toBe(4)
    expect(wizard.errorCounts.value.errorRows).toBe(1)
    expect(wizard.errorCounts.value.warningRows).toBe(2)
    expect(wizard.errorCounts.value.totalRows).toBe(3)

    // 5) 确认（带幂等键）
    const result = await wizard.confirm('idem-key-1')
    expect(result.replayed).toBe(false)
    expect(wizard.confirmResult.value?.status).toBe('COMPLETED')
    expect(vi.mocked(confirmBatch)).toHaveBeenCalledWith(9001, 'idem-key-1')
  })

  it('上传失败时停在步骤 0 并保留服务端错误码', async () => {
    vi.mocked(uploadBatch).mockRejectedValue(
      Object.assign(new Error('该文件已导入过'), { code: 'IMP-4004' }),
    )
    const wizard = useImportWizard()
    const file = new File(['x'], 'dup.csv', { type: 'text/csv' })

    await expect(wizard.upload(file, 'SKU')).rejects.toThrow('该文件已导入过')
    expect(wizard.step.value).toBe(0)
    expect(wizard.errorMessage.value).toBe('[IMP-4004] 该文件已导入过')
  })
})
