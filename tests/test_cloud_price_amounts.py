"""Regression checks for monetary amounts observed in cloud JSON."""
from server.cloud_browser import _amount


def test_decimal_ruble_strings_are_not_multiplied():
    assert _amount("3590.00") == 3590
    assert _amount("3 590,00") == 3590
    assert _amount("3 590.00") == 3590


def test_integer_ruble_strings_remain_unchanged():
    assert _amount("3 590") == 3590
    assert _amount(3590) == 3590
    assert _amount("99") == 0
