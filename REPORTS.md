# Reports — one per change, always in this repository

**Standing rule (from 2026-11-13):** every change to the code, tests, data model or behaviour is
delivered together with a report that lives **in this repository** — same branch, same push as the
change — and is indexed below. No change is "done" while its report exists only in a chat, in a
commit message or in the PR description.

A report is a Markdown file at the repository root, named after its phase (`P0_…`, `P0A_…`, `P1_…`,
and so on), and it must answer:

| Section | Contents |
| --- | --- |
| Scope | what phase/item this is, and what is explicitly **not** touched |
| Base commit | the exact SHA the work started from |
| Root cause / finding | the verified defect, with file:line evidence — not a guess |
| Fix / design | what changed and **why that shape**, including rejected alternatives |
| Files changed | path + one line of why |
| Tests added | test name + the property it pins |
| Verification | the exact command, and the CI run that executed it — never a local claim when no local run was possible |
| Diff | `base...final` compare link and a `--stat` |
| Remaining risks | honest list, including "not verified on device" |

Rules of evidence: a test is only "passing" if a run actually executed it; a command that could not
run in the authoring sandbox is reported as such; findings that contradict an earlier document are
stated in the report before the code changes.

**A report refresh follows every prompt** (rule S6 in [DECISIONS.md](DECISIONS.md)): even a prompt that
changes no code ends with [REPOSITORY_REPORT.md](REPOSITORY_REPORT.md) matching the latest head and
state, and with any claim the prompt touched corrected in the affected report — same push, no
chat-only record.

---

## Index

| Date | Phase | Report | Base → final | Verified by | Status |
| --- | --- | --- | --- | --- | --- |
| 2026-11-14 | P2 translation quality & context — **audit only** (read-only, implementation not started) | [P2_TRANSLATION_CONTEXT_AUDIT.md](P2_TRANSLATION_CONTEXT_AUDIT.md) | baseline `fe5aa6a` | none — no code changed; no test executed | **AUDIT COMPLETE / IMPLEMENTATION PENDING** |
| 2026-11-13 | P0/C2 ML Kit English echo eliminated | [P0B_MLKIT_ENGLISH_ECHO.md](P0B_MLKIT_ENGLISH_ECHO.md) | `6eb8974` → `c008f62` (docs `08004c4`, `b82fb6f`, `7b30804`, `3674ebf`) | CI 43563394305, 43563800508, 43564060458, 43564884208 | **VERIFIED COMPLETE** |
| 2026-11-13 | P1.1 strict staging validation · P1.1-A canonical keys (§8) | [P1_1_STRICT_STAGING_VALIDATION.md](P1_1_STRICT_STAGING_VALIDATION.md) | `8140a4b` → `723f101` · P1.1-A `feb20ef` → `fee9d9e` (docs `e546470`, `6eb8974`) | CI 43531030306, 43531550380, 43561318751, 43561668957 | **VERIFIED COMPLETE** |
| 2026-11-13 | P1 persistence safety (recovery never writes the live file) | [P1_PERSISTENCE_SAFETY_FIX.md](P1_PERSISTENCE_SAFETY_FIX.md) | `b960444` → `87830b7` (docs `345715d`) | CI 43526686657, 43527227407 | **VERIFIED COMPLETE** |
| 2026-11-13 | P1 review corrections | [P1_REVIEW_CORRECTIONS.md](P1_REVIEW_CORRECTIONS.md) | `1c0c270` → `e99dd93` (docs `06b3d84`) | CI 43523522645, 43524129101 | **VERIFIED COMPLETE** |
| 2026-11-13 | P1 series character memory | [P1_SERIES_NAME_MEMORY.md](P1_SERIES_NAME_MEMORY.md) · design: [P1_CHARACTER_MEMORY.md](P1_CHARACTER_MEMORY.md) | `528d629` → `8d5cc9e` (docs `1c0c270`) | CI 43518604612, 43520783453 | **VERIFIED COMPLETE** |
| 2026-11-13 | P0-A Gemini name sheet | [P0A_GEMINI_NAME_SHEET.md](P0A_GEMINI_NAME_SHEET.md) | `b8e37de` → `528d629` | CI 43514068424 (attempt 2), 43514608722 | **VERIFIED COMPLETE** |
| 2026-11-13 | P0 architecture audit (read-only) | [P0_MANHWALENS_AUDIT.md](P0_MANHWALENS_AUDIT.md) | v0.11.0 `b8e37de` | none (no code changed) | **VERIFIED COMPLETE** (audit delivered; its open items stay PENDING — see `REPOSITORY_REPORT.md` §10) |

**Latest versions / statuses:** every link above resolves to a file present at head `3674ebf`; the
Status column uses the vocabulary fixed in [REPOSITORY_REPORT.md](REPOSITORY_REPORT.md) §8 and agrees with
it row for row. The most recent repository-level consistency sync is dated 2026-11-13 (commit `05fa1ab`, run
43565553808) and recorded in that file's §9 (no code changed by it).

Supporting documents that are **not** per-change reports: [REPOSITORY_REPORT.md](REPOSITORY_REPORT.md)
(the repository-level snapshot — branch state, phases, verification, blockers, what is next),
`PLAN.md` (phases and definitions of done),
`DECISIONS.md` (one line per decision, including the standing rules this file operates under),
`BLOCKERS.md` (environment limits), `MANUAL_TESTS.md` (device checklists), `CHANGELOG.md`,
`OPEN_SOURCE_RESEARCH.md`, `PRIVACY.md`.

---

## ملخص بالعربية

**قاعدة دائمة:** كل تعديل يُنجَز يُرفَع معه تقرير داخل المستودع في نفس الفرع ونفس الرفع، ويُفهرَس في
الجدول أعلاه. التقرير يذكر: النطاق، كوميت البداية، السبب الجذري مع دليل الملف والسطر، الحل ولماذا،
الملفات المتغيرة، الاختبارات المضافة مع الخاصية التي تحميها، الأمر المُنفَّذ وتشغيل الـ CI الذي نفّذه
(ولا يُقال إن اختبارًا نجح قبل تشغيله فعليًا)، رابط المقارنة، والمخاطر المتبقية.

القيود المعروفة في بيئة العمل هذه: لا يوجد JDK ولا Android SDK، لذلك لا يمكن تشغيل Gradle محليًا —
التحقق الحقيقي الوحيد هو GitHub Actions، ويُذكر رقم التشغيل صراحةً في كل تقرير.
