# AI Schedule Creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Создавать и заполнять графики оплат/отгрузок из Excel, CSV или сообщения через AI Tools, с подтверждением и корректным оформлением Addendum, исправив выбор истории в двух отчётах.

**Architecture:** Общий сервис выбирает полный действующий снимок отдельно для проекта и типа графика. Серверный черновик хранит источник, проверенные строки и версию предпросмотра; AI управляет уточнениями, а отдельное действие пользователя запускает атомарное сохранение. Стандартный чат и его история сохраняются; расширяются его UI и контекст tools.

**Tech Stack:** Java 21, Jmix 3.1.0, Vaadin 25, Spring AI из BOM проекта, PostgreSQL, Liquibase, Apache POI для XLS/XLSX, Apache Commons CSV для CSV, JUnit 5 / AssertJ / Jmix UI test assist.

**Spec:** [spec-005-ai-schedule-creation.md](../../../.spec/features/spec-005-ai-schedule-creation.md). Прочитать полностью перед реализацией. План подготовлен по ветке `feature/ai-schedule-creation`, исходная база `a50a276`. Реализация задач 1–10 завершена и независимо проверена; сквозная приёмка задачи 11 добавлена. Итоговое ревью всей ветки остаётся открытым.

## Global Constraints

- Одна операция: один контракт, один проект, один тип графика. Форматы `.xls`, `.xlsx`, `.csv` либо пары дата–сумма в сообщении; шаблон файла не требуется.
- Одинаковые даты суммируются; каждая итоговая строка строго положительна; `decimal(19,2)`, без молчаливого округления, без частичного сохранения.
- Единственный подходящий лист/проект выбирается автоматически, несколько требуют выбора. Нет проектов — выбрать другой контракт, проект не создавать.
- Пустой исходный график заполняется; заполненный без коррекций — замена или Addendum; при коррекциях выбранного проекта/типа — только Addendum.
- CorrectionItem хранит полный новый снимок; «было / стало / разница» вычисляется отдельно. Сумму и срок контракта автоматически не менять.
- Номер и `effectiveDate` Addendum запрашиваются до предпросмотра. Дата позже всех существующих корректировок выбранного проекта/типа, включая будущие и пустые; равная дата любого Addendum контракта запрещена.
- AI учитывает всю оформленную историю; отчёты — только историю с `effectiveDate <= reportDate`. Источники оплаты и отгрузки выбираются независимо.
- Сохранять только после явного подтверждения актуального полного предпросмотра. При совпадении — «Изменений нет», без Addendum.
- CRUD/attribute/row-level права, полнота истории, владение вложением и разговором проверяются сервером; generic tools не обходят подтверждение.
- Перед изменением Jmix-артефактов читать `jmix` и профильные навыки. Доступная копия маршрутизатора: `/Users/v.fadeev/IdeaProjects/fin-assist-contract-ux-improvement/.agents/skills/jmix/SKILL.md`. Не копировать Jmix 2/Vaadin 24 API без проверки в 3.1/25.
- Существующие локальные изменения `application-dev.properties`, `.playwright-cli/` и посторонние `.spec/*.md` не включать в коммиты фичи. Не применять `git add .`.

## Review Focus

1. CSV с BOM, кавычками и десятичной запятой не должен превращать одну сумму в два столбца — задача 4.
2. Две вкладки одного пользователя и сообщение из другого разговора не должны подтверждать чужой/старый черновик — задачи 5, 9, 10.
3. Ручное изменение Item или удаление коррекции между предпросмотром и commit должно аннулировать подтверждение, даже если версия Schedule не изменилась — задачи 7–8.
4. Будущее либо пустое соглашение блокирует замену и ограничивает дату, но пустой снимок не стирает предыдущий непустой график — задачи 2, 6.
5. Повтор запроса после успешного commit и потери ответа не должен создавать второе Addendum — задачи 8, 10.

## Структура и границы работ

Обозначения путей для всех задач (это точные корни, не новые модули):

- `J` = `src/main/java/ru/stnovator/finassist/`;
- `R` = `src/main/resources/ru/stnovator/finassist/`;
- `T` = `src/test/java/ru/stnovator/finassist/`.

Общий расчёт истории — `J/service/EffectiveScheduleService.java`; правила импорта и DTO — `J/ai/schedule/`; технические сущности — `J/entity/AiScheduleImport.java` и `AiScheduleAttachment.java`; UI — `J/ai/view/` и `R/ai/view/`. Два существующих отчёта меняются в своих файлах, без общего переписывания отчётного движка.

Все новые DTO — обычные Java records, если не требуется привязка к Jmix DataContainer. Сущности черновиков исключить из `BusinessEntityRegistry`. Технические таблицы не становятся доступными generic CRUD/data tools.

Зависимости: `1 → 2 → 3`; `1 → 4 → 5`; `(2,5) → 6`; `(2,5) → 7`; `(6,7) → 8`; `(4,5,6) → 9`; `(8,9) → 10`; всё → 11. Это один план: отчёты и AI-сценарий используют общий расчёт истории и общие проверки совместимости.

