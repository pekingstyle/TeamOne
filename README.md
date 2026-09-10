# TeamOne · 一站式研发协同平台（交互原型）

<div align="center">

**Streamline Your R&D Workflow | OKR · Roadmap · Sprint · DevOps**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Web-green.svg)]()
[![Status](https://img.shields.io/badge/status-stable-brightgreen.svg)]()

[中文](README.md) | [English](README_EN.md)

</div>

---

基于 Git 的一站式研发协同平台 **TeamOne** 的可交互前端原型：把「战略目标 → 需求 → RoadMap → 版本 → 迭代 → 任务/测试任务/缺陷 → 代码评审 → CI/CD → 制品发布」的研发全流程搬到线上闭环，并以**对象化话题**串联团队协同。

> 演示场景：TeamOne 研发团队使用 TeamOne 开发 TeamOne 自己（Dogfooding）。

## ✨ 功能一览

- **产品研发**：战略目标（五层下钻）、需求管理（评审流程：提交→多评审人通过→受理→排期）、RoadMap（目标/版本双视角 + 可横移时间轴 + 发布挤压预警）、迭代与任务（任务/测试任务/缺陷混排看板 + 容量条 + Deadline 越级预警）、缺陷中心（严重度彩标 + 阻塞版本门禁 + 四联跳转）、版本与发布（发布门禁：致命/严重缺陷未关闭自动锁定发布）
- **团队协同**：即时沟通（频道/话题/私聊三分类，归档折叠；消息可转话题、对象引用卡可跳转）、团队与权限（部门 × 角色 × 资源级 ACL 权限矩阵）
- **工程底座**：代码仓库（文件/提交/分支 + **WorkTree 工作副本与关系图** + **基线管理**：审批定版冻结）、代码评审（逐行 Diff、多评审人、**单元测试门禁**：覆盖率双阈值 + 豁免流程、rebase 拦截、冲突解决记录）、CI/CD（多阶段流水线模拟执行 + Job 日志）
- **概览**：工作台（目标进度/我的冲突/发布门禁）、冲突中心（人员负载热力图 + 六类冲突判定 CF-1~6）、统计报表（燃尽/累积流/控制图/速率/缺陷分布/资源投入 + 绩效表 + CSV 导出）

## 🚀 运行

```bash
npm install
npm run dev      # 开发模式（默认 http://localhost:5173）
npm run build    # 生产构建 → dist/
npm run preview  # 本地预览构建产物
```

## 🧱 技术栈

- React 19 + TypeScript + Vite
- Tailwind CSS v4（Fancy 配色主题，`[data-theme="dark"]` 保留深色）
- lucide-react 图标；无后端、无其他运行时依赖
- 内存数据仓库（`src/data/store.ts`）+ `useSyncExternalStore` 订阅刷新，模拟实时：流水线执行、环境部署、IM 回复、话题自动归档
- 冲突检测 `computeConflicts()`、话题干系人 `stakeholdersFor()` 等均为纯函数，可平移至后端

## 📁 结构

```
src/
├── data/           # 类型定义 + 内存数据仓库（种子数据/动作/纯函数算法）
├── components/     # 共享 UI 原子（Avatar/Pill/ProgressRing/HeatCell…）
├── features/       # 页面模块（dashboard/goals/requirements/roadmap/tasks/
│                   #   defects/delivery/topics(im)/team/conflicts/reports/
│                   #   repos/review/cicd）
├── nav.ts          # 导航配置与页面 props 约定
└── App.tsx         # 应用外壳（侧栏主线轨道导航 + 顶栏 + 路由）
```

## 📄 License

Apache-2.0（与仓库 License 徽章一致，仅用于原型演示与学习交流）
