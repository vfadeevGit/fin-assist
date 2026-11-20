# AI Schedule Creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Создавать и заполнять графики оплат/отгрузок из Excel, CSV или сообщения через AI Tools, с подтверждением и корректным оформлением Addendum, исправив выбор истории в двух отчётах.

**Актуальный статус (2026-11-19):** F1–F3 и W1–W4 реализованы отдельными агентами, каждую задачу принял независимый ревьюер. Итоговое ревью и scoped проверка финальных исправлений приняты. Последний полный clean test на `2f62d80`: 474 теста/47 наборов, без ошибок и пропусков; настройки тестов и ограничения — §18.5–18.6 спецификации. Задачи 1–11 ниже сохраняют историю предыдущей версии.

**Architecture:** Общий сервис выбирает полный действующий снимок отдельно для проекта и типа графика. Серверный черновик хранит источник, проверенные строки и версию предпросмотра; AI управляет уточнениями, а серверный обработчик явного ответа пользователя на опубликованное предложение запускает атомарное сохранение. Стандартный чат и его история сохраняются; расширяются его UI и контекст tools.

**Tech Stack:** Java 21, Jmix 3.1.0, Vaadin 25, Spring AI из BOM проекта, PostgreSQL, Liquibase, Apache POI для XLS/XLSX, Apache Commons CSV для CSV, JUnit 5 / AssertJ / Jmix UI test assist.

**Spec:** [spec-005-ai-schedule-creation.md](../../../.spec/features/spec-005-ai-schedule-creation.md). Прочитать полностью перед реализацией. План подготовлен по ветке `feature/ai-schedule-creation`, исходная база `a50a276`. Реализация задач 1–10 завершена и независимо проверена; сквозная приёмка задачи 11 добавлена. Ревью базовой реализации завершено (см. журнал ниже); новый workflow требует отдельных реализации, ревью и приёмки.

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
- [x] Независимое ревью Task 11 выполнено; все 11 задач рассмотрены controller.
- [x] Финальное независимое ревью всей ветки `a50a276..c4a2792` выполнено; узкие findings и controller rulings переданы в одну final-fix wave.
- [x] Scoped повторное ревью final-fix wave принято: Ready to merge: Yes, Critical/Important замечаний нет.

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

## Final-fix wave — 2026-11-16

- Добавить только `PaymentSchedule.detail` и `ShipmentSchedule.detail` к `FinanceManagerRole`; существующие domain CRUD/attribute/row-level права не расширять. Resolver `EntityLinksTool` остаётся без изменений.
- Всегда показывать owned-квитанцию результата: локализованные status/count/total из trusted preview/сохранённого снимка, доступная ссылка либо нейтральная подпись недоступности. После ручного изменения графика receipt показывает первоначальные значения; при отзыве READ текущие доменные данные не раскрываются.
- RED: новые `ScheduleImportResultUiTest` — **4/4 expected failures**, отдельный immediate Addendum fallback — **1/1 expected failure**. GREEN: result UI4 + existing UI23 + EntityLinksTool8 = **35/35**, 3 suites, 0 failures/errors/skips, BUILD SUCCESSFUL за 1m 9s. Обе ссылки реально открыли detail view нужной записи под finance-manager authority, оба reload восстановили snapshot count/total; оба read-denied cases оставили видимую owned-квитанцию без ссылки.
- Gate 1: explicit-project IDE inspection всех пяти изменённых source/resource файлов, warnings включены; нет ошибок/unresolved findings. Старые experimental/internal AI API warnings и unused-property captions разобраны отдельно в final-fix report. Два новых message keys русские, unique/present; существующие английские audit captions вне scope этой правки.
- Minor query-materialization отложен явно: pinned Jmix 3.1 не имеет публичного немутирующего accessor исходного query; reflection/framework replacement/ослабление security не делать. Непроверенные live-model, mobile, deployment/filesystem и directSQL границы остаются прежними.
- Финальный Gate 2 на exact final source: `./gradlew clean test` — **BUILD SUCCESSFUL за 4m 14s; 40 suites, 384 tests, 0 failures/errors/skips** по всем JUnit XML. Все пять source/resource SHA-256 совпали до/после полного запуска. Полная команда повторно выполнила новые cases; read-only audit подтвердил result drafts/conversations/users и fixture contracts=0. Лог `/tmp/final-fix-full.log`, подробный локальный report `.superpowers/sdd/2026-11-15-ai-schedule-creation/final-fix-report.md`.
- Reproduction environment: все точные dummy/disposable exports приведены в разделе 14 `.spec/features/spec-005-ai-schedule-creation.md`; временный SDD `test-env.sh` после завершения не нужен и удаляется. Scoped повторное ревью исправления принято; новых блокирующих дефектов нет.

