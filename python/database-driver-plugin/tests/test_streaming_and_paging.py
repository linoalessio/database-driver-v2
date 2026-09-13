"""Port of ``StreamingAndPagingTest``: for_each_entry streams, paging is id-ordered and
mode-stable, the bounded-window default page_remote behaves."""

from __future__ import annotations

from pathlib import Path

import pytest
from conftest import entry
from database_driver.api import Credentials, SectionConfig

from database_driver.plugin.database.nosql.csv.csv_database_provider import CSVDatabaseProvider
from database_driver.plugin.database.nosql.json.json_database_provider import JsonDatabaseProvider


@pytest.fixture()
def json_provider(registry, tmp_path: Path) -> JsonDatabaseProvider:
    return JsonDatabaseProvider(Credentials(tmp_path / "credentials.json", file_repository=tmp_path / "repo"))


def test_for_each_entry_visits_everything_once(json_provider):
    section = json_provider.create_section("streamed", SectionConfig.none())
    for index in range(25):
        section.insert(entry(f"id-{index:02d}", n=index))

    seen: list[str] = []
    section.for_each_entry(lambda e: seen.append(e.id))
    assert sorted(seen) == [f"id-{index:02d}" for index in range(25)]


@pytest.mark.parametrize(
    "config",
    [SectionConfig.full(), SectionConfig.lazy(), SectionConfig.bounded(4), SectionConfig.none()],
    ids=["full", "lazy", "bounded", "none"],
)
def test_pages_are_identical_across_modes(json_provider, config):
    """The same call yields the same page regardless of how the section is cached."""
    section = json_provider.create_section(f"paged_{config.cache_mode.value.lower()}", config)
    for index in range(9):
        section.insert(entry(f"k{index}", n=index))

    assert [e.id for e in section.get_entries_page(2, 3)] == ["k2", "k3", "k4"]
    assert [e.id for e in section.get_entries_page(0, 100)] == [f"k{index}" for index in range(9)]
    assert section.get_entries_page(9, 3) == []
    assert section.get_entries_page(0, 0) == []

    with pytest.raises(ValueError):
        section.get_entries_page(-1, 3)
    with pytest.raises(ValueError):
        section.get_entries_page(0, -1)


def test_csv_default_page_remote_bounded_window(registry, tmp_path):
    """The CSV store has no paging pushdown, so it exercises the engine's max-heap
    window default."""
    provider = CSVDatabaseProvider(Credentials(tmp_path / "credentials.json", file_repository=tmp_path / "csv"))
    section = provider.create_section("windowed", SectionConfig.none())
    for index in range(12):
        section.insert(entry(f"row-{index:02d}", n=index))

    page = section.get_entries_page(5, 4)
    assert [e.id for e in page] == ["row-05", "row-06", "row-07", "row-08"]
