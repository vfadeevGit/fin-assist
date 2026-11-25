# UX Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for inline execution, or superpowers:subagent-driven-development if the user selects delegated execution. Steps use checkbox syntax for tracking.

**Goal:** Реализовать согласованное меню, единый ввод дат и штатные фильтры колонок для #25 и #12.

**Architecture:** Штатные фильтры DataGrid и их URL-привязка работают совместно с существующим genericFilter. Общая настройка полей дат покрывает XML и динамически создаваемые фильтры. Меню использует существующие экраны и права; подписи корректировок отделены от названий журналов.

**Tech Stack:** Jmix 3.1.0, Java 21, Vaadin 25, Spring, Gradle, существующая тема и FlowuiTestAssist.

**Spec:** [.spec/features/spec-007-ux-menu-dates-column-filters.md](../../../.spec/features/spec-007-ux-menu-dates-column-filters.md).

## Global Constraints

- Работа в `feature/ux-improvements`; реализация начинается отдельным этапом после согласования документов.
- Jmix 3.1.0, Java 21, Vaadin 25; точную разрешённую версию Vaadin определить из зависимостей, не выбирать самый новый JAR в кэше.
- Не создавать собственные компоненты фильтрации или новую панель полей. Универсальный фильтр остаётся.
- «Сбросить все» очищает только фильтры колонок; оба вида фильтров восстанавливаются при возврате из карточки.
- Меню: точные подписи «AI Tools», «Корректировка отгрузки», «Корректировка оплаты», «Аналитика и отчеты».
- Короткие названия корректировок применяются только в меню. «Подразделения» заменяет русские формулировки справочника без изменения модели данных.
- Форматы ввода: `ddMMyy`, `ddMMyyyy`, `dd.MM.yy`, `dd.MM.yyyy`; вывод `dd.MM.yyyy`; двухзначный год = `2000 + YY`.
- Главная и её маршрут сохраняются; функции #20–24, #26–27 не реализуются в этой работе.
- Сохранять существующие права, не добавлять wildcard-политики. Не добавлять в приложение переключатель роли и условное содержимое главной из макета.
- Перед изменением каждого типа артефактов прочитать соответствующие навыки по маршрутизации `jmix`, включая проектный `finassist-ui-scaffolding` для представлений.

## Review Focus

1. Фильтр по вложенному `contract.customer` должен работать по всей выборке, не только по текущей странице — задача 3.
2. Сброс и восстановление URL не должны возвращать удалённые условия или стирать genericFilter — задача 4.
3. Динамические и повторно открытые поля дат должны принимать компактный ввод и годы `00/99` — задача 2 и браузерная проверка задачи 5.
4. Новые пункты меню не должны выдавать доступ к AI/пользователям или дублировать меню дополнений — задача 1.
5. Ввод неправильной даты не должен незаметно сохранять прежнюю либо нормализованную дату — задача 2 и задача 5.

## Карта файлов

Существующие файлы (пути относительно корня):

- `src/main/resources/ru/stnovator/finassist/menu.xml`: состав и порядок навигации.
- `src/main/resources/ru/stnovator/finassist/messages_ru.properties`: подписи меню, подразделения, сброса и ошибок при необходимости.
- `frontend/themes/fin-assist/view/main-view.css`: выделение AI, перенос длинных подписей; без переделки dashboard.
- `src/main/resources/ru/stnovator/finassist/view/main/main-view.xml`: только если требуется привязка класса меню; маршрут и initialLayout сохраняются.
- `src/main/resources/ru/stnovator/finassist/view/{customer/customer,contract/contract,project/project,addendum/addendum}-list-view.xml`: четыре конкретных дескриптора списков.
- `src/main/java/ru/stnovator/finassist/view/{customer/Customer,contract/Contract,project/Project,addendum/Addendum}ListView.java`: подключение общего сброса.
- `src/main/java/ru/stnovator/finassist/view/support/RelatedListView.java`: только необходимые изменения совместимости восстановления; не переписывать навигацию.
- `src/main/java/ru/stnovator/finassist/view/addendum/AddendumEndDateField.java`: проверить совместимость общего механизма дат с этим специализированным полем.
- `src/main/java/ru/stnovator/finassist/security/FinanceManagerRole.java`: изменять только если существующие полномочия на экран требуют соответствующего нового menu ID; не выдавать право AI пользователям без него.

Предлагаемые новые файлы, ответственность не смешивать:

