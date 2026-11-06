# Parts 4–5: agent-assisted test and traceability audit

Scope: the tests added for UC-STU-deactivate-student, its T01–T16 design oracles, the use case's main flow and extensions, and its traceability row. Starting revision: `389a980`. This is an agent-assisted audit requested by the developer, not an independent fresh-context review or an assertion that the developer personally inspected every test.

## Part 4: test vetting

Applied the course's five questions to the tests: oracle, specification source, ability to detect a broken behavior, observable behavior rather than method invocation, and whether a mock hides the rule being tested. The individual test-to-oracle list remains in [the build report](student-deactivation.md#tests-kept-and-their-oracles).

| Test group | Oracle / source | Audit decision |
|---|---|---|
| Lifecycle service | T02/T03/T04/T05/T13; BR-student-lifecycle and BR-student-deactivation-team | Keep state/refusal assertions. Repository mocks isolate service decisions; they do not establish persistence or SQL scope. Add a changed last-name input and preservation assertion to the profile-refusal case so all four editable profile fields are checked. |
| Real persistence and authorization | T02–T05, T07, T15; scoped permissions and atomic transition | Keep real MySQL tests. Rewrite rollback to start from a committed fixture and observe it in a separate transaction after an induced failure; automatic test teardown no longer supplies the rollback result. |
| Concurrent requests | T04 / extension 3a | Rewrite the start-only barrier: keep the first transaction uncommitted, start the second, verify it waits, then release the first and require the second to report unchanged. A lock-removal mutation makes this test red. |
| Historical evaluation reports | T07 / extension 6c; BR-deactivated-student-evaluations | Keep the before/after retention oracle, which comes from the requirement that records remain unchanged. Parse JSON and assert actual non-empty record collections in both seeded weeks, rather than a non-empty JSON string. Require the initial report row to exist. This does not claim that dynamically recomputing a score supplies an independent numerical oracle. |
| Historical activities | T14 / extension 6c; BR-student-lifecycle | Rewrite the weak non-empty string assertion. Snapshot actual activity DTOs by their persisted IDs, require at least one initial record, and compare fields structurally without depending on record/property ordering. |
| Existing tokens and account failures | T08/T09 / FR-SEC-active-account | Keep signed-token integration tests and active controls. They use the real decoder, not the authentication shortcut. Validator/manager unit mocks establish parsing/delegation only; the separate real-query/HTTP tests establish account and relationship behavior. |
| Inactive evaluatee | T10 / BR-deactivated-student-evaluations | Keep previous-open-week refusal, no-save assertion and permitted active-evaluatee control. The cross-week input is from the specified policy. |
| Owed items and actual reminder recipients | T11 / BR-submission-owed | Keep existing eligibility tests; add `StudentDeactivationReminderIntegrationTest.inactiveStudentIsNotEmailedAfterDeactivationAndActiveControlIs`. Both named students initially owe the same WAR; after the real lifecycle POST, real reminder selection excludes John and still emails Eric. Only the SMTP boundary is replaced. |
| Dialog and student views | T01/T04/T06/T12/T16 | Keep 14 component tests with real Element Plus controls and controlled API responses. Deferred promises establish pending/unknown states; real DOM dismissal events establish component cancellation. Mocked APIs do not prove server authorization, browser redirects, CSS, or full browser behavior. |
| Authored Cypress cases | T01/T04/T06/T12/T16 | Retain as unexecuted tests. Native Cypress aborts at startup in this environment; these cases supply no passing evidence. |

No existing tests were deleted. The changes strengthen assertions and test setup; no production behavior was changed by this audit. Supplemental mock call assertions are not treated as proof of persisted state.

## Mutation evidence

1. Earlier build mutation: historical evaluatee selection was replaced by a no-op. `EvaluationServiceTest.historicalReportIncludesInactiveUnassignedEvaluateesOnlyInRecordedWeeks` failed (expected five report rows, received four), then the original production line was restored.
2. Audit mutation: `ActivityService.findByCriteria` temporarily returned an empty page for the selected student's activity query. The **old** `retainedActivitiesRemainVisibleThroughHistoricalQuery` test stayed green: `"[]"` is a non-empty string, so it could compare two absent histories and pass. The strengthened test failed with **Expecting actual not to be empty** under exactly the same mutation. The production query was restored.
3. Audit mutation: removed `@Lock(LockModeType.PESSIMISTIC_WRITE)` from the lifecycle repository lookup. The controlled-overlap test failed because the second request returned `alreadyDeactivated=false` instead of `true`. The annotation was restored.

Every mutation is temporary. The restored source is checked before committing. Passing and failing runs are reported separately; expected mutation failures are not counted as regression failures.

## Part 5: independent Build / Verify check

**Build stays ✅ Built.** The dedicated action, transaction, permission checks, account-status enforcement, retained reports, inactive-evaluatee refusal and UI behavior are implemented. Reactivation is outside scope, and no schema migration was introduced.

**Verify stays 🟡 Partial.** Passing backend/component evidence is separated from the unexecuted Cypress variants and the full frontend build's 18 baseline TypeScript errors. No whole use-case extension is left without a passing backend or component test, but that does not turn unexecuted browser checks into verified ones.

| Flow / extension | Evidence checked | Remaining limit |
|---|---|---|
| Main flow | Dedicated POST, DTO/persistence assertions, warning/single-confirm component tests, earlier live UI run | Cypress main-flow case unexecuted |
| 2a: unauthorized caller | Real-token API matrix, actual scoped query, role/relationship refusals; unchanged account/team | No role-only admin bypass claimed |
| 3a: already inactive / concurrent confirmation | Exact HTTP message, unchanged DTO/state, stale-response component test; controlled-overlap DB test catches lock removal | Cypress stale-dialog case unexecuted |
| 4a: cancel or dismiss | Four passing real Element Plus component cases; manual Cancel | Browser close-icon/Escape/backdrop cases unexecuted |
| 6a: assigned team | Persisted disabled account and null team, removed membership, retained enrollment/history; failure rollback | General concurrent team reassignment remains outside scope |
| 6b: no team | Service and real API/persistence success without a team | No team is required |
| 6c: retained work / visibility | Non-empty two-week evaluation history, absent third-week row, unchanged detail DTOs/averages; non-empty WAR snapshots by ID; scoped reads and privacy refusals | No browser styling or report-chart correctness claimed from mocked report children |
| 6d: existing login | Login-issued token succeeds before deactivation and receives 401 on later reads/writes; fresh login refused, active control succeeds | Does not claim cancellation of already-running requests |

The Tests cell now names extension coverage and the direct reminder-recipient test. The [T01–T16 table](student-deactivation.md#design-oracle-to-actual-test-map) remains the detailed evidence map.

## Results

- The complete 481-test backend suite passed at `389a980` before these audit-only test changes; production code is unchanged by this audit.
- The first strengthened persistence/API/reminder run passed **23 tests**, with no failures/errors/skips.
- Final controlled-concurrency/profile-refusal audit run: **29 passed, zero failures/errors/skips** (`StudentDeactivationApiIntegrationTest`, `StudentLifecycleIntegrationTest`, `StudentDeactivationReminderIntegrationTest`, `StudentLifecycleServiceTest`)..
- Existing frontend evidence remains 14 passing component tests and successful production bundling; full type-check and Cypress limitations remain unchanged. They were not rerun merely to increase a count.

## What remains personal

The developer can use these findings to make and explain their own decisions. This audit does not replace the developer's personal assessment. Part 6's separate fresh-context review has since completed with no actionable findings, and the labeled agent-assisted inline comment is linked in the build report. The build PR remains open and unmerged for grading.
