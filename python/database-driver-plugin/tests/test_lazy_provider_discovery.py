"""Port of ``LazyProviderDiscoveryTest``: provider construction reads no row data,
discovered sections exist without being materialized, reload detaches old instances."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from conftest import entry
from database_driver.api import Credentials

from database_driver.plugin.database.nosql.json.json_database_provider import JsonDatabaseProvider


@pytest.fixture()
def repo(tmp_path: Path) -> Path:
    return tmp_path / "repo"


def _seed_section(repo: Path, section: str, id: str) -> None:
    directory = repo / section
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{id}.json").write_text(json.dumps({"id": id, "data": {"seeded": True}}), encoding="utf-8")


def _provider(tmp_path: Path, repo: Path) -> JsonDatabaseProvider:
    config = tmp_path / f"credentials-{len(list(tmp_path.iterdir()))}.json"
    return JsonDatabaseProvider(Credentials(config, file_repository=repo))


def test_provider_construction_reads_no_row_data(registry, tmp_path, repo):
    _seed_section(repo, "existing", "row")
    # Poison one entry file: constructing the provider must not attempt to parse it.
    (repo / "existing" / "corrupt.json").write_text("NOT JSON", encoding="utf-8")

    provider = _provider(tmp_path, repo)  # would raise if rows were read
    assert provider.exists_section("existing")


def test_discovered_sections_exist_without_being_materialized(registry, tmp_path, repo):
    _seed_section(repo, "cold", "row")
    provider = _provider(tmp_path, repo)

    assert provider.exists_section("cold")
    section = provider.get_section("cold")
    assert section is not None
    # Materialization did not warm: no full load has run yet.
    assert section.stats().full_loads == 0
    # First data access pays the load.
    assert section.count() == 1
    assert section.stats().full_loads == 1


def test_reload_rediscovers_names_and_detaches_old_instances(registry, tmp_path, repo):
    provider = _provider(tmp_path, repo)
    old_section = provider.create_section("detach")
    old_section.insert(entry("row", n=1))

    _seed_section(repo, "appeared", "row")
    provider.reload()

    assert provider.exists_section("appeared")
    # The old instance keeps serving its own, now-detached state...
    assert old_section.count() == 1
    # ...while re-fetching materializes a fresh instance.
    assert provider.get_section("detach") is not old_section


def test_delete_section_removes_store_and_bookkeeping(registry, tmp_path, repo):
    provider = _provider(tmp_path, repo)
    provider.create_section("doomed").insert(entry("row", n=1))

    provider.delete_section("doomed")

    assert not provider.exists_section("doomed")
    assert not (repo / "doomed").exists()


def test_create_section_is_warm_but_get_section_stays_cold_until_accessed(registry, tmp_path, repo):
    _seed_section(repo, "cold", "row")
    provider = _provider(tmp_path, repo)

    warm = provider.create_section("warm")  # FULL default: warm at creation
    assert warm.stats().full_loads == 1

    cold = provider.get_section("cold")
    assert cold.stats().full_loads == 0
