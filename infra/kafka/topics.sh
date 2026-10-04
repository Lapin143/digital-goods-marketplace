#!/bin/bash
# СОЗДАН СКРИПТОМ infra/kafka/gen_kafka.py ИЗ docs/06-api/asyncapi/asyncapi.yaml. РУКАМИ НЕ ПРАВИТЬ.
# Темы (17) и права доступа (18 команд) выпуска R1. Подключается сценарием infra/kafka/init.sh, который задаёт
# функции create_topic и allow. Права: WRITE издателю, READ потребителю и его группе, WRITE потребителя на тему недоставленных.
SPEC_HASH=c2d645f1f0bb

# Темы: имя, партиций, cleanup.policy, retention.ms (или - для сжатия по ключу)
create_topic audit.events 1 delete 2592000000
create_topic audit.events.dlq 1 delete 1209600000
create_topic catalog.events 3 delete 604800000
create_topic catalog.events.dlq 3 delete 1209600000
create_topic delivery.events 3 delete 604800000
create_topic delivery.events.dlq 3 delete 1209600000
create_topic identity.events 1 delete 604800000
create_topic identity.events.dlq 1 delete 1209600000
create_topic inventory.events 3 delete 604800000
create_topic inventory.events.dlq 3 delete 1209600000
create_topic notification.events 1 delete 604800000
create_topic order.events 3 delete 604800000
create_topic order.events.dlq 3 delete 1209600000
create_topic payment.events 3 delete 604800000
create_topic payment.events.dlq 3 delete 1209600000
create_topic platform.config 1 compact -
create_topic support.events 1 delete 604800000

# Права: сервис, операции и ресурсы
allow catalog-service --operation Write --operation Describe --topic audit.events --topic catalog.events --topic inventory.events.dlq
allow catalog-service --operation Read --operation Describe --topic inventory.events --topic platform.config
allow catalog-service --operation Read --group catalog-service
allow inventory-service --operation Write --operation Describe --topic audit.events --topic catalog.events.dlq --topic inventory.events --topic order.events.dlq
allow inventory-service --operation Read --operation Describe --topic catalog.events --topic order.events --topic platform.config
allow inventory-service --operation Read --group inventory-service
allow order-service --operation Write --operation Describe --topic audit.events --topic delivery.events.dlq --topic identity.events.dlq --topic inventory.events.dlq --topic order.events --topic payment.events.dlq
allow order-service --operation Read --operation Describe --topic delivery.events --topic identity.events --topic inventory.events --topic payment.events --topic platform.config
allow order-service --operation Read --group order-service
allow payment-service --operation Write --operation Describe --topic audit.events --topic order.events.dlq --topic payment.events
allow payment-service --operation Read --operation Describe --topic order.events --topic platform.config
allow payment-service --operation Read --group payment-service
allow delivery-service --operation Write --operation Describe --topic audit.events --topic delivery.events --topic identity.events.dlq --topic order.events.dlq
allow delivery-service --operation Read --operation Describe --topic identity.events --topic order.events --topic platform.config
allow delivery-service --operation Read --group delivery-service
allow platform-service --operation Write --operation Describe --topic audit.events --topic audit.events.dlq --topic catalog.events.dlq --topic delivery.events.dlq --topic identity.events --topic identity.events.dlq --topic notification.events --topic order.events.dlq --topic payment.events.dlq --topic platform.config --topic support.events
allow platform-service --operation Read --operation Describe --topic audit.events --topic catalog.events --topic delivery.events --topic identity.events --topic order.events --topic payment.events
allow platform-service --operation Read --group platform-service
