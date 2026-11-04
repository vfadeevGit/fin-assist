# Addendum correction summaries

Implement the approved revision to Spec 004 without an item-count column.

1. Test date aggregation, exact deltas, empty drafts, and previous per-type schedule selection.
2. Add a read-only, permission-aware baseline service and pure comparison model.
3. Add aggregate columns, expandable date comparisons, changed/all toggle, audit details and complete totals to both existing composition grids. Keep dialogs and save/cancel semantics.
4. Refresh from parent DataContext changes; cache baseline reads by effective date/project/type.
5. Update Spec 004, compile/static-check changed files, run isolated clean tests and browser checks, then obtain independent review.

## Progress evidence

- Pure comparison tests first failed with missing implementation; all four then passed.
- Baseline integration tests passed. Security tests reproduced collection-fetch JPQL bypass and joined-header visibility gaps; direct secured item/header queries now guard summaries.
- Unsaved item-edit/removal UI regression passed in the full run. The first full run passed 153/154; the remaining header-access exception was changed to an unavailable result and is under focused verification.
- Final independent review: no confirmed important defect; browser-only interactions remain for the implementation verification pass. No deferred findings or design rulings.

- Header-security regression is green. Final isolated `clean test`: 154/154 passed, zero failures/errors/skips. Compact-date Node tests: 2/2 passed.
- Browser verified expanded detail refresh and toggle retention after nested saves, complete/incomplete footer behavior, parent cancellation/database preservation, both grids, and four viewport sizes. XML/messages/nonempty-file/whitespace checks passed. Temporary app stopped; developer demo DB untouched.
- Implementation and Spec 004 are complete on `addendum-polishing`; changes remain uncommitted for the user's normal workflow.