### Принятые для плана технические решения

Один файл на операцию, максимум 25 MiB. Конфигурируемые защитные пределы: 10 000 строк данных, 100 столбцов, 20 листов, 200 000 непустых ячеек на книгу; превышение отклоняет весь импорт, а не обрезает его. Образец для AI — заголовки и до 20 строк каждого кандидата, максимум 32 KiB текста за ответ tool. Эти лимиты — выбор реализации, не дополнительные ранее согласованные требования пользователя.

Черновик и метаданные вложения сохраняются в БД для восстановления после перезагрузки UI. Незавершённый черновик истекает через 24 часа без действий; файл удаляется через 24 часа после завершения/отмены/истечения, запись метаданных остаётся с признаком удаления. Квитанция завершённой операции и result ID сохраняются для идемпотентности. Очистка не удаляет файлы с активными ссылками и повторяет неудачные удаления.

Подтверждение — серверная кнопка «Подтвердить сохранение» у предпросмотра. Ни один `@Tool` не выполняет окончательную запись графика. Подтверждение привязано к authenticated user, conversation, operation ID и revision.

## Task 1: Контракты нормализации и сравнения

**Files:** Create `J/ai/schedule/ScheduleImportTypes.java`, `J/ai/schedule/ScheduleNormalizer.java`; Test `T/ai/schedule/ScheduleNormalizerTest.java`; reuse `J/service/ScheduleComparison.java`.

**Interfaces:** В `ScheduleImportTypes` определить вложенные типы: `Kind {PAYMENT, SHIPMENT}`, `Mode {CREATE, FILL, REPLACE, ADDENDUM}`, `Row(LocalDate date, BigDecimal amount)`, `InputRow(String dateText, String amountText, String source)`, `ParseOptions(String datePattern, char decimalSeparator, char groupingSeparator)`, `Issue(String source, String code)`, `Normalized(List<Row> rows, List<Issue> issues)`. `ScheduleNormalizer.normalize(List<InputRow>, ParseOptions): Normalized`; `ScheduleNormalizer.compare(List<Row> before, List<Row> after): ScheduleComparison`. Lists immutable; ошибки сохраняют ссылку на источник; ни одной частичной persistable таблицы при наличии ошибок.

- [x] Написать тест `groupsDatesAndComparesFullSnapshots`: пример спецификации даёт `150,200`, delta `+50,-200,+200`; `assertThat(result.rows()).hasSize(2)` и `assertThat(diff.delta()).isEqualByComparingTo("50")`.
- [x] Добавить `rejectsInvalidRowsAsWhole`, `rejectsNonPositiveGroupedAmount`, `rejectsOverflowAfterGrouping`, `doesNotRound`, `equalAmountsIgnoreScale`, `equalTotalsDoNotMeanEqualSchedules`. Проверить `100 + (-20) = 80`, `100 + (-100)` — ошибка, `1.001` — ошибка, `1.00 == 1.0`, несуществующий день и неоднозначную дату без ParseOptions.
- [x] Запустить `./gradlew test --tests '*ScheduleNormalizerTest'`; убедиться в RED по отсутствующему поведению.
- [x] Реализовать нормализацию с BigDecimal, строгими датами, сортировкой и суммированием; сравнение адаптирует Row в `ScheduleComparison.Entry`, без нового алгоритма разниц.
- [x] Повторить команду до GREEN. Commit только файлов задачи: `feat: normalize AI schedule inputs`.

## Task 2: Общий выбор действующего графика

**Files:** Create `J/service/EffectiveScheduleService.java`; Test `T/service/EffectiveScheduleServiceTest.java`; reuse `RelatedDataAccess`, `CorrectionComparisonService` как референс проверки полноты.

**Interfaces:** `EffectiveScheduleService.load(UUID projectId, Kind kind, LocalDate asOfInclusive): Snapshot`, где null означает всю историю. Вложенный `Snapshot(UUID projectId, Kind kind, UUID baseScheduleId, UUID sourceAddendumId, List<Row> rows, boolean hasCorrections, LocalDate latestCorrectionDate, String fingerprint)`. Для пакета отчёта `loadAll(Set<UUID> projectIds, LocalDate asOfInclusive): Map<Key,Snapshot>`, `Key(UUID projectId, Kind kind)`. Недоступность полной истории — исключение доступа, не пустой график. Fingerprint покрывает ID/versions/даты/суммы исходных строк, заголовков, всех коррекций и связей, даже если выбран только один снимок.

