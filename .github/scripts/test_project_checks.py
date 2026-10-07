import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('checks', Path(__file__).with_name('verify-current-project.py'))
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class TextEncodingTest(unittest.TestCase):
    def test_russian_and_escaping(self):
        text = 'Доверять этому адресу Home Assistant? $localAddress \\"Отмена\\"\n'
        self.assertEqual(text, checks.check_text(text.encode('utf-8'), 'fixture.kt'))

    def test_rejects_replacement_even_in_valid_utf8(self):
        with self.assertRaises(ValueError):
            checks.check_text('Адрес \ufffd\ufffd'.encode('utf-8'), 'fixture.kt')

    def test_rejects_cp1251_bytes(self):
        with self.assertRaises(UnicodeDecodeError):
            checks.check_text('Локальный адрес'.encode('cp1251'), 'fixture.kt')

    def test_rejects_double_decoded_russian(self):
        for encoding in ('cp1251', 'latin1'):
            broken = 'Сохранить адрес'.encode('utf-8').decode(encoding)
            with self.subTest(encoding=encoding), self.assertRaises(ValueError):
                checks.check_text(broken.encode('utf-8'), 'fixture.kt')

    def test_rejects_control(self):
        with self.assertRaises(ValueError):
            checks.check_text(b'address\x00', 'fixture.xml')
