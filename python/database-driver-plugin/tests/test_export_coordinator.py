"""Tests for ExportCoordinator: suffix auto-detection, all six transcript formats, the
zip archive exporter, and interface injection."""

from __future__ import annotations

import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import pytest
from database_driver.api import PageLayout, TranscriptLegendEntry, TranscriptSection

from database_driver.plugin import DirectoryZipExporter, ExportCoordinator

SECTIONS = [
    TranscriptSection("Semester 1", [["Programming 1", "1.3", "8"], ["Mathematics 1", "2.0", "8"]]),
    TranscriptSection("Semester 2", [["Programming 2", "1.7", "8"]]),
]
HEADERS = ["Module", "Grade", "ECTS"]
LEGEND = [TranscriptLegendEntry("1.0 - 1.5", "excellent"), TranscriptLegendEntry("1.6 - 2.5", "good")]


@pytest.fixture()
def coordinator() -> ExportCoordinator:
    return ExportCoordinator()


def _export(coordinator: ExportCoordinator, output: Path) -> Path:
    coordinator.export_transcript("Transcript", HEADERS, SECTIONS, "Grading Scale", LEGEND, PageLayout.DEFAULT, output)
    return output


def test_unknown_extension_is_rejected(coordinator, tmp_path):
    with pytest.raises(ValueError):
        _export(coordinator, tmp_path / "transcript.pptx")


def test_empty_headers_are_rejected(coordinator, tmp_path):
    with pytest.raises(ValueError):
        coordinator.export_transcript("T", [], SECTIONS, "L", LEGEND, PageLayout.DEFAULT, tmp_path / "t.csv")


def test_csv_transcript(coordinator, tmp_path):
    output = _export(coordinator, tmp_path / "transcript.csv")
    # Read raw bytes: read_text's universal-newline mode would fold the RFC 4180
    # \r\n separators this assertion is about.
    text = output.read_bytes().decode("utf-8")

    assert text.startswith("Transcript\r\n")
    assert "Module,Grade,ECTS" in text
    assert "Semester 1" in text and "Programming 1,1.3,8" in text
    assert "Grading Scale" in text and "1.0 - 1.5,excellent" in text


def test_json_transcript(coordinator, tmp_path):
    output = _export(coordinator, tmp_path / "transcript.json")
    root = json.loads(output.read_text(encoding="utf-8"))

    assert root["title"] == "Transcript"
    assert root["columns"] == HEADERS
    assert root["sections"][0]["title"] == "Semester 1"
    assert root["sections"][0]["rows"][0] == ["Programming 1", "1.3", "8"]
    assert root["legend"]["entries"][0] == {"label": "1.0 - 1.5", "description": "excellent"}


def test_xml_transcript(coordinator, tmp_path):
    output = _export(coordinator, tmp_path / "transcript.xml")
    root = ET.parse(output).getroot()

    assert root.tag == "transcript" and root.get("title") == "Transcript"
    assert [column.text for column in root.find("columns")] == HEADERS
    sections = root.find("sections").findall("section")
    assert sections[0].get("title") == "Semester 1"
    assert [cell.text for cell in sections[0].find("row")] == ["Programming 1", "1.3", "8"]
    assert root.find("legend").find("entry").get("label") == "1.0 - 1.5"


def test_pdf_transcript(coordinator, tmp_path):
    pytest.importorskip("reportlab")
    output = _export(coordinator, tmp_path / "transcript.pdf")
    content = output.read_bytes()
    assert content.startswith(b"%PDF")
    assert len(content) > 1000


def test_xlsx_transcript(coordinator, tmp_path):
    openpyxl = pytest.importorskip("openpyxl")
    output = _export(coordinator, tmp_path / "transcript.xlsx")

    workbook = openpyxl.load_workbook(output)
    assert "Transcript" in workbook.sheetnames
    assert "Grading Scale" in workbook.sheetnames

    sheet = workbook["Transcript"]
    assert [cell.value for cell in sheet[1]] == HEADERS
    assert sheet.cell(row=2, column=1).value == "Semester 1"
    assert sheet.freeze_panes == "A2"


def test_docx_transcript(coordinator, tmp_path):
    docx = pytest.importorskip("docx")
    output = _export(coordinator, tmp_path / "transcript.docx")

    document = docx.Document(str(output))
    assert document.paragraphs[0].text == "Transcript"
    table = document.tables[0]
    assert [cell.text for cell in table.rows[0].cells] == HEADERS
    assert table.rows[1].cells[0].text == "Semester 1"
    # Legend table: label | description, borderless.
    legend_table = document.tables[1]
    assert legend_table.rows[0].cells[0].text == "1.0 - 1.5"


def test_export_table_requires_injection(coordinator, tmp_path):
    with pytest.raises(RuntimeError):
        coordinator.export_table([], ["h"], lambda row: [str(row)], "T", tmp_path / "t.csv")


def test_injected_data_exporter_receives_the_call(coordinator, tmp_path):
    received = {}

    class RecordingExporter:
        def export(self, rows, headers, row_mapper, title, output):
            received.update(rows=list(rows), headers=list(headers), title=title, output=output)

    coordinator.inject_data_exporter(RecordingExporter())
    coordinator.export_table([1, 2], ["n"], lambda row: [str(row)], "Numbers", tmp_path / "n.csv")

    assert received["rows"] == [1, 2] and received["title"] == "Numbers"


def test_directory_zip_exporter_round_trip(coordinator, tmp_path):
    source = tmp_path / "data"
    (source / "nested").mkdir(parents=True)
    (source / "top.txt").write_text("top", encoding="utf-8")
    (source / "nested" / "deep.txt").write_text("deep", encoding="utf-8")

    ran = []
    coordinator.inject_archive_exporter(DirectoryZipExporter(source, before_export=lambda: ran.append(True)))

    output = tmp_path / "backup.zip"
    coordinator.export_archive(output)

    assert ran == [True]
    with zipfile.ZipFile(output) as archive:
        assert sorted(archive.namelist()) == ["nested/deep.txt", "top.txt"]
        assert archive.read("nested/deep.txt") == b"deep"


def test_export_archive_requires_injection(tmp_path):
    with pytest.raises(RuntimeError):
        ExportCoordinator().export_archive(tmp_path / "x.zip")


def test_zip_exporter_rejects_missing_directory(tmp_path):
    exporter = DirectoryZipExporter(tmp_path / "absent")
    with pytest.raises(OSError):
        exporter.export(tmp_path / "x.zip")
