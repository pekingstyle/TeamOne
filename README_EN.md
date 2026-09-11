# TeamOne · All-in-One R&D Collaboration Platform (Full-Stack)

<div align="center">

**Streamline Your R&D Workflow | OKR · Roadmap · Sprint · DevOps**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Web](https://img.shields.io/badge/web-React_19_+_Vite-blue.svg)]()
[![Backend](https://img.shields.io/badge/backend-Spring_Boot_3.5-blueviolet.svg)]()
[![CI](https://img.shields.io/badge/CI-self--hosted_quality_gate-success.svg)]()

[中文](README.md) | [English](README_EN.md)

</div>

> Source of the English README for the public GitHub mirror (synced by `deploy/sync-github.sh`).

---

**TeamOne** is a Git-based all-in-one R&D collaboration platform. It brings the full delivery loop online — **strategic goals → requirements → RoadMap → releases → sprints → tasks / test tasks / defects → code review → CI/CD → artifact release** — and ties team collaboration together with **object-centric topics**.

> Demo scenario: the TeamOne team builds TeamOne with TeamOne itself (dogfooding).

## 📁 Repository layout

```
├── web/              # Front-end (React 19 + TS + Vite + Tailwind v4) — interactive product UI
├── server/           # Back-end (Spring Boot 3.5 + JDK 17, seven-module modular monolith)
└── deploy/           # Deployment & ops: compose (Valkey/MinIO), start/stop scripts,
                      # PG bootstrap/backup, self-hosted CI (ci.sh), Git kernel bootstrap
```

## ✨ Feature overview

- **Product R&D**: strategic goals (5-level drill-down), requirements with a review flow, RoadMap (goal/release dual views), sprints & mixed kanban of task / test task / defect, defect center with release gate, releases with automatic blocking
- **Team collaboration**: instant messaging (channel / topic / DM with archival folding), team & permissions (department × role × resource-level ACL matrix)
- **Engineering foundation**: code repository browsing, WorkTree & baseline management, code review with unit-test gate (dual coverage thresholds + exemption), CI/CD pipeline views
- **Insights**: dashboard, conflict center (CF-1~6 rules), statistics reports (burndown / cumulative flow / control chart / velocity / defect distribution / resource input) with CSV export

## 📷 Screenshots

See the [Chinese README](README.md) for the full screenshot gallery (`web/docs/screenshots/`).

## 🚀 Getting started

```bash
# Front-end (interactive UI)
cd web && npm install && npm run dev        # http://localhost:5173

# Back-end (auth / JWT / ACL / WebSocket / Flyway) — requires an external PostgreSQL
bash deploy/dev.sh run                      # http://localhost:8080

# Quality gate (self-hosted CI)
bash deploy/ci.sh                           # unit tests + ArchUnit rules
bash deploy/ci.sh --full                    # plus Testcontainers integration tests
```

Dev seed accounts (auto-created on first start): `admin / Admin@123`, `dev1 / Dev@12345`, `dev2 / Dev@12345`.

## 🧱 Tech stack

**Front-end**: React 19 + TypeScript + Vite · Tailwind CSS v4 · in-memory store with `useSyncExternalStore`
**Back-end**: Spring Boot 3.5.7 + JDK 17 modular monolith (ArchUnit-enforced dependencies) · PostgreSQL + Flyway · JWT + revocable refresh tokens (rotation, Valkey-backed) · four-step short-circuit authorization chain with resource-level ACL · self-built WebSocket gateway · transactional outbox → Valkey Stream (in progress)
**Git kernel (self-built)**: no external Git service — bare repositories + direct git command integration behind a `GitPort` abstraction; pushes are driven by native `post-receive` hooks into TeamOne events.

## 🧭 Milestones

- ✅ **M0 skeleton**: project layout, DB migrations, login/ACL matrix, WebSocket skeleton, error envelope
- 🔄 **M1 vertical slice (in progress)**: engineering groundwork done (ArchUnit, Testcontainers regression, Valkey, OpenAPI, error-code catalog, self-hosted CI); next: prd domain state machines and the release gate
- Design documents are maintained in the internal repository and are not published here.

## 📄 License

Apache-2.0 (demo and learning use). The Git kernel implementation references Gitea (MIT) as a design source; attribution details live in the internal NOTICE.
