/**
 * 全站业务术语表（唯一维护点）——用户反馈「英文简称没有地方看和维护」（UT-31）：
 * 各页面（冲突中心 / RoadMap / 代码评审）通过 <GlossaryButton /> 弹窗展示本表，
 * 新增术语只改这一个文件。
 */
export interface GlossaryTerm {
  /** 简称 / 代码，如 CF-1 */
  code: string
  /** 业务名称 */
  name: string
  /** 人话解释 */
  desc: string
}

export interface GlossaryGroup {
  /** 所属功能域 */
  domain: string
  terms: GlossaryTerm[]
}

export const GLOSSARY: GlossaryGroup[] = [
  {
    domain: '冲突中心',
    terms: [
      { code: 'CF-1', name: '人员超载', desc: '某成员某一天被排配的工时超过了其每日可用容量，忙不过来。建议把当天部分任务顺延或转派他人。' },
      { code: 'CF-2', name: '时间区间重叠', desc: '同一成员的两条工作项时间区间交叠（跨迭代或同时进行中），需要错峰安排或转派。' },
      { code: 'CF-3', name: '里程碑挤压', desc: '里程碑到期前剩余的工作量大于剩余日历工时容量，按当前排期无法按期交付。' },
      { code: 'CF-4', name: '跨产品争用', desc: '同一成员在同一时段被多个产品/项目同时占用。' },
      { code: 'CF-5', name: '依赖倒挂', desc: '被依赖的工作项排期晚于依赖方，执行顺序颠倒。' },
      { code: 'CF-6', name: 'Deadline 越级', desc: '工作项自身的截止日期晚于其所属里程碑/版本的截止日期。' },
      { code: 'FP', name: '冲突指纹', desc: '系统为同类冲突计算的去重键，避免每次重算都产生重复记录。' },
    ],
  },
  {
    domain: '代码评审',
    terms: [
      { code: 'R8 门禁', name: '单测检测门禁', desc: '合并前检查 MR 的单测情况：整体覆盖率与 patch 覆盖率需同时达标（阈值见 MR 详情「检查规则说明」，默认 60% / 80%）。' },
      { code: '整体覆盖率', name: '全量覆盖率', desc: '整个仓库被单测执行覆盖的代码行占比，由 CI 单测作业统计并回传，平台不做计算。' },
      { code: 'patch 覆盖率', name: '变更覆盖率', desc: '本次 MR 变更的代码行中，被单测执行覆盖到的行数占比——衡量「新改的代码有没有被测到」。' },
      { code: '启发式 A', name: '单测文件识别', desc: 'MR 变更文件路径中包含 /test/、.test. 或以 Test.java 结尾时，视为「附带单测」；未命中则提示未检测到关联单测文件。' },
      { code: '豁免', name: '门禁豁免', desc: '未达门禁时由评审人审批后放行合并，豁免原因与批准人留审计记录；未豁免时合并按钮保持禁用。' },
    ],
  },
]
