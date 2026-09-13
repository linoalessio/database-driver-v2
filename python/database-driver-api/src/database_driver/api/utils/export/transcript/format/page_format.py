"""Mirror of ``de.lino.database.utils.export.transcript.format.PageFormat``."""

from __future__ import annotations

from enum import Enum, unique


@unique
class PageFormat(Enum):
    """A standard ISO 216 page size an export can be rendered at.

    Every ``TranscriptExporter`` implementation accepts a ``PageLayout``, but only the
    PDF, Excel and DOCX exporters actually render pages and so are the only ones a chosen
    format has any visible effect on. Paired with a ``PageOrientation`` via
    ``PageLayout``.
    """

    A3 = "A3"
    """297 x 420 mm."""

    A4 = "A4"
    """210 x 297 mm."""

    A5 = "A5"
    """148 x 210 mm."""
