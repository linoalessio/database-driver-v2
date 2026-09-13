"""Mirror of ``de.lino.database.utils.export.transcript.format.PageOrientation``."""

from __future__ import annotations

from enum import Enum, unique


@unique
class PageOrientation(Enum):
    """A page's orientation, paired with a ``PageFormat`` via ``PageLayout``."""

    PORTRAIT = "PORTRAIT"
    """The page's long edge runs vertically."""

    LANDSCAPE = "LANDSCAPE"
    """The page's long edge runs horizontally."""
