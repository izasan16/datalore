# Frontend UI/UX Layout Redesign Implementation Plan

> For agentic workers: use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Reorganize the existing daaspro frontend so content-driven pages no longer use oversized empty cards, while preserving current data, API publishing, and authorized-application workflows.

**Architecture:** Keep React Router, Arco Design, existing API modules, and the workspace shell. Add shared layout/state classes, then update pages in dependency order: shell, dashboard, data pages, SQL Lab, and API pages. No backend or API contract changes.

**Tech Stack:** React 18, TypeScript, React Router, Arco Design, Vite, Axios.

---

## File map

- Modify web/src/pages/workspace/Layout.tsx: navigation visibility and shell layout.
- Modify web/src/global.css: shared page, state, responsive, and height rules.
- Modify web/src/pages/workspace/index.tsx: compact shortcut cards and workspace information.
- Modify web/src/pages/datasource/index.tsx and create.tsx: natural-height list and grouped form.
- Modify web/src/pages/dataset/index.tsx and new.tsx: preserve split pane, reduce empty space.
- Modify web/src/pages/sqllab/index.tsx: preserve editor fill, compact result state.
- Modify web/src/pages/apipub/index.tsx and new.tsx: compact list and grouped creation form.
- Modify web/src/pages/apipub/detail.tsx and apps.tsx: overview-first detail and app management.
- Modify web/src/App.tsx only if a small route-fallback consistency adjustment is needed; do not change route paths.

Verification commands:

    Set-Location D:\极智\daaspro\web
    npm run build
    Set-Location D:\极智\daaspro
    git diff --check

Expected: build exits 0 and diff check reports no whitespace errors.

---

### Task 1: Add shared layout and state primitives

Files:
- Modify web/src/global.css

- [ ] Add page-level CSS primitives: page-shell, page-header, page-header-main, page-header-actions, page-section, page-empty, page-error, compact-card-grid, compact-entry-card, and bounded-checks. Use existing color variables, a 20px page rhythm, content-driven height by default, and a max-width: 900px media query that stacks grids and lets header actions wrap. bounded-checks must use max-height: 220px; overflow-y: auto.
- [ ] Keep card-fill and page-fill for SQL Lab and the data-management tree, and add a comment that these are only for editors, results, trees, or regions requiring an internal scroll area. Do not make generic cards full height.
- [ ] Run npm run build from D:\极智\daaspro\web. Expected: PASS.
- [ ] Commit: git add web/src/global.css; git commit -m "style: add shared content-driven page layout rules".

### Task 2: Simplify the workspace shell and dashboard

Files:
- Modify web/src/pages/workspace/Layout.tsx
- Modify web/src/pages/workspace/index.tsx

- [ ] Remove workflow and settings from MENU, remove the gray-menu explanatory text, and replace the API Publish IconCloud with a distinct API-oriented Arco icon such as IconApi. Keep available route keys and menu navigation unchanged.
- [ ] Keep avatar, role, and logout. Add a left-aligned current module title derived from location.pathname, retaining right-aligned user controls. Keep 56px height and safe wrapping.
- [ ] Use page-shell, page-header, compact-card-grid, compact-entry-card, and a compact “工作区信息” section. Remove dashboard height: 100%, flex: 1, and justifyContent: space-between. Remove tenantId from user-facing text while preserving the three routes.
- [ ] Run npm run build and inspect /workspace. Confirm cards are content-height and workspace information is visible in the first viewport.
- [ ] Commit: git add web/src/pages/workspace/Layout.tsx web/src/pages/workspace/index.tsx; git commit -m "style: compact workspace dashboard and navigation".

### Task 3: Normalize data source and data management layouts

Files:
- Modify web/src/pages/datasource/index.tsx
- Modify web/src/pages/datasource/create.tsx
- Modify web/src/pages/dataset/index.tsx
- Modify web/src/pages/dataset/new.tsx

- [ ] Replace the data-source full-height Card with page-shell, page-header, and natural-height page-section. Keep refresh, create, table browsing, loading, and error behavior. Render an empty state with the existing create action.
- [ ] Keep all data-source fields and API calls. Add “基本信息” and “连接信息” labels, with name/type in the first group and connection credentials in the second. Keep “测试连接” secondary and “保存” primary. Allow actions to wrap below 900px.
- [ ] Keep the source tree and dataset list as navigable panes, but avoid making no-data states fill the viewport. Add visible retry text/action for initial list failures and keep tree internal scrolling.
- [ ] Preserve dataset upload, preview, and validation. Use a page header and natural-height form section. Remove duplicate return/cancel actions; keep one navigation escape and one primary save action. Bound upload preview with internal scrolling.
- [ ] Run npm run build; inspect /datasource, /datasource/new, /datamanage, and /datamanage/new at desktop and narrow width.
- [ ] Commit: git add web/src/pages/datasource web/src/pages/dataset; git commit -m "style: improve datasource and dataset page density".

