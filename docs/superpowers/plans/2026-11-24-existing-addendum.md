# Existing addendum schedule implementation plan

> Execute inline using executing-plans and test-driven-development.

Goal: implement issue #19 without changing the base schedule or duplicating Addendum.
Spec: `.spec/features/spec-006-existing-addendum-schedule.md`.
Stack: Jmix 3.1, Java 21, PostgreSQL; reuse existing secured DataManager and confirmation boundary.

## Tasks

- [x] Add integration regressions for existing-DS snapshots, absent/empty bases,
  duplicate/later corrections, foreign/hidden documents and revoked write rights.
  Observe RED before production changes.
- [x] Extend `ScheduleImportStore.DraftContent` with `existingAddendumRequired`
  and `existingAddendumId`, retaining old constructors/JSON. Preserve intent across
  updates; clear the document on contract change. Add workflow selection and
  server-side mode/history validation, reusing the commit lock and fingerprint.
- [x] Extend commit to reference the existing document without saving or changing
  it; create only the correction and its items. Prove base/document immutability,
  repeated-confirmation safety and history-change refusal.
- [x] Add `selectExistingScheduleAddendum` preparation tool, expose retained
  selection in state, and update prompt guidance. Add server-authored snapshot
  title/explanation and document-specific receipts, including restoration.
- [x] Exercise a native conversation with real saved user confirmation; run
  compile/static checks, full `clean test` on disposable PostgreSQL, and review.

## Review focus

Old JSON must decode; row/source edits must retain intent; contract changes must
require reselection; payment/other-project corrections must not conflict;
history/permission changes at commit must not produce a success receipt.

## Execution record

User explicitly authorized implementation of the approved specification in the
existing fix branch; execute without another plan approval or worktree change.


Targeted evidence: first 6 integration cases passed; expanded 118-case run had
117 passes and one invalid fixture (domain already forbids duplicate-date
Addenda). That fixture was removed. Native conversation and receipt restoration
passed. Separate RED test demonstrated cached selection exposure after rights
revocation; selectionModes now checks the selected document again. Fresh review
identified whitespace normalization of stored numbers; a RED test reproduced
HistoryChanged, and normalization now applies only to new-document input.

One pair of targeted Gradle launches overlapped and collided on binary reports
(EOFException / missing in-progress-results file). Those results are not used as
a passing gate. Subsequent verification runs are sequential.

IDE checks cover all changed Java files; existing experimental/internal Jmix API
warnings are unchanged. Message bundle has dynamic-key unused-property warnings;
the six new keys were mechanically verified, and conversation tests assert text.
No new views, fields, buttons or descriptors were introduced.


Final: all tasks complete. Fresh full clean test: 489 tests in 48 suites,
0 failures/errors/skips, BUILD SUCCESSFUL in 4m11s. Whitespace-number and
revoked-selection RED regressions both GREEN in this run. Independent review
reported no further definite defects; no review findings were deferred.
Implementation is on the requested fix branch; publication is through a PR to main, without merging.


PR review: a second fresh reviewer found no blocking implementation defect.
The P3 stale statement about live validation was corrected in the specification.
Four explicitly authorized real-model scenarios reached ai2mee.ru and failed at
initial authentication (401 invalid/expired credential), with no model output.
Live tool selection remains unverified; publish as draft pending refreshed key
and live validation. No code changes followed the 489-test full run.
