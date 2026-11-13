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

---

## Index

| Date | Phase | Report | Base → final | Verified by |
| --- | --- | --- | --- | --- |
| 2026-11-13 | P1 persistence safety (recovery never writes the live file) | [P1_PERSISTENCE_SAFETY_FIX.md](P1_PERSISTENCE_SAFETY_FIX.md) | `b960444` → this commit | CI run at HEAD (see Actions) |
| 2026-11-13 | P1 review corrections | [P1_REVIEW_CORRECTIONS.md](P1_REVIEW_CORRECTIONS.md) | `1c0c270` → `e99dd93` (docs `06b3d84`) | CI 43523522645, 43524129101 |
| 2026-11-13 | P1 series character memory | [P1_SERIES_NAME_MEMORY.md](P1_SERIES_NAME_MEMORY.md) · design: [P1_CHARACTER_MEMORY.md](P1_CHARACTER_MEMORY.md) | `528d629` → `8d5cc9e` (docs `1c0c270`) | CI 43518604612, 43520783453 |
| 2026-11-13 | P0-A Gemini name sheet | [P0A_GEMINI_NAME_SHEET.md](P0A_GEMINI_NAME_SHEET.md) | `b8e37de` → `528d629` | CI 43514068424, 43514608722 |
| 2026-11-13 | P0 architecture audit (read-only) | [P0_MANHWALENS_AUDIT.md](P0_MANHWALENS_AUDIT.md) | v0.11.0 `b8e37de` | none (no code changed) |

Supporting documents that are **not** per-change reports: `PLAN.md` (phases and definitions of done),
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