- [x] Тесты `selectsLatestNonemptyPerKind`, `futureAndEmptyHistoryHasDifferentSelectionAndGuardSemantics`, `doesNotMixProjects`, `inclusiveCutoff`, `hiddenRowsMakeHistoryUnavailable`: база оплаты 100, коррекция оплаты 120, более поздняя отгрузок 200 → оплата 120; пустая коррекция не обнуляет 120; будущая входит при null cutoff и не входит до её даты. `hasCorrections == true` и latestCorrectionDate учитывают пустые коррекции в выбранной границе.
- [x] RED: `./gradlew test --tests '*EffectiveScheduleServiceTest'`.
- [x] Реализовать пакетную загрузку, группировку по проекту/типу и полноту прав. Проверку наличия коррекций для AI выполнять без cutoff. Не выводить скрытые идентификаторы из системной проверки полноты. Не заменять `ProjectScheduleService`, обслуживающий базовый режим; не менять строгое before-date поведение редактора Addendum.
- [x] GREEN той же командой плюс `--tests '*CorrectionComparisonServiceTest' --tests '*ProjectScheduleSecurityTest'`. Commit: `feat: resolve effective schedules independently by kind`.

## Task 3: Исправление источников отчётов

**Files:** Modify `J/report/CashflowCalendar.java`, `J/report/ListOfContracts.java`, `R/messages_ru.properties`; Test existing `T/report/CashflowCalendarTest.java`, `T/report/ListOfContractsTest.java`.

**Interfaces:** Consumes `EffectiveScheduleService.loadAll`; оба отчёта сохраняют существующие public delegate signatures и dataset schema, но внутренние source IDs/подписи разделяются по `Key`. Если текст шаблона использует одно соглашение для обеих колонок, обновить формирование подписи на два явно названных источника, не менять формат выходного файла без необходимости.

- [x] В обоих тестах добавить `retainsPaymentCorrectionAfterShipmentOnlyAddendum` и обратный случай: оплаты 100→120, отгрузки 50→70; после второго соглашения ожидаются 120/70, а не 100/70. Добавить разные проекты, пустое соглашение, даты до/в день/после effectiveDate и неизменность базового режима.
- [x] RED: `./gradlew test --tests '*CashflowCalendarTest' --tests '*ListOfContractsTest'`.
- [x] Заменить выбор одного Addendum общим resolver с reportDate; суммы и подписи брать из выбранного источника каждого типа. Сохранить фильтры периода, доступ и структуру datasets; не использовать AI cutoff=null.
- [x] GREEN той же командой; проверить dataset source labels содержат реальные номера разных соглашений. Commit: `fix: preserve independent schedule history in reports`.

## Task 4: Чтение Excel/CSV и выбор схемы

**Files:** Create `J/ai/schedule/ScheduleTableReader.java`, `ScheduleTableTypes.java`, `ScheduleImportProperties.java`; Modify `build.gradle`, `src/main/resources/application.properties`; Test `T/ai/schedule/ScheduleTableReaderTest.java`; fixtures `src/test/resources/ai/schedule/`.

**Interfaces:** В `ScheduleTableTypes`: `Cell(int column, String raw, String formatted, String type)`, `Sheet(String key, String name, List<List<Cell>> sample)`, `Inspection(List<Sheet> sheets)`, `Mapping(String sheetKey, int dateColumn, int amountColumn, int firstDataRow, Set<Integer> excludedRows, ParseOptions formats)`. `ScheduleTableReader.inspect(InputStream,String fileName): Inspection`; `read(InputStream,String fileName,Mapping): List<InputRow>`. Оба метода bounded; excludedRows допустимы только для подтверждённых заголовков/итогов, не для строк с ошибками.

- [x] Тесты `readsXlsAndXlsx`, `readsQuotedCsvWithBomAndDecimalComma`, `ambiguousColumnsRequireMapping`, `readsAllRowsBeyondAiSample`, `rejectsOversizedWorkbookWithoutTruncation`, `handles1904DateSystem`, `formulaWithoutReliableCachedValueRequiresCorrection`. Пример CSV `"01.12.2026";"1 234,50"` даёт ровно одну строку и сумму 1234.50 после задачи 1. Две подходящие вкладки остаются двумя кандидатами.
- [x] RED: `./gradlew test --tests '*ScheduleTableReaderTest'`.
- [x] Сверить `./gradlew dependencyInsight --dependency poi --configuration runtimeClasspath`; использовать совместимую с BOM версию POI (в локальном кеше проверены исходные артефакты 5.5.1), закрепить прямую зависимость для собственного использования. Для Commons CSV выбрать и закрепить совместимую версию после проверки официального API; не полагаться на случайный transitive jar. Реализовать reader: безопасный workbook parser, настоящий CSV parser, строгую кодировку UTF-8/BOM с явным уточнением альтернативной кодировки, отсутствие исполнения макросов/внешних ссылок. Проверять лимиты во время чтения, не только после создания workbook в памяти.
- [x] GREEN; тесты создают небольшие fixture-книги программно, включая cached formulas и ошибки; зафиксировать итоговый dependencyInsight без конфликтов. Commit: `feat: parse schedule spreadsheets with explicit mappings`.

## Task 5: Владение вложением и долговечный черновик

