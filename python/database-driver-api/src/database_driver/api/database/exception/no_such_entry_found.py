"""Mirror of ``de.lino.database.database.exception.NoSuchEntryFound``."""

from __future__ import annotations


class NoSuchEntryFound(RuntimeError):
    """Raised when an operation (such as ``update`` or ``delete``) is attempted on a
    ``DatabaseEntry`` whose id does not exist in the target ``DatabaseSection``."""

    def __init__(self, id: str) -> None:
        super().__init__(f"No such entry found with id='{id}'")