### Завершение

Все 11 задач прошли независимое ревью; итоговый review и scoped повторная проверка исправлений приняты. Последний проверенный production commit — `1d6059a`; после полного clean test (384/40, zero failures/errors/skips) выполнялась только фиксация статуса документации. Ветка `feature/ai-schedule-creation` сохранена; merge/push не выполнялись. Посторонние пользовательские изменения не включались в коммиты. Временные app/mock процессы завершены; тестовая PostgreSQL останавливается при завершении controller.


## Дополнение от 2026-11-19 — файлы в чате

Статус: описанный ниже подход согласован пользователем и реализован; выполнение и ревью подтверждены итогами ниже. Позднее 2026-11-19 пользователь также согласовал workflow подтверждения через чат; его нормативное описание — раздел 18 спецификации, реализация — выполненные W1–W4 ниже. Ранее отмеченные выполненными задачи относятся к предыдущей версии UI.

Основа: исследован механизм `jmix-crm` — карточки ожидающих отправки файлов, вложения сообщений в истории и сводная панель контекста. Переносится пользовательский подход, с адаптацией к native AI Tools chat Jmix 3.1 и существующей модели владения fin-assist. Excel/CSV продолжает обрабатывать наш проверенный reader.

### F1. Карточки и обзор файлов беседы

- [x] В `ScheduleAiChatInputFragment` показывать имя и размер ожидающего отправки файла, оставлять удаление до отправки.
- [x] В `ScheduleTimelineItemRenderer` показывать карточку отправленного файла под исходным сообщением; обеспечить восстановление после reload и скачивание оригинала с серверной проверкой владельца/доступности.
- [x] В `ScheduleAiChatFragment` добавить компактный свёрнутый раздел «Файлы беседы · N» со списком отправленных вложений. Общие компоненты карточки/списка выделить по необходимости; не копировать всю архитектуру чата CRM.
- [x] Проверить отправку первого сообщения с файлом, reload, переключение бесед, скачивание, недоступный файл и запрет доступа к чужим вложениям. Нельзя показывать файл одной беседы в другой или считать ожидающий отправки файл уже отправленным.
- [x] Провести независимое ревью и проверку отображения в браузере.

### F2. Статус обработки источника графика

- [x] Добавить серверное представление состояния обработки вложения на основе связанных операций импорта: «Прикреплён к сообщению», ожидание выбора листа/уточнения столбцов, распознанный график с количеством дат и суммой, ошибка с понятной причиной.
- [x] Отображать эти состояния в карточке файла; не выводить статус успешного распознавания из свободного текста AI. Загрузка файла и сохранение графика — отдельные события.
- [x] При повторном использовании файла различать связанные операции: результат старого импорта не должен подменять состояние текущего.
- [x] Проверить переходы, ошибки, восстановление после reload и повторное использование; провести независимое ревью.

### F3. Повторное использование вложений через диалог

- [x] Расширить `ScheduleAttachmentService` и `ScheduleAiChatDataService`: передавать AI проверенный список доступных отправленных вложений беседы, а не только файл текущего сообщения и источники активных импортов. Список ограничивать фактическим текущим USER-сообщением; исключить будущие/чужие/неотправленные вложения.
- [x] Адаптировать `ScheduleImportTool` и проверку происхождения источника: позволить выбрать ранее отправленный файл этой беседы по запросу пользователя, без повторной загрузки; при неоднозначности уточнять файл.
- [x] Сохранить проверки владения, доступности, лимиты чтения и трактовку имён/содержимого как недоверенных данных. Не загружать все файлы целиком в каждый prompt.
- [x] Проверить: файл отправлен отдельным сообщением до запроса импорта; несколько файлов с одинаковыми именами; длинная история за пределами окна памяти; недоступный файл; изоляция бесед; повтор после ошибки.
- [x] Провести независимое ревью, регрессионные тесты и browser case с одним прикреплением XLS и последующим запросом импорта.

Согласованная граница: карточки, обзор, статусы и повторное использование файлов. Удаление уже отправленных файлов из истории, переключатели включения/исключения из контекста и объединение нескольких файлов в один график этим решением не вводятся. Спецификация обновлена: раздел 18. Выполнять вместе с W1–W4 ниже; новые сервисные интерфейсы и тесты уточнены в этом дополнении.


