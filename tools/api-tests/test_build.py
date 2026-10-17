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


if __name__ == '__main__':
    unittest.main()
