"""Mirror of ``de.lino.database.utils.Pair``."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Generic, TypeVar

T = TypeVar("T")
R = TypeVar("R")


@dataclass(frozen=True, slots=True)
class Pair(Generic[T, R]):
    """A simple, immutable holder for two related values of possibly different types.

    Kept as its own type (rather than a plain ``tuple``) so call sites such as
    ``DatabaseRepository.convert`` keep the Java API's named ``first``/``second``
    accessors instead of positional indexing.
    """

    first: T
    second: R
