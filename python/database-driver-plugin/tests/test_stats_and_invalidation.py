"""Port of ``StatsAndInvalidationTest``: the engine's hit/miss accounting and the
external-invalidation hook, over the JSON store."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from conftest import entry
from database_driver.api import Credentials, SectionConfig

from database_driver.plugin.database.nosql.json.json_database_provider import JsonDatabaseProvider


@pytest.fixture()
def repo(tmp_path: Path) -> Path:
    return tmp_path / "repo"


@pytest.fixture()
def provider(registry, tmp_path: Path, repo: Path) -> JsonDatabaseProvider:
    return JsonDatabaseProvider(Credentials(tmp_path / "credentials.json", file_repository=repo))


def _write_external(repo: Path, section: str, id: str, value: str) -> None:
    """Simulates another process writing an entry file directly."""
    directory = repo / section
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{id}.json").write_text(json.dumps({"id": id, "data": {"value": value}}), encoding="utf-8")


def test_bounded_stats_separate_hits_from_misses(provider):
    section = provider.create_section("counted", SectionConfig.bounded(8))
    section.insert(entry("x", value=1))

    # insert() primed the cache, so the first lookup is a hit; a missing id is a miss.
    section.find_entry_by_id("x")
    section.find_entry_by_id("absent")

    stats = section.stats()
    assert stats.cache_hits >= 1
    assert stats.cache_misses >= 1
    assert 0.0 <= stats.cache_hit_ratio() <= 1.0


def test_full_load_counters_track_warmup_and_reload(provider):
    section = provider.create_section("loaded")  # FULL: warm at creation
    initial = section.stats().full_loads
    assert initial >= 1

    section.reload()
    assert section.stats().full_loads == initial + 1
    assert section.stats().full_load_nanos > 0


def test_external_invalidate_evicts_from_a_bounded_cache(provider, repo):
    section = provider.create_section("watched", SectionConfig.bounded(8))
    section.insert(entry("row", value="old"))
    assert section.find_entry_by_id("row").get_meta_data().get_string("value") == "old"

    # Another process changes the row on disk; the cached copy is now stale.
    _write_external(repo, "watched", "row", "new")
    assert section.find_entry_by_id("row").get_meta_data().get_string("value") == "old"

    # The eviction hook drops the stale copy; the next read re-fetches through the loader.
    section.on_external_invalidate("row")
    assert section.find_entry_by_id("row").get_meta_data().get_string("value") == "new"


def test_external_invalidate_drops_from_a_materialized_view_until_reload(provider, repo):
    section = provider.create_section("materialized")  # FULL
    section.insert(entry("row", value="old"))

    section.on_external_invalidate("row")
    # Map-backed modes read only from memory: the dropped id reads as absent...
    assert section.find_entry_by_id("row") is None

    # ...until a reload re-reads the backing store.
    section.reload()
    assert section.find_entry_by_id("row") is not None


def test_idle_section_reports_perfect_hit_ratio(provider):
    section = provider.create_section("idle")
    assert section.stats().cache_hit_ratio() == 1.0
