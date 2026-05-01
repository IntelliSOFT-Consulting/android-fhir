#!/usr/bin/env python3
"""Extract flattened Excel headers from nphiis Questionnaire assets."""

from __future__ import annotations

import argparse
import csv
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


DEFAULT_ASSET_DIR = Path("nphiis/src/main/assets")
DEFAULT_OUTPUT_DIR = Path("nphiis/build/questionnaire-headers")
GROUP_TYPES = {"group", "display"}


@dataclass(frozen=True)
class FlatQuestion:
    questionnaire_file: str
    questionnaire_title: str
    column_order: int
    link_id: str
    excel_header: str
    header_key: str
    question_text: str
    section_path: str
    question_path: str
    item_type: str
    required: bool
    repeats: bool
    hidden: bool
    enable_when: str


def normalize_text(value: str | None) -> str:
    return re.sub(r"\s+", " ", (value or "")).strip()


def slugify(value: str) -> str:
    slug = normalize_text(value).lower()
    slug = re.sub(r"[^a-z0-9]+", "_", slug)
    slug = re.sub(r"_+", "_", slug).strip("_")
    return slug or "column"


def safe_output_name(value: str) -> str:
    slug = normalize_text(value).lower()
    slug = re.sub(r"[^a-z0-9._-]+", "-", slug)
    slug = re.sub(r"-+", "-", slug).strip("-")
    return slug or "questionnaire"


def is_hidden(item: dict[str, Any]) -> bool:
    for extension in item.get("extension", []):
        if (
            extension.get("url")
            == "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden"
            and extension.get("valueBoolean") is True
        ):
            return True
    return False


def format_enable_when_value(condition: dict[str, Any]) -> str:
    if "answerCoding" in condition:
        coding = condition["answerCoding"] or {}
        return normalize_text(coding.get("display") or coding.get("code"))
    for key in (
        "answerString",
        "answerDate",
        "answerDateTime",
        "answerTime",
        "answerDecimal",
        "answerInteger",
        "answerBoolean",
    ):
        if key in condition:
            return normalize_text(str(condition.get(key)))
    return ""


def format_enable_when(item: dict[str, Any]) -> str:
    parts: list[str] = []
    for condition in item.get("enableWhen", []):
        question = normalize_text(condition.get("question"))
        operator = normalize_text(condition.get("operator"))
        value = format_enable_when_value(condition)
        fragment = " ".join(part for part in (question, operator, value) if part)
        if fragment:
            parts.append(fragment)
    return " | ".join(parts)


def iter_flat_questions(
    items: Iterable[dict[str, Any]],
    questionnaire_file: str,
    questionnaire_title: str,
    parent_labels: tuple[str, ...] = (),
) -> Iterable[dict[str, Any]]:
    for item in items:
        if not isinstance(item, dict):
            continue

        item_type = normalize_text(item.get("type"))
        link_id = normalize_text(item.get("linkId"))
        text = normalize_text(item.get("text"))
        label = text or link_id
        next_parents = parent_labels + ((text,) if text else ())

        if item_type and item_type not in GROUP_TYPES and link_id:
            section_path = " > ".join(parent_labels)
            question_path = " > ".join(
                part for part in (*parent_labels, label) if part
            )
            header_base = " - ".join(part for part in (section_path, label) if part)
            excel_header = f"{header_base} [{link_id}]"
            header_key_base = "__".join(
                part for part in (slugify(section_path), slugify(label)) if part
            )
            yield {
                "questionnaire_file": questionnaire_file,
                "questionnaire_title": questionnaire_title,
                "link_id": link_id,
                "excel_header": excel_header,
                "header_key": f"{header_key_base}__{slugify(link_id)}",
                "question_text": text,
                "section_path": section_path,
                "question_path": question_path,
                "item_type": item_type,
                "required": bool(item.get("required", False)),
                "repeats": bool(item.get("repeats", False)),
                "hidden": is_hidden(item),
                "enable_when": format_enable_when(item),
            }

        child_items = item.get("item", [])
        if isinstance(child_items, list) and child_items:
            yield from iter_flat_questions(
                child_items,
                questionnaire_file=questionnaire_file,
                questionnaire_title=questionnaire_title,
                parent_labels=next_parents,
            )


def load_questionnaire(path: Path) -> dict[str, Any] | None:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None
    if not isinstance(payload, dict):
        return None
    if payload.get("resourceType") != "Questionnaire":
        return None
    return payload


def write_summary(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["questionnaire_file", "questionnaire_title", "column_count"],
        )
        writer.writeheader()
        writer.writerows(rows)


def write_columns(path: Path, questions: list[FlatQuestion]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "questionnaire_file",
                "questionnaire_title",
                "column_order",
                "link_id",
                "excel_header",
                "header_key",
                "question_text",
                "section_path",
                "question_path",
                "item_type",
                "required",
                "repeats",
                "hidden",
                "enable_when",
            ],
        )
        writer.writeheader()
        for question in questions:
            writer.writerow(question.__dict__)


def write_header_row(path: Path, headers: list[str]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        csv.writer(handle).writerow(headers)


def extract(asset_dir: Path, output_dir: Path) -> tuple[list[dict[str, Any]], list[FlatQuestion]]:
    output_dir.mkdir(parents=True, exist_ok=True)

    questionnaires: list[dict[str, Any]] = []
    all_questions: list[FlatQuestion] = []

    for path in sorted(asset_dir.glob("*.json")):
        questionnaire = load_questionnaire(path)
        if questionnaire is None:
            continue

        title = normalize_text(questionnaire.get("title") or questionnaire.get("name")) or path.stem
        flat_rows = list(
            iter_flat_questions(
                questionnaire.get("item", []),
                questionnaire_file=path.name,
                questionnaire_title=title,
            )
        )

        questions = [
            FlatQuestion(column_order=index, **row)
            for index, row in enumerate(flat_rows, start=1)
        ]
        all_questions.extend(questions)
        questionnaires.append(
            {
                "questionnaire_file": path.name,
                "questionnaire_title": title,
                "column_count": len(questions),
            }
        )

        output_stem = safe_output_name(path.stem)
        write_header_row(
            output_dir / f"{output_stem}.excel_headers.csv",
            [question.excel_header for question in questions],
        )
        write_header_row(
            output_dir / f"{output_stem}.linkid_headers.csv",
            [question.link_id for question in questions],
        )

    write_summary(output_dir / "questionnaire_summary.csv", questionnaires)
    write_columns(output_dir / "questionnaire_columns.csv", all_questions)
    return questionnaires, all_questions


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Flatten nphiis Questionnaire assets into Excel-friendly CSV headers and metadata."
        )
    )
    parser.add_argument(
        "--asset-dir",
        type=Path,
        default=DEFAULT_ASSET_DIR,
        help=f"Directory containing questionnaire JSON assets (default: {DEFAULT_ASSET_DIR})",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"Directory for generated CSV files (default: {DEFAULT_OUTPUT_DIR})",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    questionnaires, questions = extract(args.asset_dir, args.output_dir)

    print(f"Questionnaires processed: {len(questionnaires)}")
    print(f"Flattened columns written: {len(questions)}")
    print(f"Output directory: {args.output_dir.resolve()}")

    if not questionnaires:
        print("No Questionnaire assets were found.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
