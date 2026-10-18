# -*- coding: utf-8 -*-
"""Проверки сборщика API-тестов без стенда: python3 -m unittest discover -s tools/api-tests (входит в tools/docs-checks/run_all.sh).

Сценарий собирается в коллекцию Postman, а намеренно неверные сценарии отвергаются с понятной причиной: контроль, который ни разу не сработал,
ничего не гарантирует. Данные берутся из настоящих контрактов и реестров операций репозитория.
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402
import run as runner  # noqa: E402
import oas  # noqa: E402
import st02_matrix  # noqa: E402

OPS = oas.load()
MATRIX = st02_matrix.load_matrix()


def run(scenarios, **kwargs):
    with tempfile.TemporaryDirectory() as d:
        for i, scenario in enumerate(scenarios):
            with open(os.path.join(d, 's%d.json' % i), 'w', encoding='utf-8') as f:
                json.dump(scenario, f, ensure_ascii=False)
        return build.build(d, ops=OPS, matrix=MATRIX, **kwargs)


def good():
    return {'id': 'T-01', 'title': 'проверка', 'steps': [
        {'name': 'витрина', 'op': 'listStorefrontProducts', 'as': 'guest', 'query': {'limit': '5'}, 'expect': 200,
         'save': {'firstProduct': 'items.0.id'}, 'assert': [{'path': 'items', 'notEmpty': True}]},
        {'name': 'витрина ещё раз', 'op': 'listStorefrontProducts', 'as': 'buyer', 'query': {'q': '{{firstProduct}}'}, 'expect': [200, 304]}]}


class BuildTest(unittest.TestCase):
    def test_every_operation_has_service_matrix_row_and_roles(self):
        self.assertEqual(68, len(OPS))
        for op in OPS.values():
            self.assertTrue(set(op.matrix_ids) <= set(MATRIX), op.id)

    def test_matrix_cases_cover_every_role_for_done_operations(self):
        cases, problems = st02_matrix.cases(OPS, MATRIX)
        self.assertEqual([], problems)
        done = [i for i in OPS if OPS[i].reachable and oas.is_done(OPS, i)]
        self.assertTrue(done)
        self.assertEqual(len(done) * len(st02_matrix.ROLES), len(cases))

    def test_all_operations_option_adds_operations_without_handlers(self):
        done, _ = st02_matrix.cases(OPS, MATRIX)
        everything, _ = st02_matrix.cases(OPS, MATRIX, done_only=False)
        self.assertGreater(len(everything), len(done))

    def test_guest_is_refused_with_401_and_other_roles_with_403(self):
        cases, _ = st02_matrix.cases(OPS, MATRIX, done_only=False)
        denied = [c for c in cases if c['expect'] == 'deny']
        self.assertTrue(denied)
        item = build.matrix_item(next(c for c in denied if c['role'] == 'guest'), OPS[next(c for c in denied if c['role'] == 'guest')['operation']])
        self.assertIn('equal(401)', ''.join(item['event'][0]['script']['exec']))
        other = next(c for c in denied if c['role'] != 'guest')
        self.assertIn('equal(403)', ''.join(build.matrix_item(other, OPS[other['operation']])['event'][0]['script']['exec']))

    def test_good_scenario_builds_a_collection(self):
        collection, summary, problems = run([good()], with_matrix=False)
        self.assertEqual([], problems)
        self.assertEqual(1, summary['scenarios'])
        self.assertEqual(2, summary['requests'])
        self.assertEqual(['buyer', 'guest'], summary['roles'])
        steps = collection['item'][0]['item']
        self.assertEqual('{{baseUrl}}/api/v1/products?limit=5', steps[0]['request']['url']['raw'])
        self.assertEqual('Bearer {{token_buyer}}', steps[1]['request']['header'][0]['value'])
        self.assertIn('pm.collectionVariables.set("firstProduct"', ''.join(steps[0]['event'][0]['script']['exec']))

    def test_multipart_header_and_not_contains_steps_are_built(self):
        scenario = {'id': 'T-02', 'title': 'форма и заголовок', 'steps': [
            {'name': 'документ', 'op': 'uploadSellerDocument', 'as': 'buyer', 'expect': 415,
             'multipart': {'field': 'file', 'filename': 'a.pdf', 'contentType': 'application/pdf', 'content': 'не pdf'},
             'assert': [{'header': 'Content-Type', 'contains': 'application/problem+json'}, {'bodyNotContains': 'не pdf'}]}]}
        collection, summary, problems = run([scenario], with_matrix=False)
        self.assertEqual([], problems)
        step = collection['item'][0]['item'][0]
        headers = {h['key']: h['value'] for h in step['request']['header']}
        self.assertTrue(headers['Content-Type'].startswith('multipart/form-data; boundary='))
        raw = step['request']['body']['raw']
        self.assertTrue(raw.startswith('--' + build.BOUNDARY + '\r\nContent-Disposition: form-data; name="file"; filename="a.pdf"\r\nContent-Type: application/pdf\r\n\r\nне pdf'))
        self.assertTrue(raw.endswith('\r\n--' + build.BOUNDARY + '--\r\n'))
        script = ''.join(step['event'][0]['script']['exec'])
        self.assertIn('pm.response.headers.get("Content-Type")', script)
        self.assertIn('application/problem+json', script)
        self.assertIn('to.not.include("не pdf")', script)

    def test_multipart_is_refused_next_to_a_json_body_or_on_a_get(self):
        both = {'id': 'T-03', 'title': 'x', 'steps': [
            {'name': 'и то и другое', 'op': 'uploadSellerDocument', 'as': 'buyer', 'expect': 415, 'body': {}, 'multipart': {'content': 'x'}}]}
        _, _, problems = run([both], with_matrix=False)
        self.assertTrue(any('multipart допустим только у POST и вместо body' in p for p in problems), problems)
        on_get = {'id': 'T-04', 'title': 'x', 'steps': [
            {'name': 'GET с формой', 'op': 'listStorefrontProducts', 'as': 'guest', 'expect': 200, 'multipart': {'content': 'x'}}]}
        _, _, problems = run([on_get], with_matrix=False)
        self.assertTrue(any('multipart допустим только у POST' in p for p in problems), problems)

    def test_a_header_check_without_contains_is_refused(self):
        scenario = {'id': 'T-05', 'title': 'x', 'steps': [
            {'name': 'витрина', 'op': 'listStorefrontProducts', 'as': 'guest', 'expect': 200, 'assert': [{'header': 'ETag'}]}]}
        _, _, problems = run([scenario], with_matrix=False)
        self.assertTrue(any('проверка заголовка без contains' in p for p in problems), problems)

    def test_sabotage_flips_the_first_denial_and_reports_nothing_to_flip_without_one(self):
        collection, _, _ = run([])
        self.assertTrue(runner.sabotage(collection))
        self.assertIn('equal(200)', json.dumps(collection))
        self.assertFalse(runner.sabotage({'item': [{'name': 'x', 'item': []}]}))

    def test_empty_scenario_list_is_valid(self):
        collection, summary, problems = run([], with_matrix=False)
        self.assertEqual([], problems)
        self.assertEqual(0, summary['requests'])
        self.assertEqual([], collection['item'])

    def bad(self, mutate, expected):
        scenario = good()
        mutate(scenario)
        _, _, problems = run([scenario], with_matrix=False)
        self.assertTrue(any(expected in p for p in problems), 'ждали «%s», получили %s' % (expected, problems))

    def test_unknown_operation_is_rejected(self):
        self.bad(lambda s: s['steps'][0].update(op='noSuchOperation'), 'нет в OpenAPI')

    def test_operation_without_handler_is_rejected(self):
        later = next(i for i, op in OPS.items() if op.reachable and not oas.is_done(OPS, i) and any(c.isdigit() for c in op.responses))
        code = int(sorted(c for c in OPS[later].responses if c.isdigit())[0])
        self.bad(lambda s: s['steps'][0].update(op=later, expect=code, path=dict((p, 'x') for p in OPS[later].path_params), query={}),
                 'ещё не готова')

    def test_undocumented_status_is_rejected(self):
        self.bad(lambda s: s['steps'][0].update(expect=418), 'не описан в OpenAPI')

    def test_unknown_role_is_rejected(self):
        self.bad(lambda s: s['steps'][0].update(**{'as': 'wizard'}), 'неизвестная роль')

    def test_unsaved_variable_is_rejected(self):
        self.bad(lambda s: s['steps'][0].update(query={'q': '{{never}}'}), 'не сохранена')

    def test_duplicate_scenario_id_is_rejected(self):
        _, _, problems = run([good(), good()], with_matrix=False)
        self.assertTrue(any('повторяется' in p for p in problems), problems)

    def test_missing_and_extra_path_parameters_are_rejected(self):
        with_param = next(i for i, op in OPS.items() if op.reachable and op.path_params)
        code = int(sorted(c for c in OPS[with_param].responses if c.isdigit())[0])
        scenario = {'id': 'T-02', 'steps': [{'op': with_param, 'expect': code}]}
        original = oas.is_done
        oas.is_done = lambda *args, **kwargs: True
        try:
            _, _, problems = run([scenario], with_matrix=False)
            scenario['steps'][0]['path'] = dict((p, 'x') for p in OPS[with_param].path_params + ['extra'])
            _, _, extra = run([scenario], with_matrix=False)
        finally:
            oas.is_done = original
        self.assertTrue(any('не задан параметр пути' in p for p in problems), problems)
        self.assertTrue(any('нет параметра пути {extra}' in p for p in extra), extra)


def with_everything_done(func):
    """Операции среза 2 ещё не готовы по реестрам: на время теста все операции считаются готовыми (так проверяется сам сборщик)."""
    original = oas.is_done
    oas.is_done = lambda *args, **kwargs: True
    try:
        return func()
    finally:
        oas.is_done = original


WEBHOOK = {'eventId': 'evt_1', 'type': 'payment.paid', 'occurredAt': '2026-10-03T12:00:00.123Z',
           'data': {'paymentId': 'pay_1', 'orderId': '{{orderId}}', 'amount': {'amount': 100, 'currency': 'RUB'}}}


def webhook_scenario(**step):
    base = {'name': 'вебхук', 'op': 'receivePaymentGatewayNotification', 'as': 'guest', 'body': WEBHOOK, 'expect': 200}
    base.update(step)
    return {'id': 'T-03', 'title': 'вебхук', 'steps': [
        {'name': 'заказ', 'op': 'createOrder', 'as': 'buyer', 'set': {'orderKey': '{{$guid}}'}, 'headers': {'Idempotency-Key': '{{orderKey}}'},
         'body': {'productId': 'x', 'quantity': 1}, 'expect': 201, 'save': {'orderId': 'id'}}, base]}


class WebhookAndVariablesTest(unittest.TestCase):
    def build(self, scenario):
        return with_everything_done(lambda: run([scenario], with_matrix=False))

    def test_set_variable_and_explicit_idempotency_key_are_built(self):
        collection, _, problems = self.build(webhook_scenario(sign={}))
        self.assertEqual([], problems)
        order = collection['item'][0]['item'][0]
        headers = dict((h['key'], h['value']) for h in order['request']['header'])
        self.assertEqual('{{orderKey}}', headers['Idempotency-Key'])
        pre = next(e for e in order['event'] if e['listen'] == 'prerequest')
        self.assertIn('pm.collectionVariables.set("orderKey", pm.variables.replaceIn("{{$guid}}"))', ''.join(pre['script']['exec']))

    def test_signed_webhook_gets_signature_script_without_secret_value(self):
        collection, _, problems = self.build(webhook_scenario(sign={}))
        self.assertEqual([], problems)
        hook = collection['item'][0]['item'][1]
        pre = ''.join(next(e for e in hook['event'] if e['listen'] == 'prerequest')['script']['exec'])
        for part in ('secret_payment_webhook_secret', 'X-Webhook-Timestamp', 'X-Webhook-Signature', 'HmacSHA256(stamp + "." + body, secret)'):
            self.assertIn(part, pre)
        self.assertEqual(['payment_webhook_secret'], runner.required_secrets(collection))

    def test_stale_and_bad_signature_options_change_the_script(self):
        collection, _, problems = self.build(webhook_scenario(sign={'timestampOffsetSeconds': -600, 'badSignature': True}))
        self.assertEqual([], problems)
        pre = ''.join(next(e for e in collection['item'][0]['item'][1]['event'] if e['listen'] == 'prerequest')['script']['exec'])
        self.assertIn('Date.now() + -600 * 1000', pre)
        self.assertIn('signature[0] === "0"', pre)

    def test_webhook_without_signature_is_rejected_unless_unsigned(self):
        _, _, problems = self.build(webhook_scenario())
        self.assertTrue(any('требует подпись' in p for p in problems), problems)
        _, _, problems = self.build(webhook_scenario(unsigned=True, expect=401))
        self.assertEqual([], [p for p in problems if 'подпись' in p])

    def test_sign_on_operation_without_webhook_secret_is_rejected(self):
        scenario = good()
        scenario['steps'][0]['sign'] = {}
        _, _, problems = self.build(scenario)
        self.assertTrue(any('нет подписи вебхука' in p for p in problems), problems)

    def test_dynamic_variable_in_signed_body_is_rejected(self):
        body = json.loads(json.dumps(WEBHOOK))
        body['eventId'] = '{{$guid}}'
        _, _, problems = self.build(webhook_scenario(sign={}, body=body))
        self.assertTrue(any('динамическим переменным' in p for p in problems), problems)

    def test_unknown_sign_key_is_rejected(self):
        _, _, problems = self.build(webhook_scenario(sign={'secret': 'x'}))
        self.assertTrue(any('sign это объект' in p for p in problems), problems)

    def test_secrets_come_from_files_and_missing_one_is_reported(self):
        collection, _, _ = self.build(webhook_scenario(sign={}))
        with tempfile.TemporaryDirectory() as d:
            values, error = runner.secrets_for(collection, d)
            self.assertIsNone(values)
            self.assertIn('payment_webhook_secret', error)
            with open(os.path.join(d, 'payment_webhook_secret'), 'w', encoding='utf-8') as f:
                f.write('s3cret\n')
            values, error = runner.secrets_for(collection, d)
            self.assertEqual({'secret_payment_webhook_secret': 's3cret'}, values)
            self.assertIsNone(error)
        self.assertNotIn('s3cret', json.dumps(collection))

    def test_signature_script_produces_what_the_stub_verifies(self):
        """Скрипт подписи выполняется в Node с макетом песочницы Postman; подпись принимает та же проверка, что у заглушки платёжного шлюза."""
        import shutil
        import subprocess
        node = shutil.which('node')
        if not node:
            self.skipTest('нет node')
        collection, _, problems = self.build(webhook_scenario(sign={}))
        self.assertEqual([], problems)
        script = '\n'.join(next(e for e in collection['item'][0]['item'][1]['event'] if e['listen'] == 'prerequest')['script']['exec'])
        stub = os.path.join(oas.REPO, 'tools', 'external-stubs', 'src', 'signing.mjs')
        harness = """
