"""Mirror of ``de.lino.database.utils.cache.ConsistentHashRing``."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Generic, TypeVar

NodeId = TypeVar("NodeId")


class ConsistentHashRing(ABC, Generic[NodeId]):
    """Consistent-hashing ring following the Cassandra/DynamoDB principle: routes keys to
    nodes, and supports replica selection across multiple nodes.

    Each physical node is expected to be assigned several "virtual nodes" on the ring,
    which spreads load more evenly and avoids hotspots. A key is hashed onto the ring and
    routed to the next node clockwise.
    """

    @abstractmethod
    def add_node(self, node: NodeId) -> None:
        """Adds a node to the ring. Expected to be safe to call concurrently with
        lookups."""

    @abstractmethod
    def remove_node(self, node: NodeId) -> None:
        """Removes a node and all of its virtual nodes from the ring. Expected to be safe
        to call concurrently with lookups."""

    @abstractmethod
    def node_for(self, key: Any) -> NodeId:
        """Returns the node responsible for a key."""

    @abstractmethod
    def nodes_for(self, key: Any, replication_factor: int) -> list[NodeId]:
        """Returns up to ``replication_factor`` distinct nodes for a key (for
        replication, analogous to Cassandra's replication factor), in ring order starting
        at the key's position."""
