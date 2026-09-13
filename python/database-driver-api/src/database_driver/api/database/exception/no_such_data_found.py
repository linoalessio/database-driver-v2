"""Mirror of ``de.lino.database.database.exception.NoSuchDataFound``."""

from __future__ import annotations


class NoSuchDataFound(RuntimeError):
    """Raised when a stored record is found without the expected ``"data"`` payload while
    a ``DatabaseSection`` is loading or reading its entries, indicating corrupted or
    unexpectedly shaped persisted data."""

    def __init__(self, id: str) -> None:
        # The Java original deliberately omits the id from the message; kept identical so
        # log output stays comparable between the two editions.
        super().__init__("No such data found in document")
