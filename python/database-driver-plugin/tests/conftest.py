"""Shared fixtures: every test runs against a fresh registry and repository directory."""

from __future__ import annotations

import pytest

from database_driver.plugin import DatabaseRepositoryRegistry


@pytest.fixture()
def registry() -> DatabaseRepositoryRegistry:
    """A fresh registry per test; shut down afterwards so providers never leak across
    tests."""
    instance = DatabaseRepositoryRegistry(log_bytes=False)
    yield instance
    instance.shutdown()


def entry(id: str, **payload):
    """Builds the ``{data: {...}}``-enveloped entry shape every store expects."""
    from database_driver.api import DatabaseEntry, JsonDocument

    return DatabaseEntry(id, JsonDocument("data", JsonDocument(dict(payload))))
