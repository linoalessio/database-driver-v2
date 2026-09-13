"""Mirror of ``de.lino.database.database.auth.Credentials``."""

from __future__ import annotations

import os
import traceback
from pathlib import Path

from database_driver.api.json.json_document import JsonDocument

_UNKNOWN = "Unknown"
"""Placeholder value used for fields that are not applicable to a given database (e.g.
host/credentials for the file-based JSON database)."""


class Credentials:
    """Holds the connection details required to reach a database backend (host,
    credentials, port, database name and, for file-based providers, a repository
    directory), and transparently persists them to a JSON configuration file.

    On construction, if ``config_destination`` does not exist yet, the given values are
    written to it as JSON; otherwise the existing file is read and its values are loaded
    instead, **ignoring the constructor arguments other than** ``config_destination`` -
    the config file, not the caller, is the source of truth after the first run. Keep
    this in mind when changing the constructor or serialization: it is not safe to assume
    the arguments passed in are always the ones actually in effect. The persisted JSON
    shape is identical to the Java edition's, so a config file written by one edition is
    readable by the other.

    The Java edition's convenience constructors map to the keyword defaults here: a
    network-based provider passes everything but ``file_repository``; a file-based
    provider passes only ``config_destination`` and ``file_repository``.
    """

    def __init__(
        self,
        config_destination: str | os.PathLike[str],
        address: str = _UNKNOWN,
        user_name: str = _UNKNOWN,
        password: str = _UNKNOWN,
        port: int = -1,
        database: str = _UNKNOWN,
        file_repository: str | os.PathLike[str] = _UNKNOWN,
    ) -> None:
        """Seeds ``config_destination`` with the given values on first use, or loads the
        existing file back, discarding every other argument. Any failure while reading an
        existing file is caught and printed rather than raised, leaving this instance
        with placeholder fields - matching the Java edition's error handling.

        Args:
            config_destination: Configuration file where the credentials will be saved,
                or read back from if it already exists.
            address: Host address.
            user_name: Login username.
            password: Verification password.
            port: Database port.
            database: Database name.
            file_repository: Repository where the file database shall save its data; only
                meaningful for file-based providers such as ``JsonDatabaseProvider``.
        """
        self.config_destination: Path = Path(config_destination)
        self.address: str = _UNKNOWN
        self.user_name: str = _UNKNOWN
        self.password: str = _UNKNOWN
        self.port: int = -1
        self.database: str = _UNKNOWN
        self.file_repository: str = _UNKNOWN

        if not self.config_destination.exists():
            self.address = address
            self.user_name = user_name
            self.password = password
            self.port = port
            self.database = database
            self.file_repository = str(file_repository)

            JsonDocument() \
                .append("address", address) \
                .append("userName", user_name) \
                .append("password", password) \
                .append("port", port) \
                .append("database", database) \
                .append("fileRepository", str(file_repository)) \
                .write(self.config_destination)
            return

        try:
            document = JsonDocument(self.config_destination)
            self.address = document.get_string("address")
            self.user_name = document.get_string("userName")
            self.password = document.get_string("password")
            self.port = document.get_integer("port")
            self.database = document.get_string("database")
            self.file_repository = document.get_string("fileRepository")
        except Exception:
            traceback.print_exc()

    @staticmethod
    def of(config_destination: str | os.PathLike[str]) -> Credentials | None:
        """Reads an already-persisted ``Credentials`` configuration back from disk
        without writing anything, unlike the constructor, which creates
        ``config_destination`` if it is missing.

        Returns:
            The parsed ``Credentials``, or ``None`` both when ``config_destination``
            does not exist and when reading or parsing it fails - the failure case is
            printed rather than propagated, matching the constructor's error handling.
        """
        destination = Path(config_destination)
        if not destination.exists():
            return None
        try:
            # The file exists, so the constructor takes its read-existing-file branch and
            # the placeholder arguments below are discarded - same flow as the Java
            # edition's private getCredentials helper.
            return Credentials(destination)
        except Exception:
            traceback.print_exc()
            return None