- `src/main/java/ru/stnovator/finassist/view/support/ApplicationDatePickerConfigurer.java`: единая настройка календарных дат.
- `src/main/java/ru/stnovator/finassist/view/support/DatePickerConfigurationPostProcessor.java`: применение настройки после создания компонентов Spring/Jmix, включая динамические.
- `src/main/java/ru/stnovator/finassist/view/support/ColumnFilterSupport.java`: общий сброс штатных фильтров без изменения genericFilter.
- `src/test/java/ru/stnovator/finassist/view/main/MenuNavigationUiTest.java`.
- `src/test/java/ru/stnovator/finassist/view/detail/DateInputConfigurationUiTest.java`.
- `src/test/java/ru/stnovator/finassist/view/filter/ColumnFiltersUiTest.java`.
- `src/test/java/ru/stnovator/finassist/view/filter/ColumnFilterNavigationUiTest.java`.

Если выбранный штатный механизм делает вспомогательный файл ненужным, не создавать его ради плана; зафиксировать фактический путь в этом документе.

---

## Задача 1: меню и терминология (#25, N01–N04)

**Files:** menu.xml, messages_ru.properties, main-view.css, при необходимости main-view.xml и существующие политики; MenuNavigationUiTest.java. Прочитать `jmix-add-i18n-keys`, `jmix-style-ui`, `jmix-create-resource-role`, `finassist-ui-scaffolding` перед соответствующими изменениями.

**Interfaces:** использует существующий экран `aitls_AiChatHubView` (подтверждён в OpenAiToolsAction) и экран запуска Reports; новых бизнес-сервисов и экранов не производит.

- [ ] Проверить в исходниках разрешённых зависимостей Reports ID экрана запуска, фактическое объединение меню дополнений и доступные значки. Сопоставить итоговое меню с N01, сохранив разрешённые дополнительные административные пункты без дубликатов.
- [ ] Создать `MenuNavigationUiTest`: `aiEntryIsFirstAndPermissionAware` проверяет порядок и доступ; `opensExistingAiAndReportScreens` проверяет реальные переходы; `usersHiddenWithoutPermission` — скрытие пользователей и пустой группы; `homeRouteIsUnchanged` — сохранение главной. Использовать существующий образец AiToolsShortcutUiTest, не выдумывать новые роли доступа к AI.
- [ ] Запустить `./gradlew test --tests '*MenuNavigationUiTest'`; убедиться, что новые проверки падают на отсутствующих пунктах/старой структуре, а не из-за окружения.
- [ ] Перестроить меню точно по N01. Использовать отдельные ключи для двух коротких подписей и подписи запуска отчётов. Сохранить отдельное ДС. Связать AI с уже существующим центром чатов без побочных операций.
- [ ] Добавить умеренный акцент AI и понятную иконку; сохранить различие постоянного акцента и текущего выбранного пункта. Обеспечить перенос длинного названия ДС, клавиатурный фокус и читаемость узкого меню.
- [ ] Переименовать русские формулировки справочника по N04; выполнить поиск по `Направлен`, `направлен`, `LineOfBusiness`, `lineOfBusiness` и проверить контекст каждого совпадения. Технические имена и несвязанные значения не заменять.
- [ ] Запустить `./gradlew test --tests '*MenuNavigationUiTest' --tests '*AiToolsShortcutUiTest' --tests '*MainViewUiTest'`; ожидается BUILD SUCCESSFUL. Проверить UI-подписи и отсутствие повторных пунктов в браузере.
- [ ] После проверки создать отдельный коммит `feat(ui): reorganize navigation and localize departments`, включив только файлы задачи.

## Задача 2: единая настройка дат (#25, D01–D02)

**Files:** ApplicationDatePickerConfigurer.java, DatePickerConfigurationPostProcessor.java, DateInputConfigurationUiTest.java; при необходимости специализированное AddendumEndDateField.java. Перед реализацией прочитать `jmix-verify-api-symbol`, `jmix-create-test` и навыки соответствующих UI-артефактов.

**Interfaces:** новый собственный интерфейс `void ApplicationDatePickerConfigurer.configure(com.vaadin.flow.component.datepicker.DatePicker picker)`; интеграция — `Object DatePickerConfigurationPostProcessor.postProcessAfterInitialization(Object bean, String beanName)`. Конфигуратор идемпотентен, не изменяет значение, обязательность, min/max и read-only поля.

