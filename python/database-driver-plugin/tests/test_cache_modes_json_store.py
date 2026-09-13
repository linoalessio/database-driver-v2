"""Port of ``CacheModeJsonStoreTest``: the four cache modes round-trip identically on
the JSON file store, and the engine's per-mode semantics hold."""

from __future__ import annotations

from pathlib import Path

import pytest
from conftest import entry
from database_driver.api import (
    CacheMode,
    Credentials,
    DataAlreadyExist,
    NoSuchEntryFound,
    SectionConfig,
)

from database_driver.plugin.database.nosql.json.json_database_provider import JsonDatabaseProvider


@pytest.fixture()
def provider(registry, tmp_path: Path) -> JsonDatabaseProvider:
    credentials = Credentials(tmp_path / "credentials.json", file_repository=tmp_path / "repo")
    return JsonDatabaseProvider(credentials)


def _round_trip(section) -> None:
    section.insert(entry("alpha", value=1))
    section.insert(entry("beta", value=2))

    assert section.count() == 2
    assert section.exists("alpha")
    assert not section.exists("gamma")

    found = section.find_entry_by_id("beta")
    assert found is not None and found.get_meta_data().get_integer("value") == 2

    with pytest.raises(DataAlreadyExist):
        section.insert(entry("alpha", value=99))

    section.update(entry("alpha", value=42))
    assert section.find_entry_by_id("alpha").get_meta_data().get_integer("value") == 42

    with pytest.raises(NoSuchEntryFound):
        section.update(entry("ghost", value=0))
    with pytest.raises(NoSuchEntryFound):
        section.delete("ghost")

    section.delete("beta")
    assert section.count() == 1

    section.clear()
    assert section.count() == 0
    assert section.get_entries() == []


@pytest.mark.parametrize(
    "config",
    [SectionConfig.full(), SectionConfig.lazy(), SectionConfig.bounded(16), SectionConfig.none()],
    ids=["full", "lazy", "bounded", "none"],
)
def test_mode_round_trip(provider, config):
    _round_trip(provider.create_section(f"mode_{config.cache_mode.value.lower()}", config))


def test_sections_survive_and_rediscover_across_provider_reload(provider):
    section = provider.create_section("persistent")
    section.insert(entry("kept", value=7))

    provider.reload()

    assert provider.exists_section("persistent")
    reloaded = provider.get_section("persistent")
    assert reloaded is not None
    assert reloaded.find_entry_by_id("kept").get_meta_data().get_integer("value") == 7


def test_bounded_read_through_sees_rows_written_before_its_creation(provider, tmp_path):
    # Seed through a FULL section, then re-declare as BOUNDED: the read-through cache
    # must see the pre-existing rows via its loader.
    provider.create_section("seeded").insert(entry("early", value=1))

    bounded = provider.create_section("seeded", SectionConfig.bounded(4))
    assert bounded.get_config().cache_mode is CacheMode.BOUNDED
    assert bounded.find_entry_by_id("early").get_meta_data().get_integer("value") == 1


def test_json_update_merges_when_document_has_no_id(provider):
    section = provider.create_section("merge")
    section.insert(entry("doc", first="a", second="b"))

    # An update whose document lacks "id" merges on top of the stored metadata.
    section.update(entry("doc", second="changed"))

    merged = section.find_entry_by_id("doc").get_meta_data()
    assert merged.get_string("second") == "changed"