## Workflow через сообщения — согласовано 2026-11-19

Нормативный источник: раздел 18 спецификации; F1–F3 остаются обязательной частью. Старые `UI_CONFIRMATION`, кнопочный `confirmDisplayed()` и утверждение «только UI-confirm path» в задачах 1–11 описывают прежний путь и заменяются W1–W3. `ScheduleImportCommitService.confirm()` остаётся внутренним сервисом, не инструментом модели. Существующие права, locks, fingerprints, idempotency и правила Addendum сохраняются.

### Карта изменений и проектные интерфейсы

Имена новых классов ниже — решения плана, не утверждение о существующих framework API. Java-пути относительно `src/main/java/ru/stnovator/finassist/`; тесты — зеркально в `src/test/java/ru/stnovator/finassist/`.

| Артефакт | Ответственность |
| --- | --- |
| `ai/schedule/ScheduleConversationConfirmationService` (новый), `ScheduleImportStore`, `entity/AiScheduleImport` и additive Liquibase | Долговечная связь предложения/версии/реального ответа; единственное ожидающее предложение на беседу; восстановление и защита от повторов. |
| `ai/schedule/ScheduleConfirmationReplyClassifier` (новый) | Консервативное распознавание самостоятельного согласия/отказа; сложный текст возвращает NEEDS_DIALOGUE. Никакого поиска подстроки «да». |
| `ai/ScheduleAiChatDataService`, `ai/view/ScheduleAiChatFragment`, `ai/schedule/ScheduleImportTool`, `ScheduleChatContext` | Перехват фактического USER до обращения к модели, публикация предложения/результата, продолжение уточнений, исправления и provenance. |
| `ai/view/ScheduleImportPanel`, `ScheduleTimelineItemRenderer`, новые небольшие renderer/card fragments при необходимости | Удаление панели управления; полный read-only график/разница и квитанция в истории, карточки и обзор файлов. |
| `ai/schedule/ScheduleAttachmentService`, `ScheduleImportStore` | Список отправленных файлов до фактического запроса и проверка выбранного исторического источника; отдельно исходное сообщение и текущий запрос. |
| `src/main/resources/prompts/financial-chat-system-prompt.st`, messages_ru и UI descriptors | Диалоговые инструкции, новые состояния/подписи, отсутствие старого требования нажать кнопку. |

Предлагаемые внутренние контракты: `publishProposal(operationId, revision, proposalMessageId)`, `handleReply(conversationId, actualUserMessageId): ReplyOutcome`, `pendingProposal(conversationId)`; outcome = APPLIED / DECLINED / NEEDS_DIALOGUE / STALE / NONE с данными результата при успехе. `publishProposal` доступен только серверному pipeline после сохранения полной проверенной карточки-предложения, не как @Tool. `handleReply` сам загружает USER и владельца, не принимает `confirmed` или текст от модели. При отправке USER composer сохраняет request-local observedProposalMessageId/observedPreviewRevision именно отображённого предложения своей вкладки. Обработчик сравнивает их с серверным pending; при отсутствии/несовпадении ничего не сохраняет. Эти метаданные проверяются как ссылки, а не принимаются за разрешение записи. Сериализация с commit — под транзакционными locks; уникальность pending обеспечивается в БД/блокировкой разговора, не памятью UI. Конкретную схему связи с native message проверить по pinned API перед реализацией.

Для файлов: `listSubmitted(conversationId, throughUserMessageId)` возвращает bounded metadata и явную доступность; при большом списке нужна пагинация/поиск без молчаливой потери источников. `selectSource(operationId, revision, attachmentId, actualUserMessageId)` проверяет принадлежность и порядок, сохраняет sourceMessageId отдельно от нового requestMessageId, инвалидирует старый preview. Существующий путь текущего вложения переиспользует тот же контроль. Модель может предложить ID только из серверного списка; сервер всё равно проверяет его. Контракты реализовать в F3, не дублировать в W2.

### W1. Доверенное подтверждение сообщением

Зависимости: базовые store/workflow/commit. Результат — безопасный серверный путь, ещё без изменения UI.