import { createHmac } from 'node:crypto';
import { verify } from %s;
const body = JSON.stringify({ eventId: 'evt_1', note: 'Привет' });
const headers = {};
const CryptoJS = { HmacSHA256: (m, k) => ({ toString: () => createHmac('sha256', k).update(m, 'utf8').digest('hex') }), enc: { Hex: 1 } };
const pm = { environment: { get: () => 's3cret' }, variables: { replaceIn: (t) => t },
  request: { body: { raw: body }, headers: { upsert: (h) => { headers[h.key] = h.value; } } } };
new Function('pm', 'CryptoJS', %s)(pm, CryptoJS);
const result = verify({ secrets: ['s3cret'], timestamp: headers['X-Webhook-Timestamp'], body, signature: headers['X-Webhook-Signature'] });
console.log(JSON.stringify({ result, stamp: headers['X-Webhook-Timestamp'] }));
""" % (json.dumps('file://' + stub), json.dumps(script))
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, 'sign.mjs')
            with open(path, 'w', encoding='utf-8') as f:
                f.write(harness)
            out = subprocess.run([node, path], capture_output=True, text=True, timeout=30)
        self.assertEqual(0, out.returncode, out.stderr)
        answer = json.loads(out.stdout)
        self.assertEqual({'ok': True}, answer['result'])
        self.assertRegex(answer['stamp'], r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$')

    def test_slice2_operations_are_covered_by_matrix_or_marked_as_not_reachable(self):
        """Каждая операция среза 2 либо имеет строки ST-02 (доступна через шлюз с токеном), либо внутренняя, либо подписанный вебхук."""
        with_rows = {c['operation'] for c in st02_matrix.cases(OPS, MATRIX, done_only=False)[0]}
        slice2 = [i for i, op in OPS.items() if oas.ledger(op.service).get(i) in ('slice2', 'done') and i in SLICE2_OPERATIONS]
        self.assertEqual(11, len(slice2), slice2)
        for op_id in slice2:
            op = OPS[op_id]
            internal = not op.path.startswith('/api/v1/') or bool(op.webhook_secret)
            self.assertTrue(op_id in with_rows or internal, '%s: нет строк ST-02 и не внутренняя операция' % op_id)
        self.assertEqual({'createOrder', 'getOrder', 'getOrderPaymentSession', 'listManualRefunds', 'completeManualRefund'},
                         {i for i in slice2 if i in with_rows})


SLICE2_OPERATIONS = {'reserveKeys', 'confirmReservation', 'getKeyValues', 'createOrder', 'getOrder', 'getOrderPaymentSession', 'openPaymentSession',
                     'receivePaymentGatewayNotification', 'listManualRefunds', 'completeManualRefund', 'receiveKeyEmailStatus'}


if __name__ == '__main__':
    unittest.main()
