"""Tests for DatabaseRepositoryRegistry: registration, conversion, singletons."""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest
from conftest import entry
from database_driver.api import Credentials, DatabaseRepository, DatabaseType, FileProvider


def _credentials(tmp_path: Path, name: str) -> Credentials:
    return Credentials(tmp_path / f"{name}.json", file_repository=tmp_path / name)


def test_construction_installs_both_singletons(registry):
    assert DatabaseRepository.get_instance() is registry
    assert FileProvider.get_instance() is not None


def test_register_find_unregister(registry, tmp_path):
    provider = registry.register_database_provider(1, DatabaseType.JSON, _credentials(tmp_path, "one"))

    assert registry.find_database_provider_by_id(1) is provider
    assert registry.get_database_provider_pool() == [provider]
    assert registry.get_database_provider_pool(DatabaseType.JSON) == [provider]
    assert registry.get_database_provider_pool(DatabaseType.CSV) == []

    with pytest.raises(RuntimeError):
        registry.register_database_provider(1, DatabaseType.JSON, _credentials(tmp_path, "dup"))

    assert registry.unregister_database_provider(1) is provider
    assert registry.find_database_provider_by_id(1) is None
    with pytest.raises(RuntimeError):
        registry.unregister_database_provider(1)


def test_embedded_jvm_types_are_rejected(registry, tmp_path):
    with pytest.raises(NotImplementedError):
        registry.register_database_provider(9, DatabaseType.H2_DB, _credentials(tmp_path, "h2"))
    with pytest.raises(NotImplementedError):
        registry.register_database_provider(9, DatabaseType.APACHE_DERBY, _credentials(tmp_path, "derby"))
    # A failed construction must not leave a half-registered id behind.
    assert registry.find_database_provider_by_id(9) is None


def test_convert_copies_sections_between_backends(registry, tmp_path):
    registry.register_database_provider(1, DatabaseType.JSON, _credentials(tmp_path, "source"))
    registry.register_database_provider(2, DatabaseType.SQLITE, _credentials(tmp_path, "target"))

    source = registry.find_database_provider_by_id(1)
    source.create_section("copied").insert(entry("row", value="over the wire"))

    pair = registry.convert(1, 2)
    assert pair.first is source

    target_section = pair.second.get_section("copied")
    assert target_section is not None
    assert target_section.find_entry_by_id("row").get_meta_data().get_string("value") == "over the wire"

    with pytest.raises(RuntimeError):
        registry.convert(1, 99)


def test_shutdown_async_clears_all_providers(registry, tmp_path):
    registry.register_database_provider(1, DatabaseType.JSON, _credentials(tmp_path, "a"))
    registry.register_database_provider(2, DatabaseType.CSV, _credentials(tmp_path, "b"))

    asyncio.run(registry.shutdown_async())
    assert registry.get_database_provider_pool() == []