- [ ] Разрешить точную версию date-picker через Gradle и изучить её реальные API и клиентский парсер. Проверить поддержку четырёх форматов и закреплённого диапазона 2000–2099 в браузере. `setDateFormats` и reference date — кандидаты, не доказательство корректного разбора компактных цифр. Если они не обеспечивают D01, применить один общий клиентский parse/format adapter через поддерживаемую точку расширения; не копировать парсеры по формам. Записать проверенный механизм и фактические файлы адаптера в план до его реализации.
- [ ] Создать `DateInputConfigurationUiTest`: `configuresDocumentAndDynamicFilterDates`, `preservesCalendarValidationAndReadOnly`, `configuresCustomAddendumField`. Проверять конфигурацию реальных полей документа, обоих фильтров и специального поля ДС; не подменять клиентский ввод серверным setValue в качестве доказательства парсинга.
- [ ] Запустить `./gradlew test --tests '*DateInputConfigurationUiTest'`; ожидается падение из-за отсутствующей общей настройки.
- [ ] Реализовать конфигуратор и применить после инициализации каждого соответствующего DatePicker, создаваемого Jmix/Spring. Сохранить русские названия календаря. Проверить, что XML-загрузка и повторное открытие фильтра не перезаписывают настройки. Если фабричный lifecycle требует иной точки подключения, использовать подтверждённую регистрацию Jmix и зафиксировать замену post-processor; не ограничиваться обходом компонентов в ReadyEvent.
- [ ] В браузере проверить `251126`, `25112026`, `25.11.26`, `25.11.2026` → `25.11.2026`; `010100` → `01.01.2000`, `311299` → `31.12.2099`; `290224` допустимо, `290225`, `310426`, `251326` отклоняются. Проверить также очистку, выбор календарём, повторное открытие фильтра, границы диапазона и уход из поля с неверным вводом.
- [ ] Запустить `./gradlew test --tests '*DateInputConfigurationUiTest' --tests '*AddendumTermsUiTest' --tests '*DetailValidationUiTest'`; ожидается BUILD SUCCESSFUL.
- [ ] После проверок создать коммит `feat(ui): unify date entry across documents and filters`.

## Задача 3: штатные фильтры и независимый сброс (#12, F01–F03)

**Files:** четыре list-view.xml и их контроллеры, ColumnFilterSupport.java, messages_ru.properties, ColumnFiltersUiTest.java. Прочитать `jmix-create-list-view`, `jmix-configure-fetch-plan`, `jmix-create-test`, `finassist-ui-scaffolding`.

**Interfaces:** новый собственный метод `void ColumnFilterSupport.clearColumnFilters(io.jmix.flowui.component.grid.DataGrid<?> grid)`. Управляет только штатными header filters указанной таблицы; не очищает loader condition целиком и не меняет genericFilter. Точный доступ к header filter и уведомлению URL binder проверить по Jmix 3.1.0 до написания кода.

- [ ] Создать параметризованный `ColumnFiltersUiTest` для четырёх журналов: `dataColumnsAreFilterable`, `serviceColumnHasNoFilter`, `columnFiltersIntersectGenericFilter`, `clearAllPreservesGenericFilter`, `nestedCustomerFilterUsesFullDataset`. Использовать данные с совпадениями за пределами первой страницы и двумя независимыми условиями, включая сумму/дату и ссылку на сущность.
- [ ] Запустить `./gradlew test --tests '*ColumnFiltersUiTest'`; ожидается падение на отсутствующей штатной фильтрации.
- [ ] Включить `filterable` на всех колонках из F01, исключив `relatedDetails`; проверить атрибут по XSD Jmix 3.1.0. Сохранить существующие loader, genericFilter, pagination, сортировки и действия.
- [ ] Подключить штатную URL-привязку `dataGridFilter` к каждому grid рядом с уже имеющимися привязками genericFilter и pagination. Параметры двух механизмов не пересекаются.
- [ ] Реализовать общий сброс и кнопку «Сбросить все» в существующей панели действий каждого журнала. Обновить состояние штатных фильтров и URL штатными событиями; не использовать reflection/private API. Описание кнопки — «Сбросить фильтры колонок».
- [ ] Проверить AND между фильтрами колонок и выражением genericFilter, корректный возврат на первую страницу при изменении условий и сброс раскрытий через существующий lifecycle. При необходимости добавить минимальную общую обработку без дублирования загрузок.
- [ ] Запустить `./gradlew test --tests '*ColumnFiltersUiTest'`; ожидается BUILD SUCCESSFUL. Проверить реальные popup-фильтры, значения типов, состояние кнопок, сортировку и пустую выдачу в браузере.
- [ ] Создать коммит `feat(ui): enable native column filters with scoped reset`.

## Задача 4: восстановление фильтров при возврате (#12, F04)

