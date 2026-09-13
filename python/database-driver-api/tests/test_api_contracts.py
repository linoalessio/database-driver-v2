"""Smoke tests for the ported API contracts.

The Java module has no test sources; these exist because the Python port carries real
logic in a few places (JsonDocument, SectionConfig normalization, Credentials
self-seeding, ExportType suffix resolution) whose behavior must stay pinned to the Java
edition's.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import timedelta
from decimal import Decimal
from pathlib import Path

import pytest

from database_driver.api import (
    CacheMode,
    Credentials,
    DataAlreadyExist,
    DatabaseEntry,
    DatabaseSection,
    ExportType,
    JsonDocument,
    NoSuchEntryFound,
    PageFormat,
    PageLayout,
    PageOrientation,
    Pair,
    SectionConfig,
    Serialized,
)

# ---------------------------------------------------------------- JsonDocument

def test_json_document_roundtrip(tmp_path: Path) -> None:
    document = (
        JsonDocument()
        .append("name", "lino")
        .append("age", 24)
        .append("pi", 3.14)
        .append("active", True)
        .append("nested", JsonDocument("inner", "value"))
    )
    assert document.get_string("name") == "lino"
    assert document.get_integer("age") == 24
    assert document.get_double("pi") == pytest.approx(3.14)
    assert document.get_boolean("active") is True
    nested = document.get_meta_data("nested")
    assert nested is not None and nested.get_string("inner") == "value"

    destination = tmp_path / "doc.json"
    assert document.write(destination) is True
    loaded = JsonDocument.load(destination)
    assert loaded.get_string("name") == "lino"
    assert loaded.get_keys() == document.get_keys()


def test_json_document_load_missing_file_returns_empty(tmp_path: Path) -> None:
    loaded = JsonDocument.load(tmp_path / "absent.json")
    assert loaded.get_keys() == set()


def test_json_document_binary_roundtrip_matches_biginteger_semantics() -> None:
    document = JsonDocument().append("blob", b"\x00\xff\x10")
    assert document.get_binary("blob") == b"\x00\xff\x10"
    # Sign handling matters: Java stores byte[] as a signed BigInteger.
    document.append("neg", b"\xff")
    assert document.get_binary("neg") == b"\xff"


def test_json_document_big_decimal_is_lossless() -> None:
    document = JsonDocument().append("amount", Decimal("1.100000000000000000000001"))
    assert document.get_big_decimal("amount") == Decimal("1.100000000000000000000001")


def test_json_document_copy_is_deep() -> None:
    original = JsonDocument().append("nested", JsonDocument("key", "value"))
    copy = original.copy()
    copy.json_object["nested"]["key"] = "changed"
    assert original.get_meta_data("nested").get_string("key") == "value"


def test_json_document_get_with_default_and_predicate() -> None:
    document = JsonDocument().append("count", 5)
    assert document.get("absent", int, default=7) == 7
    assert document.get("count", int, default=7, predicate=lambda value: value > 10) == 7
    assert document.get("count", int, default=7, predicate=lambda value: value > 1) == 5


# ----------------------------------------------------------------- Serialized

@dataclass
class _Student(Serialized):
    matriculation: str = ""
    email: str = ""

    def keys_of(self) -> list[str]:
        return [self.matriculation, self.email]


def test_serialized_keys_and_byte_roundtrip() -> None:
    student = _Student("12345", "lino@example.org")
    assert student.primary_key() == "12345"
    assert student.has_key("LINO@EXAMPLE.ORG")
    assert not student.has_key("other")
    assert student.keys_as_string() == "12345:lino@example.org"

    restored = Serialized.from_byte_array(student.to_byte_array(), _Student)
    assert restored.matriculation == "12345"
    assert restored.email == "lino@example.org"


# --------------------------------------------------------------- SectionConfig

def test_section_config_normalizes_non_bounded_modes() -> None:
    assert SectionConfig(CacheMode.FULL, 99, timedelta(seconds=5)) == SectionConfig.full()
    assert SectionConfig.full().max_entries == -1 and SectionConfig.full().ttl is None


def test_section_config_bounded_validation() -> None:
    with pytest.raises(ValueError):
        SectionConfig.bounded(0)
    with pytest.raises(ValueError):
        SectionConfig.bounded(10, timedelta(0))
    config = SectionConfig.bounded(10, timedelta(minutes=1))
    assert config.cache_mode is CacheMode.BOUNDED and config.max_entries == 10


# ----------------------------------------------------------------- Credentials

def test_credentials_seed_then_read_back(tmp_path: Path) -> None:
    destination = tmp_path / "credentials.json"
    first = Credentials(destination, "localhost", "root", "secret", 3306, "app")
    assert first.address == "localhost" and first.port == 3306

    # Second construction must ignore its arguments and read the file back.
    second = Credentials(destination, "elsewhere", "other", "different", 9999, "nope")
    assert second.address == "localhost"
    assert second.port == 3306
    assert second.database == "app"

    read_back = Credentials.of(destination)
    assert read_back is not None and read_back.user_name == "root"
    assert Credentials.of(tmp_path / "absent.json") is None


# ------------------------------------------------------------------ ExportType

def test_export_type_suffix_resolution() -> None:
    assert ExportType.from_suffix("transcript.PDF") is ExportType.PDF
    assert ExportType.from_suffix("data.xlsx") is ExportType.EXCEL
    assert ExportType.from_suffix("no-extension") is None
    assert ExportType.from_suffix("trailing.") is None
    assert ExportType.PDF.adjust_suffix_to_file(Path("transcript")) == Path("transcript.pdf")


def test_page_layout_default() -> None:
    assert PageLayout.DEFAULT == PageLayout(PageFormat.A4, PageOrientation.PORTRAIT)


# ------------------------------------------------- DatabaseSection defaults

class _InMemorySection(DatabaseSection):
    """Minimal section exercising the contract's default methods, the way a pre-paging
    implementation would."""

    def __init__(self) -> None:
        self._entries: dict[str, DatabaseEntry] = {}

    def get_name(self) -> str:
        return "test"

    def insert(self, database_entry: DatabaseEntry) -> None:
        if database_entry.id in self._entries:
            raise DataAlreadyExist(database_entry.id)
        self._entries[database_entry.id] = database_entry

    def update(self, database_entry: DatabaseEntry) -> None:
        if database_entry.id not in self._entries:
            raise NoSuchEntryFound(database_entry.id)
        self._entries[database_entry.id] = database_entry

    def delete(self, id: str) -> None:
        if id not in self._entries:
            raise NoSuchEntryFound(id)
        del self._entries[id]

    def count(self) -> int:
        return len(self._entries)

    def clear(self) -> None:
        self._entries.clear()

    def reload(self) -> None:
        pass

    def exists(self, id: str) -> bool:
        return id in self._entries

    def find_entry_by_id(self, id: str):
        return self._entries.get(id)

    def get_entries(self) -> list[DatabaseEntry]:
        return sorted(self._entries.values(), key=lambda entry: entry.id)


def test_section_defaults_and_async_wrappers() -> None:
    section = _InMemorySection()
    for index in range(5):
        section.insert(DatabaseEntry(f"id-{index}", JsonDocument("data", JsonDocument("n", index))))

    with pytest.raises(DataAlreadyExist):
        section.insert(DatabaseEntry("id-0", JsonDocument()))

    page = section.get_entries_page(1, 2)
    assert [entry.id for entry in page] == ["id-1", "id-2"]
    assert section.get_entries_page(10, 2) == []
    with pytest.raises(ValueError):
        section.get_entries_page(-1, 2)

    seen: list[str] = []
    section.for_each_entry(lambda entry: seen.append(entry.id))
    assert len(seen) == 5

    async def _run() -> None:
        assert await section.count_async() == 5
        assert await section.exists_async("id-3") is True
        found = await section.find_entry_by_id_async("id-3")
        assert found is not None and found.get_meta_data().get_integer("n") == 3
        await section.delete_async("id-3")
        assert await section.count_async() == 4

    asyncio.run(_run())


def test_pair_accessors() -> None:
    pair = Pair("a", 1)
    assert pair.first == "a" and pair.second == 1
    with pytest.raises(AttributeError):
        pair.first = "b"  # type: ignore[misc]
