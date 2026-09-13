"""Mirror of ``de.lino.database.utils.export.ExporterInjector``."""

from __future__ import annotations

from abc import ABC, abstractmethod

from database_driver.api.utils.export.archiv.archive_exporter import ArchiveExporter
from database_driver.api.utils.export.data.data_exporter import DataExporter


class ExporterInjector(ABC):
    """The injection contract ``ExportCoordinator`` implements.

    Rather than receiving its exporters through its constructor or through per-call
    setter methods of its own devising, each is handed in through one of these shared
    setter methods - "interface injection", the third classic form of dependency
    injection alongside constructor and setter injection, distinguished by the setters
    being defined once on a shared interface rather than being specific to whichever
    class happens to need them.

    Any class - not just ``ExportCoordinator`` - can implement this contract to become
    wireable against ``DataExporter`` and ``ArchiveExporter`` the same way; nothing about
    it is specific to any one application. Grouped-transcript exports are deliberately
    not part of this contract: ``ExportCoordinator`` resolves which transcript
    implementation to use dynamically, per call, rather than through an injected
    instance - see ``ExportCoordinator.export_transcript``.
    """

    @abstractmethod
    def inject_data_exporter(self, data_exporter: DataExporter) -> None:
        """Injects the flat-table exporter used by subsequent calls.

        Raises:
            TypeError: If ``data_exporter`` is ``None``.
        """

    @abstractmethod
    def inject_archive_exporter(self, archive_exporter: ArchiveExporter) -> None:
        """Injects the archive exporter used by subsequent calls, e.g. a
        ``DirectoryZipExporter``.

        Raises:
            TypeError: If ``archive_exporter`` is ``None``.
        """
