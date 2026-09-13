"""Port of ``CacheModeSqliteTest``: the same engine semantics over the SQLite backend -
the one SQL vendor testable without a server."""

from __future__ import annotations

from pathlib import Path

import pytest
from conftest import entry
from database_driver.api import Credentials, SectionConfig

from database_driver.plugin.database.sql.sqlite.sqlite_database_provider import SQLiteDatabaseProvider


@pytest.fixture()
def provider(registry, tmp_path: Path) -> SQLiteDatabaseProvider:
    credentials = Credentials(tmp_path / "credentials.json", file_repository=tmp_path / "db")
    instance = SQLiteDatabaseProvider(credentials)
    yield instance
    instance.shutdown()


@pytest.mark.parametrize(
    "config",
    [SectionConfig.full(), SectionConfig.lazy(), SectionConfig.bounded(16), SectionConfig.none()],
    ids=["full", "lazy", "bounded", "none"],
)
def test_mode_round_trip(provider, config):
    section = provider.create_section(f"mode_{config.cache_mode.value.lower()}", config)

    section.insert(entry("one", n=1))
    section.insert(entry("two", n=2))

    assert section.count() == 2
    assert section.exists("one")
    assert section.find_entry_by_id("two").get_meta_data().get_integer("n") == 2

    section.update(entry("one", n=10))
    assert section.find_entry_by_id("one").get_meta_data().get_integer("n") == 10

    section.delete("two")
    assert section.count() == 1

    section.clear()
    assert section.count() == 0


def test_tables_survive_and_rediscover_across_provider_reload(provider):
    provider.create_section("tenacious").insert(entry("row", n=5))

    provider.reload()

    assert provider.exists_section("tenacious")
    section = provider.get_section("tenacious")
    assert section.find_entry_by_id("row").get_meta_data().get_integer("n") == 5


def test_clear_actually_empties_a_sqlite_table(provider):
    # Deliberate deviation from Java (which issues TRUNCATE and silently fails on
    # SQLite): clear() must genuinely remove the rows.
    section = provider.create_section("wipe", SectionConfig.none())
    section.insert(entry("a", n=1))
    section.clear()
    assert section.count_remote() == 0


def test_paging_pushdown_orders_by_id(provider):
    section = provider.create_section("paged", SectionConfig.none())
    for index in range(10):
        section.insert(entry(f"id-{index:02d}", n=index))

    page = section.get_entries_page(3, 4)
    assert [e.id for e in page] == ["id-03", "id-04", "id-05", "id-06"]
    assert section.get_entries_page(20, 5) == []
