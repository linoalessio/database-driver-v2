"""database_driver.plugin - the concrete implementations behind ``database_driver.api``,
mirroring the Java ``database-driver-plugin`` module package-for-package.

Constructing a :class:`DatabaseRepositoryRegistry` installs itself as
``DatabaseRepository``'s singleton accessor and, as a side effect, installs the
``FileProvider`` singleton used internally by the file stores - the same wiring as the
Java module. The ``DefaultCacheProvider`` is discovered by the api package's ``Caches``
through this distribution's ``database_driver.cache_provider`` entry point
automatically; nothing needs to be imported for that.

Only the registry, engine classes, cache implementations and export coordinator are
re-exported here; backend providers/sections import lazily through the registry (or
directly from their own modules), so unused backends never require their driver
packages installed.
"""

from database_driver.plugin.database.abstract_cached_database_section import (
    AbstractCachedDatabaseSection,
    SectionStats,
)
from database_driver.plugin.database.abstract_lazy_database_provider import AbstractLazyDatabaseProvider
from database_driver.plugin.database.file.default_file_provider import DefaultFileProvider
from database_driver.plugin.database_repository_registry import DatabaseRepositoryRegistry
from database_driver.plugin.utility.cache.default_cache import DefaultCache
from database_driver.plugin.utility.cache.default_clustered_cache import DefaultClusteredCache
from database_driver.plugin.utility.cache.default_consistent_hash_ring import DefaultConsistentHashRing
from database_driver.plugin.utility.cache.provider.default_cache_provider import DefaultCacheProvider
from database_driver.plugin.utility.export.export_coordinator import DirectoryZipExporter, ExportCoordinator

__all__ = [
    "AbstractCachedDatabaseSection",
    "AbstractLazyDatabaseProvider",
    "DatabaseRepositoryRegistry",
    "DefaultCache",
    "DefaultCacheProvider",
    "DefaultClusteredCache",
    "DefaultConsistentHashRing",
    "DefaultFileProvider",
    "DirectoryZipExporter",
    "ExportCoordinator",
    "SectionStats",
]
