# 08. Тестирование и трассировка

| Документ | Содержимое |
| --- | --- |
| [nfr-methodology.md](nfr-methodology.md) | Методика проверки нефункциональных требований: что, как, при каких условиях и когда измеряем для каждого из 29 NFT |
| [traceability.md](traceability.md) | Матрица трассировки требований, версия 5: цель, требование, user story, процесс, операции OpenAPI и события AsyncAPI, тест-кейсы и модули кода для требований R1 (конец среза 3 Ф4) |
| [test-strategy.md](test-strategy.md) | Стратегия тестирования: восемь уровней, инструменты, назначение проверок 29 NFT и 44 инвариантам, сквозные сценарии, тесты безопасности, заглушки, время в тестах, окружения, нумерация TC, критерии выхода срезов |
| `TC-*.md` | Тест-кейсы по областям, в файле несколько тест-кейсов TC-NNN. Сейчас есть [TC-accounts.md](TC-accounts.md), [TC-sellers.md](TC-sellers.md), [TC-catalog.md](TC-catalog.md), [TC-keys.md](TC-keys.md), [TC-e2e.md](TC-e2e.md), [TC-performance.md](TC-performance.md), [TC-admin.md](TC-admin.md) и [TC-seller-api.md](TC-seller-api.md) |
| `_template-test-case.md` | Шаблон тест-кейса |

## Состояние

| Артефакт | Статус | Фаза |
| --- | --- | --- |
| Методика проверки NFR | готова | Ф1 |
| Матрица трассировки | v5, семь колонок заполнены у R1, «Тест-кейс» и «Код» собираются скриптом `gen_traceability_tc.py`, сверяется скриптом `check_traceability.py` | Ф1, Ф2, Ф4 |
| Стратегия тестирования | готова, сверяется скриптом `check_strategy.py` | Ф2 |
| Тест-кейсы | 244 тест-кейса в 12 файлах, все автоматизированы, сверяются с кодом скриптом `check_testcases.py`: `TC-accounts.md` 30 (TC-101 – TC-130), `TC-sellers.md` 22 (TC-201 – TC-222), `TC-catalog.md` 33 (TC-301 – TC-333), `TC-keys.md` 19 (TC-401 – TC-419), `TC-orders.md` 32 (TC-501 – TC-532), `TC-delivery.md` 13 (TC-601 – TC-613), `TC-support.md` 20 (TC-701 – TC-720), `TC-admin.md` 17 (TC-801 – TC-817), `TC-e2e.md` 17 (TC-001 – TC-017), `TC-concurrency.md` 14 (TC-030 – TC-045), `TC-performance.md` 7 (TC-062 – TC-070), `TC-seller-api.md` 20 (TC-950 – TC-969) | Ф4, Ф5 |
| Сверка «документы = код» | операции, события и темы Kafka сверяются с исходниками сервисов скриптом `check_code_docs.py` | Ф4 |