- [x] Прочитать Jmix router и skills сущностей, миграций, сервисов, безопасности и тестов; проверить pinned native message API. Спроектировать additive storage proposal ID/revision/fingerprint и обработанного USER ID, сохранив старые drafts/receipts.
- [x] RED: добавить `ScheduleConversationConfirmationServiceTest` и `ScheduleConfirmationReplyClassifierTest`: согласие только своему опубликованному pending; «нет» отменяет; mixed correction/цитата/подстрока/чужое/будущее USER не применяются; повтор того же сообщения идемпотентен; pending уникален; устаревшая версия не сохраняется. Старые PREVIEW_READY без proposal не становятся автоматически подтверждёнными. Две вкладки: A отображает V1, B публикует V2, ответ из A с observed V1 отклоняется без применения V2; отсутствие observed metadata тоже не даёт согласия.
- [x] Запустить новые целевые тесты и убедиться в ожидаемых падениях, реализовать минимальные сервисы/миграцию. Для простых утвердительных/отрицательных ответов применять нормализацию регистра/пробелов/пунктуации и явный список целых фраз; остальные ответы уходят на уточнение, не в commit.
- [x] Проверить rollback, concurrent replies, замещение предложения, domain fingerprint и отозванные права с настоящими store/commit на disposable DB; запустить migration empty/upgrade проверки затронутой схемы.
- [x] Независимое ревью W1 (авторизация и гонки), устранение замечаний, целевые проверки и отдельный коммит только файлов задачи.

### W2. Встраивание в native chat pipeline

Зависимости: W1, F3. Результат — подготовка и применение через настоящий разговор, сохранение стандартной истории/ошибок/retry.

- [x] RED: `ScheduleConversationWorkflowTest` проверяет persisted USER → обработчик подтверждения до LLM; подготовительный tool не может сохранить; предложение активно только после публикации полного preview; retry ошибки публикации не открывает запись; исходный файл и новый запрос имеют разные корректные IDs; observed proposal metadata сохраняется вместе с фактическим USER и не подменяется текущим pending другой вкладки.
- [x] Встроить обработчик в фактический путь persisted сообщения. Согласие на pending обслуживается сервером; обычные запросы продолжают native модельный цикл. Отказ закрывает операцию; исправление/другая тема делает старое предложение неподтверждаемым до следующего полного предложения.
- [x] Публиковать объяснение действия, полный проверенный график/diff и вопрос как сохраняемое сообщение/связанное представление. Не полагаться на пересказ таблицы моделью. Проверить точку публикации и восстановления по pinned API, не вводить параллельный conversation store.
- [x] Обновить tool states и prompt: CREATE/FILL без вопроса о режиме, REPLACE/ADDENDUM выбор в чате, Addendum only при истории, запрос реквизитов до preview, no-change без записи; запрет утверждать успех до commit. Снять старое требование UI-кнопки.
- [x] Проверить все четыре режима, no-project/no-change, неоднозначность, исправления и текстовый ввод; сохранить parser/rights/report regression. Независимое ревью, исправления и отдельный коммит.

### W3. Удаление кнопок и отображение предложения в истории

Зависимости: W2, F1–F2. Результат — пользователь проходит сценарий без панели управления импортом.

- [x] RED: `ScheduleConversationUiTest` проверяет отсутствие пяти контролов, наличие всех 150 строк preview и всех изменений, связи карточки с конкретным сообщением/версией, восстановление после reload.
- [x] Удалить confirm/correct/cancel/refresh/operation selector из `ScheduleImportPanel`; read-only представление перенести в timeline, выделив небольшой компонент при необходимости. Сохранить доступность всех строк прокруткой и видимый вопрос о применении.
- [x] Удалить зависимость UI от прямого commit; обновить старые кнопочные tests в пользу server reply path, не удаляя их проверки security/полноты/replay. Интегрировать F1–F2, локализацию и восстановление receipts со ссылкой либо нейтральным fallback без доступа к доменным данным.
- [x] Проверить native hub/direct chat, смену беседы, reload pending/applied, новую операцию вместо старой, отсутствие повторяющихся предложений при retry. Выполнить IDE inspections и render walk. Независимое ревью, исправления, отдельный коммит.

### W4. Сквозная приёмка нового workflow

Зависимости: F1–F3, W1–W3. Результат — evidence для A21–A26 без переиспользования старого отчёта как доказательства.

