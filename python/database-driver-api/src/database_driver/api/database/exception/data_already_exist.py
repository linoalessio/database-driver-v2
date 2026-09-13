"""Mirror of ``de.lino.database.database.exception.DataAlreadyExist``."""

from __future__ import annotations


class DataAlreadyExist(RuntimeError):
    """Raised when an attempt is made to insert a ``DatabaseEntry`` with an id that
    already exists in the target ``DatabaseSection``."""

    def __init__(self, id: str) -> None:
        super().__init__(f"Entry already exists with id='{id}'")
