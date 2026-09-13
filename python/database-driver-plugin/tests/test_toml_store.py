"""Port of ``TomlStoreTest``: human-readable files with faithful types, replace-only
updates, engine parity, and the store corruption contract."""

from __future__ import annotations

from pathlib import Path

import pytest
from conftest import entry
from database_driver.api import Credentials, JsonDocument, NoSuchDataFound, SectionConfig

from database_driver.plugin.database.nosql.toml import toml_document_mapper
from database_driver.plugin.database.nosql.toml.toml_database_provider import TOMLDatabaseProvider


@pytest.fixture()
def repo(tmp_path: Path) -> Path:
    return tmp_path / "repo"


@pytest.fixture()
def provider(registry, tmp_path: Path, repo: Path) -> TOMLDatabaseProvider:
    return TOMLDatabaseProvider(Credentials(tmp_path / "credentials.json", file_repository=repo))


def test_stores_human_readable_toml_with_faithful_types(provider, repo):
    section = provider.create_section("people")
    section.insert(entry("Lino", name="lino", age=23, gpa=1.7, enrolled=True))

    text = (repo / "people" / "Lino.toml").read_text(encoding="utf-8")
    assert 'id = "Lino"' in text
    assert "[data]" in text
    assert "age = 23" in text  # integral stays integral - not 23.0
    assert "gpa = 1.7" in text
    assert "enrolled = true" in text

    read_back = section.find_entry_by_id("Lino").get_meta_data()
    assert read_back.get_integer("age") == 23
    assert isinstance(read_back.json_object["age"], int)


def test_update_replaces_instead_of_merging(provider):
    section = provider.create_section("replace")
    section.insert(entry("doc", first="a", second="b"))

    section.update(entry("doc", second="changed"))

    data = section.find_entry_by_id("doc").get_meta_data()
    assert data.get_string("second") == "changed"
    assert not data.contains("first")  # replaced, not merged - unlike the JSON store


@pytest.mark.parametrize(
    "config",
    [SectionConfig.full(), SectionConfig.lazy(), SectionConfig.bounded(8), SectionConfig.none()],
    ids=["full", "lazy", "bounded", "none"],
)
def test_behaves_like_every_other_engine_backed_section(provider, config):
    section = provider.create_section(f"mode_{config.cache_mode.value.lower()}", config)
    section.insert(entry("a", n=1))
    section.update(entry("a", n=2))
    assert section.find_entry_by_id("a").get_meta_data().get_integer("n") == 2
    section.delete("a")
    assert section.count() == 0


def test_foreign_and_corrupt_files_follow_the_store_corruption_contract(provider, repo):
    directory = repo / "corrupted"
    directory.mkdir(parents=True)
    (directory / "broken.toml").write_text("this is [not TOML", encoding="utf-8")

    section = provider.create_section("corrupted", SectionConfig.none())
    with pytest.raises(NoSuchDataFound):
        section.find_entry_by_id("broken")


def test_null_values_are_dropped_and_nan_rejected():
    document = JsonDocument({"data": {"kept": 1, "dropped": None}})
    text = toml_document_mapper.to_toml(document)
    assert "kept" in text and "dropped" not in text

    with pytest.raises(ValueError):
        toml_document_mapper.to_toml(JsonDocument({"data": {"bad": float("nan")}}))


def test_discovered_lazily_like_every_other_provider(provider, repo):
    provider.create_section("known")
    provider.reload()
    assert provider.exists_section("known")