**Files:** Create `J/entity/AiScheduleImport.java`, `AiScheduleAttachment.java`, `J/ai/schedule/ScheduleImportStore.java`, `ScheduleAttachmentService.java`, `ScheduleImportCleanup.java`; Create `R/liquibase/changelog/2026/11/15-000100-ai-schedule-import.xml`; Create `J/security/AiScheduleImportRole.java`; Modify `FinanceManagerRole.java`, `R/messages_ru.properties`; Test `T/ai/schedule/ScheduleImportStoreTest.java`, `ScheduleAttachmentServiceTest.java`.

**Interfaces:** `ScheduleImportStore.create(UUID conversationId): UUID`, `load(UUID operationId): Draft`, `update(UUID operationId,long expectedRevision,DraftContent): Draft`, `cancel(UUID operationId,long expectedRevision): void`. `Draft`/`DraftContent` вложенные records Store: владелец берётся с сервера; content содержит kind/source/mapping/rows/contractId/projectId/mode/addendumNumber/effectiveDate/fingerprint; Draft дополнительно operationId, conversationId, revision, status, timestamps, resultId. `ScheduleAttachmentService.stage(UUID conversationId,String fileName,InputStream): UUID`, `open(UUID attachmentId): InputStream`, `remove(UUID attachmentId): void`; все методы проверяют владельца и разговор на сервере.

- [x] Тесты `ownerCanResumeDraft`, `otherUserCannotReadOrMutate`, `revisionRejectsSecondTab`, `attachmentIdCannotCrossConversation`, `cancelDoesNotCreateDomainRecords`, `cleanupNeverDeletesActiveAttachment`. Проверить после перезапуска контекста revision и связь файла остаются; технические сущности отсутствуют в discovery.
- [x] RED: `./gradlew test --tests '*ScheduleImportStoreTest' --tests '*ScheduleAttachmentServiceTest'`.
- [x] Создать сущности с audit/version и миграцию, следуя Jmix entity/security навыкам. Хранить versioned JSON content как text с явной schemaVersion, без сериализации JPA-графа. FK на существующий AiConversationEntity, attachment→conversation; user ownership неизменяемо. State transitions: `COLLECTING → PREVIEW_READY → SAVED`, либо `CANCELLED/EXPIRED`; правка возвращает COLLECTING и увеличивает revision. В store нет публичной функции присвоения SAVED произвольному клиенту.
- [x] Реализовать очистку по политике выше; фоновой задаче дать минимальный системный контекст только для технических записей. Права на технические записи — только владельцу, даже при прямом DataManager; обычный AI discovery их не раскрывает. Не расширять доменные права существующих ролей ради фичи.
- [x] GREEN и миграция на пустой/существующей тестовой БД. Commit: `feat: persist owned schedule import drafts and attachments`.

## Task 6: Подготовка операции и предпросмотра

**Files:** Create `J/ai/schedule/ScheduleImportWorkflow.java`, `ScheduleImportPreview.java`; Test `T/ai/schedule/ScheduleImportWorkflowTest.java`.

**Interfaces:** `Workflow.start(UUID conversationId,Kind kind): Draft`; `setRows(UUID op,long rev,List<InputRow>,ParseOptions): Draft`; `findContracts(UUID op,String query): List<Choice>`; `selectContract(UUID op,long rev,UUID contractId): List<Choice>`; `selectProject(UUID op,long rev,UUID projectId): Draft`; `allowedModes(UUID op): Set<Mode>`; `preview(UUID op,long rev,Mode mode,String number,LocalDate effectiveDate): ScheduleImportPreview`. `Choice(UUID id,String caption)` — вложенный record Workflow. Preview: operationId, revision, contract/project captions, kind, mode, number/date, rows, ScheduleComparison, total, fingerprint, noChanges. Нельзя передать caption/owner/fingerprint с клиента как доверенные данные.

- [x] Тесты `selectsOnlyProjectAndAsksForSeveral`, `noProjectsRequestsAnotherContract`, `missingAndEmptyScheduleModes`, `correctionsDisallowReplacement`, `unchangedScheduleDoesNotPrepareSave`, `requiresAddendumMetadataBeforePreview`, `rejectsBackdatedOrDuplicateDate`. Проверить empty correction и future effectiveDate, одинаковую дату соглашения другого проекта, скрытую историю и принадлежность проекта контракту.
- [x] RED: `./gradlew test --tests '*ScheduleImportWorkflowTest'`.
- [x] Соединить Store, Normalizer и EffectiveScheduleService. Сведения о контракте искать после извлечения; реквизиты из исходного промпта можно повторно не спрашивать. Единственный доступный проект нельзя объявлять единственным фактическим при неполной видимости; блокировать неоднозначную автоматизацию. При ADDENDUM дата строго больше `latestCorrectionDate`, номер непустой до 50 символов, конфликт уникальности номера/даты требует исправления.
- [x] GREEN; assert число Schedule/Item/Addendum/Correction до и после preview одинаково. Commit: `feat: prepare validated schedule import previews`.

## Task 7: Защита истории от конкурентных изменений

**Files:** Create `J/service/ScheduleMutationGuard.java`; Modify `J/service/ContractTermsValidation.java` для общего порядка блокировок; Test `T/service/ScheduleMutationGuardTest.java`.

