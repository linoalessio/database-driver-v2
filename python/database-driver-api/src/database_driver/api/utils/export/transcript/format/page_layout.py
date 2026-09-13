"""Mirror of ``de.lino.database.utils.export.transcript.format.PageLayout``."""

from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar

from database_driver.api.utils.export.transcript.format.page_format import PageFormat
from database_driver.api.utils.export.transcript.format.page_orientation import PageOrientation


@dataclass(frozen=True, slots=True)
class PageLayout:
    """A page's size and orientation, e.g. A4 portrait, passed to
    ``TranscriptExporter.export`` so a caller can choose how a transcript is laid out
    rather than each implementation hardcoding a single page size.

    Attributes:
        format: The page's ISO 216 size.
        orientation: The page's orientation.
    """

    format: PageFormat
    orientation: PageOrientation

    DEFAULT: ClassVar[PageLayout]
    """The layout every export used before ``PageLayout`` existed: A4 portrait."""

    def __post_init__(self) -> None:
        if not isinstance(self.format, PageFormat):
            raise TypeError("@PageLayout: format must be a PageFormat")
        if not isinstance(self.orientation, PageOrientation):
            raise TypeError("@PageLayout: orientation must be a PageOrientation")


PageLayout.DEFAULT = PageLayout(PageFormat.A4, PageOrientation.PORTRAIT)
