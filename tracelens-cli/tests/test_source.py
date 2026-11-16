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


@pytest.mark.parametrize("class_name", [
    "com.chargebee.ems.Foo$Inner", "com.chargebee.ems.Foo$1",
    "com.chargebee.ems.Foo$Inner$Nested",
])
def test_resolves_jvm_binary_inner_class_names(repo, class_name):
    assert source.source_line(class_name, 5, repo) == "return 42;"


@pytest.mark.parametrize("source_dir", [
    "src", "app-module/src", "framework-module/src",
    "service-module/src/main/java", "",
])
def test_resolves_ant_modules_gradle_modules_and_direct_source_root(tmp_path, source_dir):
    java_file = tmp_path / source_dir / "com" / "example" / "Handler.java"
    java_file.parent.mkdir(parents=True)
    java_file.write_text("package com.example;\nclass Handler { }\n")

    assert source.source_line("com.example.Handler", 2, str(tmp_path)) == "class Handler { }"


def test_source_lookup_can_cross_multiple_modules_in_one_checkout(tmp_path):
    for module, class_name in [("application", "Handler"), ("framework", "Router")]:
        java_file = tmp_path / module / "src" / "com" / "example" / f"{class_name}.java"
        java_file.parent.mkdir(parents=True)
        java_file.write_text(f"class {class_name} {{ }}\n")

    assert source.source_line("com.example.Handler", 1, str(tmp_path)) == "class Handler { }"
    assert source.source_line("com.example.Router", 1, str(tmp_path)) == "class Router { }"


def test_missing_source_root_returns_none(tmp_path):
    assert source.source_line("com.example.Handler", 1, str(tmp_path / "missing")) is None


def test_unknown_class_returns_none(repo):
    assert source.source_line("com.chargebee.ems.Missing", 1, repo) is None


def test_out_of_range_line_returns_none(repo):
    assert source.source_line("com.chargebee.ems.Foo", 9999, repo) is None


def test_negative_line_returns_none(repo):
    assert source.source_line("com.chargebee.ems.Foo", -1, repo) is None


def test_source_refreshes_after_edit(repo):
    assert source.source_line("com.chargebee.ems.Foo", 2, repo) == "class Foo {"
    path = Path(repo) / "src/main/java/com/chargebee/ems/Foo.java"
    path.write_text("package com.chargebee.ems;\nclass UpdatedFoo { }\n")
    assert source.source_line("com.chargebee.ems.Foo", 2, repo) == "class UpdatedFoo { }"


def test_source_location_is_explicit_current_checkout(repo):
    result = source.source_location("com.chargebee.ems.Foo$Inner", 5, repo)
    assert result["sourceCorrespondence"] == "unverified"
    assert result["path"].endswith("/Foo.java")
    assert result["editors"]["cursor"].startswith("cursor://file/")
    assert result["editors"]["vscode"].endswith(":5")
    assert source.source_location("com.chargebee.ems.Foo", 9999, repo) is None


@pytest.mark.parametrize("fqcn", ["../secret", "/etc/passwd", "com/foo", "a..B", "x?" ])
def test_source_rejects_path_input(repo, fqcn):
    assert source.source_location(fqcn, 1, repo) is None
    assert source.source_line(fqcn, 1, repo) is None


def test_source_rejects_symlink_escape(tmp_path):
    root = tmp_path / "checkout"
    root.mkdir()
    (tmp_path / "Secret.java").write_text("private source")
    (root / "Secret.java").symlink_to(tmp_path / "Secret.java")
    assert source.source_location("Secret", 1, str(root)) is None
    assert source.source_line("Secret", 1, str(root)) is None