**Interfaces:** `ScheduleMutationGuard.lockContracts(Set<UUID> contractIds): void` (только внутри текущей транзакции, сортировка UUID); `lockOperation(UUID operationId): AiScheduleImport`. Общий порядок: lock operation, затем contract; ручные изменения, не имеющие operation, берут contract. Guard участвует во всех изменениях Schedule, Item, Correction, CorrectionItem, Addendum и переносе Project между контрактами, включая удаления. Одной версии заголовка недостаточно.

- [x] Написать реальные двухтранзакционные тесты с barriers: `manualItemEditCannotCommitAcrossConfirmedSnapshot`, `correctionInsertInvalidatesPreview`, `correctionDeleteInvalidatesPreview`, `movingParentLocksOldAndNewContracts`. Проверить прямой DataManager путь, а не только AI service; разрешены ожидание и последующий отказ stale либо откат при deadlock, но не незамеченный stale commit.
- [x] RED: `./gradlew test --tests '*ScheduleMutationGuardTest'`.
- [x] Ввести единый pessimistic lock строки Contract для участников изменений, согласовать с существующим ContractTermsValidation. Для сохранений получить старые и новые связи; для удалений проверить поддержанный pre-delete/BEFORE_COMMIT механизм Jmix, чтобы блокировка была взята до commit. Историю в AI перечитывать в той же транзакции после lock при READ_COMMITTED, без вложенного REQUIRES_NEW snapshot. Не выполнять внешние LLM/file запросы под lock. Прямой SQL вне Jmix-путей не включён в гарантию; существующие штатные пути DataManager/редакторы обязаны участвовать.
- [x] GREEN плюс `--tests '*ContractTermsServiceTest'`; проверить отсутствие инверсии порядка блокировок. Commit: `feat: serialize schedule mutations for safe confirmation`.

## Task 8: Атомарное и идемпотентное сохранение

**Files:** Create `J/ai/schedule/ScheduleImportCommitService.java`; Test `T/ai/schedule/ScheduleImportCommitServiceTest.java`.

**Interfaces:** `confirm(UUID operationId,long previewRevision): CommitResult` вызывается только серверным обработчиком UI; `CommitResult(UUID operationId,String entityName,UUID entityId,boolean alreadySaved)`. Не регистрировать как `@Tool` или generic REST endpoint. Consumes Store, Workflow, Guard и Snapshot. Commit receipt и доменные записи сохраняются в одной транзакции.

- [x] Тесты `createAndFillBothKinds`, `replaceRetainsHeaderAndRemovesOldItems`, `addendumStoresFullSnapshot`, `failureOnLastItemRollsBackEverything`, `retryAfterLostResponseReturnsSameId`, `staleRevisionOrHistoryRequiresNewPreview`, `deniedAttributeOrDeletePermissionPreventsWholeSave`. Проверить отсутствие amountAdjustment/newEndDate и чужих типов коррекций.
- [x] RED: `./gradlew test --tests '*ScheduleImportCommitServiceTest'`.
- [x] Под lock проверить authenticated owner/conversation, PREVIEW_READY и revision; если SAVED — вернуть прежний доступный result ID. Перечитать историю, сравнить fingerprint, проверить полный состав прав, дату и номер. Создать/заменить composition и receipt одним SaveContext/transaction; удаление старых Item явно, не рассчитывать на несогласованный orphan removal. Не сбрасывать старый снимок при ошибке. Доменную запись делать через secured DataManager, не под системным пользователем.
- [x] GREEN плюс конкурентные тесты задачи 7. Commit: `feat: save confirmed schedules atomically and idempotently`.

## Task 9: Tool orchestration и контекст существующего чата

**Files:** Create `J/ai/schedule/ScheduleImportTool.java`, `J/ai/ScheduleAiChatDataService.java`; Modify `src/main/resources/prompts/financial-chat-system-prompt.st`; Test `T/ai/schedule/ScheduleImportToolTest.java`, `T/ai/ScheduleAiChatDataServiceTest.java`, existing `T/ai/AiToolsConfigurationTest.java`.

**Interfaces:** Tool delegates Workflow; exposed operations `startScheduleImport`, `inspectScheduleAttachment`, `applyScheduleMapping`, `setScheduleRows`, `findScheduleContracts`, `selectScheduleContract`, `selectScheduleProject`, `previewScheduleImport`, `cancelScheduleImport` consume op/revision and trusted `ToolContext`. Types follow задачам 4–6; tool results contain next-required field, choices/issues/preview ID, not an executable confirmation token. `ScheduleAiChatDataService extends AiChatDataService` extends `buildPromptSpec(...)` / `loadHistory(...)` as required; user/conversation context resolved from server-owned message, never model arguments.

