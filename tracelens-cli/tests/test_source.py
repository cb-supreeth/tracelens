import textwrap
from pathlib import Path

import pytest

from tracelens_cli import source


@pytest.fixture()
def repo(tmp_path: Path):
    src = tmp_path / "src" / "main" / "java" / "com" / "chargebee" / "ems"
    src.mkdir(parents=True)
    (src / "Foo.java").write_text(textwrap.dedent("""\
        package com.chargebee.ems;
        class Foo {
            class Inner {
                int bar() {
                    return 42;
                }
            }
        }
        """))
    return str(tmp_path)


def test_resolves_simple_class(repo):
    assert source.source_line("com.chargebee.ems.Foo", 2, repo) == "class Foo {"


def test_resolves_inner_class_to_outer_file(repo):
    # com.chargebee.ems.Foo.Inner -> Foo.java
    assert source.source_line("com.chargebee.ems.Foo.Inner", 5, repo) == "return 42;"


def test_unknown_class_returns_none(repo):
    assert source.source_line("com.chargebee.ems.Missing", 1, repo) is None


def test_out_of_range_line_returns_none(repo):
    assert source.source_line("com.chargebee.ems.Foo", 9999, repo) is None


def test_negative_line_returns_none(repo):
    assert source.source_line("com.chargebee.ems.Foo", -1, repo) is None