**Files:** ColumnFilterNavigationUiTest.java, при необходимости RelatedListView.java, AddendumListView.java и URL-привязки четырёх дескрипторов.

**Interfaces:** использует штатные URL-параметры задачи 3; не создаёт статическое хранилище пользовательского состояния. Существующий RelatedListView сохраняет своё состояние для клиента, контракта и проекта; журнал ДС проверяется отдельно.

- [ ] Создать `ColumnFilterNavigationUiTest`, параметризованный четырьмя журналами: `restoresBothFilterLayersAfterDetail`, `doesNotRestoreClearedColumnFilters`, `lookupDoesNotLeakStandaloneState`. Установить несколько фильтров и нетривиальную операцию, открыть карточку, вернуться; проверить значения, операции, индикаторы, выдачу и состояние genericFilter.
- [ ] Запустить `./gradlew test --tests '*ColumnFilterNavigationUiTest'`; локализовать реальные пробелы восстановления. Не менять рабочее поведение только ради симметрии контроллеров.
- [ ] Устранить только обнаруженные пробелы, сохранив согласованность query parameters и восстановление страницы/сортировки/выделения. Не переносить состояние между пользователями и не включать связанные раскрытия в lookup.
- [ ] Запустить `./gradlew test --tests '*ColumnFilterNavigationUiTest' --tests '*RelatedNavigationUiTest'`; ожидается BUILD SUCCESSFUL.
- [ ] Повторить переход в карточку и назад в браузере для каждого журнала, включая возврат после сохранения и отмены. Убедиться, что очищенные фильтры не появляются снова.
- [ ] Создать отдельный коммит при наличии изменений восстановления: `fix(ui): preserve column filters across detail navigation`; если настройка задачи 3 уже достаточна, коммит содержит только регрессионные проверки.

## Задача 5: итоговая проверка и документация

**Files:** затронутые ранее файлы и эта спецификация/план; новые продуктовые артефакты не предполагаются.

- [ ] Выполнить самопроверку покрытия N01–N04, D01–D02, F01–F04 по таблице ниже. Для каждой проверки записать результат и фактический способ проверки; не отмечать выполненное заранее.
- [ ] Прочитать `jmix-ide-static-analysis`, выполнить Jmix-aware IDE inspections для каждого изменённого файла и исправить найденные проблемы.
- [ ] Прочитать `jmix-verify-bootrun`; выполнить завершающийся `./gradlew clean test`. Контекст, Liquibase и тесты должны завершиться успешно. Не использовать бесконечный bootRun как gate проверки контекста.
- [ ] На запущенном для UI-проверки экземпляре проверить меню при широком и узком окне, клавиатуру, права, реальные переходы AI/Reports, отсутствие изменений главной. Проверить все клиентские сценарии дат и оба фильтра; сохранить доказательства отдельно от продуктового кода.
- [ ] Проверить `git diff --check` и итоговый diff. Не включать временные `.superpowers/`, `.playwright-mcp/`, серверные файлы и screenshots в продуктовый коммит.
- [ ] Обновить выполненные пункты плана и результаты проверок. Подготовить итоговое описание изменений и ограничений; закрытие GitHub issues и публикация PR — отдельные действия по принятому рабочему процессу, не часть текущего запроса на документы.

## Матрица покрытия

| Требования | Задача | Проверка |
|---|---|---|
| N01–N03 | 1, 5 | MenuNavigationUiTest, роли, браузер, AI/Reports, главная |
| N04 | 1, 5 | Контекстный поиск локализации, подписи меню и редакторов |
| D01–D02 | 2, 5 | DateInputConfigurationUiTest и реальный браузерный ввод |
| F01–F03 | 3, 5 | ColumnFiltersUiTest, вложенное свойство, страницы, независимый сброс |
| F04 | 4, 5 | ColumnFilterNavigationUiTest, RelatedNavigationUiTest и возврат в браузере |

## Порядок выполнения и текущий статус

Задачи 1 и 2 дают самостоятельные результаты #25. Задача 3 использует единое поведение дат; задача 4 проверяет интеграцию навигации; задача 5 закрывает весь объём. Рекомендуется последовательное выполнение в текущей ветке без одновременного редактирования общих XML и локализации.

Сейчас подготовлены только документы. Код не реализован, тесты приложения не запускались. Спецификация прошла независимую проверку на существенные пробелы: таких вопросов не найдено. Перед выполнением уточнить выбранный пользователем способ работы: самостоятельно в этой сессии или с делегированием задач агентам.