- [x] Добавить `ScheduleConversationAcceptanceTest`: upload/ранее отправленный файл → реальные tools → persisted proposal → persisted «Да, применить» → commit → результат и отчёты. Подтвердить нулевые domain writes до согласия, единственный результат после retry, полный snapshot Addendum.
- [x] Матрица A21–A26: PAYMENT и SHIPMENT; XLS/XLSX/CSV и текст; CREATE/FILL/REPLACE/ADDENDUM; «нет», исправление, mixed assent, инструкция из файла; несколько одноимённых файлов, длинная история, недоступный источник, stale rights/data, reload и параллельные ответы. Распределить тесты по W1–W3/F1–F3, не дублировать всё только в E2E.
- [x] Полный `./gradlew clean test` на disposable PostgreSQL с тестовым AI endpoint, прочитать JUnit XML; не запускать записи тестов на рабочей БД.
- [x] Browser walk с файлом `tmp/payment-schedule-spec-005.xls`: один upload, видимая карточка, распознано 5 дат/750000.00, полные preview/diff, ответ в чате, ссылка/reload. Сохранение проверять на тестовых данных; live рабочий разговор ограничить preview, если отдельно не разрешено создавать конкретный бизнес-документ.
- [x] Независимое итоговое ревью нового diff и проверка соответствия A21–A26. Обновить спецификацию/план фактическими evidence и ограничениями, затем отдельный коммит. Не объявлять новый workflow готовым по результатам прежних 433 tests.

Порядок: W1 → F3 → W2 → F1/F2 → W3 → W4. Каждая F-задача также выполняется через RED → GREEN → целевую проверку → независимое ревью → отдельный коммит; новые UI/сервисные тесты покрывают перечисленные в F1–F3 cases. Разбиение допускает независимую подготовку read-only карточек, но финальная интеграция выполняется после фиксации серверных контрактов. Пользовательские локальные файлы не включать, merge/push этим планом не разрешаются.

W4, 19.11.2026. Код и тесты — коммит `6478599`. Итоговый чистый прогон с временными настройками памяти — 470 тестов в 47 наборах, без ошибок, падений и пропусков. Браузер на изолированной БД подтвердил один XLS, пять дат, 750000.00, сохранение одного ДС и открытие документа после reload. Подробности приведены в § 18.5 спецификации. Независимое итоговое ревью и scoped проверка последующих исправлений завершены, см. итог ниже.


## Итог обновлённого плана — 2026-11-19

| Задача | Реализация и исправления | Независимое ревью |
| --- | --- | --- |
| W1 | `61b943d`, `633a133`, `ae5008b` — доверенный ответ, повторы, порядок сообщений | Принято после двух раундов исправлений; 39 целевых тестов |
| F3 | `09a9985`, `96b7376` — исторические файлы, один проверенный снимок состояния | Принято; 92 исходных/72 финальных целевых теста |
| W2 | `49d5412`, `448eab1` — native pipeline, полное предложение и проверка показанного содержимого | Принято; 190 исходных/63 финальных целевых теста |
| F1 | `7410854` — карточки, обзор, скачивание | Принято; 55 тестов; браузерная проверка выполнена в W4 |
| F2 | `f1e2d09`, `cce4478` — статусы, обновление после ошибок и сохранение при отзыве прав | Принято; 126 финальных целевых тестов |
| W3 | `5dc400a`, `9181acf` — удаление панели, таблицы, совместимость старых квитанций | Принято; 62 исходных/45 финальных целевых тестов, отдельный Chromium render150/151 строк |
| W4 | `6478599`, `6ccdd28` — native acceptance, реальный browser/HTTP download, исправление имён и закрытия потока | Принято; полный clean470/47 до финальной волны |
| Финальная волна | `983a19e`, `2f62d80` — автоматическое восстановление предложения, имя листа, точный first-call count и воспроизводимые настройки тестов | Scoped итоговое ревью принято; полный clean474/47 |

Количество целевых тестов не суммируется: наборы пересекаются. Все итоговые JUnit XML прочитаны controller. Финальный код `2f62d80` прошёл clean test за2m47s с временными настройками test heap1600m/context cache3 (copyable инструкция в спецификации). После этого изменена только документация статуса. Блокирующих замечаний нет. Оставлены три minor-ограничения из §18.6: offset overview при конкурентном добавлении, стоимость поиска повторного старта по истории, нейтральный ответ при повторе восстановления без нового pending.

Решения controller: работа продолжена в согласованном checkout feature-ветки; native store не заменён, недостающие метаданные наблюдения хранятся в защищённых технических SQL-таблицах; минимальная интеграция UI перенесена в W2, оформление/очистка завершены W3; исторические SAVED-квитанции сохраняются без предположения об отсутствии старых данных; все проверки записи выполнялись на disposable PostgreSQL, live-provider quality не заявляется. Эти решения не требуют merge/push и не меняют пользовательскую конфигурацию.