- [x] Тесты `toolsCannotCommit`, `forgedConversationAndAttachmentAreDenied`, `fileInstructionsCannotConfirm`, `stateSurvivesHistoryWindow`, `normalChatStillUsesDefaultHistoryAndCallbacks`. Через mock model проверить путь «данные → контракт → проект → режим → реквизиты → preview», смешанный запрос двух типов уточняется, два подходящих листа не выбираются молча. После всех tool calls доменных записей всё ещё ноль.
- [x] RED: `./gradlew test --tests '*ScheduleImportToolTest' --tests '*ScheduleAiChatDataServiceTest'`.
- [x] Использовать проверенные в source JAR 3.1.0 protected hooks: `AiChatDataService.buildPromptSpec(List<Message>,UUID,Consumer<AiToolStatusUpdate>)`, `loadHistory(UUID,AiChatMessageEntity)`. Сохранять framework prompt, progress callbacks, текущую историю и placeholder lifecycle. `assistantMessageId` уже есть в ToolContext: по нему сервер восстанавливает conversation и проверяет effective user. Не применять глобальную mutable переменную/ThreadLocal для передачи контекста асинхронного вызова.
- [x] Добавить структурированный актуальный state в запрос, чтобы усечение chat history не теряло выбор и строки. Tool извлекает все строки сервером по Mapping; LLM не переписывает полный файл в JSON по памяти. Для plain prompt InputRow привязан к исходному сообщению; неоднозначность требует вопроса. Промпт не содержит обещаний сохранения до UI-confirm; отсутствие изменений возвращает точную строку «Изменений нет».
- [x] GREEN плюс `--tests '*AiToolsConfigurationTest' --tests '*OpenWebUiConnectionTest'` с локальным mock HTTP, без обращения к живой модели. Commit: `feat: orchestrate schedule imports through AI tools`.

## Task 10: Загрузка и подтверждение в существующем AI UI

**Files:** Create `J/ai/view/ScheduleAiChatFragment.java`, `ScheduleImportPanel.java`, `R/ai/view/schedule-ai-chat-fragment.xml`; Modify `R/messages_ru.properties`; минимальные view/fragment replacement bindings в `J/ai/AiScheduleUiConfiguration.java`; Test `T/ai/ScheduleImportUiTest.java`, existing `T/ai/AiToolsShortcutUiTest.java`.

**Interfaces:** `ScheduleImportPanel.bind(UUID conversationId)`, `showPreview(UUID operationId,long revision)`, `clear()`; panel loads trusted Preview from store and calls `CommitService.confirm` only on button click. `ScheduleAiChatFragment` extends `AiChatFragment`; preserve `setConversation`, `sendMessage`, `processUserMessage` lifecycle. Файл staging привязывается к conversation и следующему реальному user message перед запуском tool processing.

- [x] UI-тесты `uploadsOnlyAllowedFileTypes`, `canRemovePendingAttachment`, `singleProjectNeedsNoPicker`, `rendersEveryPreviewRowAndAllChanges`, `confirmInvokesOnlyDisplayedRevision`, `switchingConversationClearsPendingConfirmation`, `doubleClickReturnsSameResult`. Вручную нажатие после подтверждённого изменения поля должно требовать новый preview.
- [x] RED: `./gradlew test --tests '*ScheduleImportUiTest'`.
- [x] Повторить UX CRM: меню «+» → файл с устройства, временное хранилище, карточка/удаление. Проверенные исходники CRM: `AiConversationComposerFragment`, `AddContextMenuFactory`, XML composer. Ограничить одним файлом и форматами/лимитом плана. Хранилище — сервис задачи 5, не публичный FileRef.
- [x] Подключить subclass через поддержанный Jmix 3.1 механизм замены фрагмента (проверить по skill/API до написания дескриптора); standard hub и direct `aitls/chat/:id?` должны оба использовать расширение. Если требуется переопределение дескриптора, сохранить стандартные ID/views и историю. Не заводить второй chat view с отдельным хранилищем.
- [x] Показывать полный график в таблице с доступом ко всем строкам; totals и diff вычислены сервером. UI-панель отображает источники, номер/дату, действие, «Подтвердить сохранение», исправление и отмену. При успехе использовать `EntityLinksTool`; при reload восстановить незавершённый draft либо сохранённый результат. Пока идёт загрузка/обработка, двойная отправка блокируется; ошибка даёт повтор без дубликата.
- [x] GREEN плюс shortcut tests; провести browser walkthrough XLSX/CSV/текст, обе разновидности, Addendum и отказ stale. Commit: `feat: add schedule attachments and explicit chat confirmation`.

## Task 11: Сквозная проверка и документация результата

**Files:** Create `T/ai/schedule/ScheduleImportAcceptanceTest.java`; Modify spec только для фактических свидетельств реализации, этот план — для отметок выполнения.

**Interfaces:** Проверка public workflow → mock AI → real persistence → report datasets, без нового production API.