### Task 4: Refine SQL Lab without breaking editor sizing

Files:
- Modify web/src/pages/sqllab/index.tsx

- [ ] Retain flex-fill for editor, script list, and result pane where remaining-height allocation is required. Remove full-height styling from title, toolbar, and non-scrolling empty wrappers.
- [ ] When no query has run or results are empty, render a short page-empty message inside the result pane. When execution fails, render an inline page-error and retain the existing run action.
- [ ] Give script delete icon buttons title and aria-label without changing deletion behavior.
- [ ] Run npm run build, then commit: git add web/src/pages/sqllab/index.tsx; git commit -m "style: keep SQL Lab dense while preserving editor fill".

### Task 5: Redesign API list and creation page

Files:
- Modify web/src/pages/apipub/index.tsx
- Modify web/src/pages/apipub/new.tsx

- [ ] Use page-shell, page-header, and natural-height page-section for the API list. Keep create and refresh actions. Show API name, path, dataset, status, updated time, and row actions. Empty state must directly reach /apipub/new.
- [ ] Replace pseudo-link click navigation with a React Router Link or explicit button targeting /apipub/[id]; do not change route behavior.
- [ ] In apipub/new.tsx remove duplicate bottom cancel when the header has the return action. Keep publish as the only primary action. Add “基础信息”, “数据权限”, and “调用限制” labels while preserving payload and validation.
- [ ] Wrap exposed/filter checkbox groups in bounded-checks. Add 全选 and 清空 actions that update form fields. Keep filter choices limited to exposed columns and keep max rows beside the call-limit label.
- [ ] Track loading/error around listDatasets. On failure render page-error with retry; do not silently catch. Disable dataset selection while loading and preserve column-loading errors.
- [ ] Run npm run build; inspect /apipub and /apipub/new; verify selectors are bounded, failure is visible, and publish navigates to the detail route.
- [ ] Commit: git add web/src/pages/apipub/index.tsx web/src/pages/apipub/new.tsx; git commit -m "style: compact API list and creation form".

### Task 6: Reorganize API detail and authorized-application management

Files:
- Modify web/src/pages/apipub/detail.tsx
- Modify web/src/pages/apipub/apps.tsx

- [ ] Keep back, enable/disable, and delete actions in page-header. Show API name, status, endpoint, HTTP method, and dataset in the overview.
- [ ] Remove height: 100% from detail columns/cards. Render natural-height sections in order: 调用配置, 授权应用, 调用示例. A desktop grid is allowed only when both columns have content; authorized applications must never be stretched.
- [ ] Keep checkbox selection, 保存授权, 查看密钥, and 应用管理 navigation. If no apps exist, show a compact empty state with a create/manage button. Keep the 404 security note and add accessible names to credential actions.
- [ ] Replace the app giant full-height card with a page header, compact create section, and natural-height table. Keep create, refresh, copy, one-time-secret modal, and delete behavior. Add explicit empty state and accessible AppKey copy action. Keep secret behavior unchanged.
- [ ] Track initial listApps errors and render a retryable inline error instead of silently showing an empty table.
- [ ] Run npm run build. Verify /apipub/[id] has no oversized right card, and create app -> authorize app -> save authorization remains usable.
- [ ] Commit: git add web/src/pages/apipub/detail.tsx web/src/pages/apipub/apps.tsx; git commit -m "style: reorganize API details and authorized applications".

### Task 7: Responsive and accessibility pass

Files:
- Modify web/src/global.css
- Modify any changed page file where an icon-only or non-semantic action remains

- [ ] Inspect /workspace, /datasource, /datamanage, /sqllab, /apipub, /apipub/new, /apipub/[id], and /apipub/apps at desktop width and below 900px. Confirm headers wrap, grids stack, tables scroll when necessary, and primary actions remain visible.
- [ ] Every icon-only refresh/copy/delete/action button gets title and aria-label. Every clickable row action is a button or router link. Status indicators include text, not color alone.
- [ ] Run from D:\极智\daaspro\web: npm run build. Run from D:\极智\daaspro: git diff --check and git status --short. Expected: build succeeds and diff check is clean.
- [ ] Commit: git add web/src/global.css web/src/pages; git commit -m "fix: complete frontend layout and accessibility pass".

## Plan self-review

- Spec coverage: workspace shell, dashboard, data source, data management, SQL Lab, API list, API creation, API detail, authorized applications, responsive rules, loading/empty/error states, and accessibility each have an explicit task.
- Scope: only frontend presentation and interaction is changed; API routes, persistence, and authorization semantics remain intact.
- Placeholder scan: no TBD, TODO, or unspecified implementation step is required.
- Type consistency: existing API types and route paths are reused; page-local state does not alter shared interfaces.
- Verification: every task includes a build check or route-level validation, with final production build and diff check before completion.