- [x] Добавить интеграционный сценарий `paymentThenShipmentAddendumRemainsConsistentEverywhere`: импорт оплат, Addendum оплат, Addendum отгрузок; оба отчёта и AI preview возвращают один и тот же снимок на одинаковой границе. Проверить all-history AI против as-of report, номер/дату, полные CorrectionItem, no-change и повтор confirmation.
- [x] Выполнить targeted suites задач, затем все gates проекта: API/static inspections, `./gradlew clean test` на disposable PostgreSQL, browser render walkthrough. Для БД создать отдельный контейнер/БД с уникальным именем, передать `MAIN_DATASOURCE_URL/USERNAME/PASSWORD` и тестовый AI key через окружение; не использовать настройки/данные production и не печатать реальные секреты. Сетевой AI в тестах заменён mock HTTP. До запуска подтвердить, что именно эти env keys связываются конфигурацией проекта; не менять пользовательский `application-dev.properties`.
- [x] Проверить миграцию как на пустой схеме, так и на копии предыдущей тестовой схемы; deny CRUD/row policies, cleanup и concurrency должны реально исполняться, не быть skipped.
- [x] Провести UI сценарии A18; записать проверенные экраны и ограничения. `bootRun` не использовать как завершающий Gate-2 test: сервер не завершает команду. Для browser walk сервер запускается отдельно с явным управлением жизненным циклом.
- [x] Сверить R01–R19 / A01–A20 по таблице ниже, записать команды и результаты, известные ограничения, per-file static verdicts. Если gate недоступен — отметить непроверенное, не выдавать за PASS. Commit: `test: verify end-to-end AI schedule creation`.

## Покрытие спецификации и самопроверка плана

| Требования / критерии | Задачи |
| --- | --- |
| R01–R04, R14–R15; A01–A04 | 1, 4, 9, 10 |
| R05–R06; A05 | 6, 9, 10 |
| R07–R09; A06–A08 | 2, 6, 8 |
| R10–R12, R16, R18; A09–A12, A19 | 1, 2, 6, 8 |
| R13; A13–A15 | 5, 6, 7, 8, 9, 10 |
| R17; A16, A18 | 5, 9, 10 |
| R19; A17, A20 | 2, 3, 11 |

Самопроверка: каждый R/A имеет задачу; типы Kind/Row/Mode/InputRow общие, не дублируются в отчётах и tools; сохранение доступно только из UI-confirm path; обе границы истории описаны явно; пять Review Focus имеют отдельные тесты. Новые классы и сигнатуры — проектные решения плана, не утверждение о наличии этих API в framework. Конкретные точки замены UI, dependency versions и lifecycle locking должны пройти указанные проверки на реализации; их нельзя заменить догадкой из старых Jmix API.

## Передача в реализацию

План не запускает реализацию автоматически. После просмотра пользователь выбирает inline выполнение (`executing-plans`) либо задачи с независимыми исполнителями и ревью (`subagent-driven-development`). Рекомендуется второй вариант: изменения прав, подтверждения и конкурентной записи требуют отдельной проверки на границах задач. В любом режиме зависимые задачи идут последовательно по указанному графу, а существующие пользовательские файлы остаются вне коммитов фичи.


## Фактическое выполнение — 2026-11-16

Отметки выше фиксируют достигнутый результат задачи, а не обязательное буквальное совпадение проектных примеров API/имен тестов. Задачи 1–10 и исправления их ревью приняты controller; Task 11 не добавляет production API. Например, operation lock остаётся внутри Store, а Guard отвечает только за contract locks; native UI расширен через три поддержанных component replacements. Точные решения и per-file inspections записаны в task reports; итоговая матрица требований — раздел 13 спецификации.

- [x] Задачи 1–4: общие records/нормализация, независимая история, оба отчёта, защищённый XLS/XLSX/CSV parser; целевые RED/GREEN и независимые ревью выполнены.
- [x] Задачи 5–8: технический owned store/attachments/retention, preview/права, сериализация штатных DataManager mutations, атомарный commit/replay; целевые RED/GREEN и независимые ревью выполнены.
- [x] Задачи 9–10: preparation tools/native persisted chat, trusted message binding, native hub/direct composer, полный preview/confirmation/reload, отдельные исправления caption/upload/retry lifecycle; целевые tests и независимые ревью выполнены.
- [x] Task 11: `ScheduleImportAcceptanceTest.paymentThenShipmentAddendumRemainsConsistentEverywhere` — loopback mock HTTP → настоящие tools → preview без доменных записей → реальный confirm → оба report datasets. Оплаты/FILL → оплаты/Addendum → отгрузки/Addendum; полные snapshots, сохранение базы/прошлого соглашения, номер/дата, no-change/replay, будущая AI history и report cutoff до/в/после каждой даты, BASE.
- [x] Дополнительно закрыты A05/A08: отдельный acceptance test проверяет 0/1/несколько контрактов без auto-select, пустой и отсутствующий исходный Schedule с пустой будущей коррекцией, запрет FILL/ранней даты, допустимый поздний preview без записи.
- [x] Финальная целевая команда после всех test edits: `source .superpowers/sdd/2026-11-15-ai-schedule-creation/test-env.sh` → `./gradlew test --tests '*ScheduleImportAcceptanceTest'`: BUILD SUCCESSFUL, **2 executed, 0 failures/errors/skips** (`/tmp/task11-final-targeted2.log`).
- [x] Миграции: исходный joined scenario отдельно прошёл на пустой уникальной БД и на отдельной восстановленной копии test DB с реконструированной схемой до message-binding migration. Проверены все пять changeset трёх AI migration-файлов, UUID nullable column/index; обе task-owned БД удалены, cleanup drafts/chats=0. Это не копия production и не откат shared test schema. Подробности/точные команды — task-11 report.
- [x] A18 desktop browser evidence задачи 10: hub text PAYMENT/FILL; CSV SHIPMENT/FILL с pending removal/reattachment и reload; direct XLSX PAYMENT/Addendum; stale после настоящего ручного Item edit, новый preview/confirm, saved links/reload, визуально проверенное `+` menu. Controller просмотрел screenshots. Caption/lifecycle исправления дополнительно проверены 22 headless UI tests; повтор полного browser walk после них не заявляется.
- [x] Общий финальный Gate 2: `./gradlew clean test` на disposable PostgreSQL, dummy AI key/model и loopback mocks — **BUILD SUCCESSFUL за 4m 6s; 39 suites, 379 tests, 0 failures/errors/skips**. JUnit XML прочитан по всем suites; deny policies, cleanup/restart и concurrency реально выполнялись. Acceptance class повторно прошёл на той же DB после целевой команды. Финальный query: owned drafts/chats/Addenda/contracts=0; task-owned migration DBs=0; shared latest migration=1.
- [ ] Независимое ревью Task 11.
- [ ] Финальное независимое ревью всей ветки controller.

Gate 1 нового test: explicit-project IDE inspection без ошибок/unresolved symbols; ожидаемые предупреждения `BandData` internal report API и необязательный `HashMap<>` style suggestion. Новых views/roles нет. Per-file verdicts, `git diff --check`, Markdown/nonempty checks и точные итоговые счётчики — в task-11 report. Ограничения: mobile/responsive QA, browser finance-manager login, browser XLS и большой 150-row browser fixture не выполнялись; соответствующие серверные/headless сценарии указаны в spec. Не проверялось качество живой LLM и container upload permissions; external model endpoints не использовались.

### Сохранённые решения выполнения

Все `Ruling:` из временного журнала выполнения сохранены здесь вместе с причиной и ценой решения, чтобы они не зависели от сохранности SDD workspace.

| Решение | Причина | Цена / компромисс |
| --- | --- | --- |
| Продолжить в текущем checkout feature-ветки, сохраняя посторонние изменения. | Пользователь создал эту ветку для фичи и согласовал план здесь. | Если изоляция выбрана неверно, потребуется перенос работы. |
| По задачам запускать целевые suites, полный suite — в завершающем gate. | Проверки должны соответствовать изменению без бессмысленного повторения. | Межзадачные регрессии могут обнаружиться позже. |
| В задаче 3 обновить financial system prompt и соответствующие assertions. | Старый prompt утверждал выбор одного последнего Addendum и возврат обоих типов к базе, что противоречило исправленным отчётам. | Небольшое дополнительное изменение prompt сверх исходного списка файлов. |
| В задаче 4 добавить CsvOptions к Mapping/Inspection. | Исходная сигнатура не позволяла явно уточнять CSV encoding/delimiter, требуемые спецификацией. | Локальная адаптация downstream DTO consumers. |
| В задаче 3 разрешить минимальное изменение wrap/высоты строки подписи в list-of-contracts.xlsx. | Две реальные подписи независимых источников обрезались фиксированной высотой 22. | Небольшое изменение шаблона, требующее проверки output. |
| В задаче 4 вынести workbook preflight в package-private helper. | Отделить ограниченный защитный разбор от преобразования таблицы и не создавать монолитный reader. | Один дополнительный focused source file. |
| Технические сущности задачи 5 доступны напрямую только на чтение с ownership constraints; services записывают внутренним путём после ownership validation. | Клиент не должен подделывать revision/status; доменные записи остаются secured. | Дополнительная внутренняя persistence-логика и tests; attachment/message provenance в draft, package-private preview transition. |
| В задаче 6 добавить getPreview(operationId, revision). | Для восстановления UI нужен доверенный preview; исходный интерфейс его не предоставлял. Старый fingerprint отклоняется, история молча не подменяется. | Один узкий read API. |
| В задаче 5 добавить tombstone/native conversation deletion integration и additive migration. | Native chat должен удаляться, а receipts — сохраняться. | Небольшое расширение lifecycle за пределами первоначального списка файлов. |
| В задаче 7 использовать operation locking Store вместо дублирующего Guard.lockOperation. | Сохранить проверенную границу owner/state и избежать дублирующих locks. | Отклонение от одной запланированной, но не нужной сигнатуры. |
| В задаче 10 добавить owned Store.savedForConversation и native hub/input/chat replacements. | После полного reload нужны IDs сохранённых операций для восстановления результата; first-message attachment должен работать в native hub. | Один небольшой discovery query и три component replacements; нового store/маршрута чата нет. |
