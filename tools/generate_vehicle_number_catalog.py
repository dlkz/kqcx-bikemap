#!/usr/bin/env python3
"""Generate the bundled vehicle-number catalog from scan detail CSV files.

The runtime asset ``app/src/main/assets/vehicle_number_catalog.json`` is a
build input, not a hand-edited file.  Regenerate it whenever a new scan is
available so "the catalog that ships with the app" stays reproducible.

Usage (Windows):

    py tools/generate_vehicle_number_catalog.py \
        --input docs/developer-data/vehicle-number/江西快趣车辆明细-精确编号.csv \
        --input 快趣车辆编号数据/2026-10-08_1738/江西快趣车辆明细-精确编号.csv \
        --updated-at 2026-10-08 \
        --output app/src/main/assets/vehicle_number_catalog.json

    # verify the committed asset is up to date (no write):
    py tools/generate_vehicle_number_catalog.py --input ... --check

Input format (columns, UTF-8 with BOM tolerated):

    序号, 站点ID, 学校/校区, 车辆编号, 车型

The 车辆编号 column looks like ``="007100151"``.  Numbers are always kept as
strings so leading zeros survive; the scan exports them as text on purpose.
Several ``--input`` files are merged into one catalog, so older scans keep
contributing coverage that a later scan no longer reports.  Pass them in
chronological order: when two scans disagree about a number's campus, the
later scan wins, because a vehicle may have been moved between campuses.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path

NUMBER_LENGTH = 9
SERIES_LENGTH = 6
SCHEMA_VERSION = 2

# Display names for known interface site ids.  The vehicle service still
# reports the old names for some sites (for example 72 -> 南昌工程学院), so the
# curated name is kept here instead of trusting the scan column.  Unknown ids
# fall back to whatever the CSV reports.
SITE_NAME_OVERRIDES = {
    67: "江西师范大学",
    72: "江西水利电力大学",
}

_NUMBER_PATTERN = re.compile(r"\d+")


def clean_number(raw: str) -> str:
    """Return the digits of a vehicle number, preserving leading zeros."""
    digits = "".join(_NUMBER_PATTERN.findall(raw or ""))
    return digits.zfill(NUMBER_LENGTH)


def read_scan(path: Path):
    """Yield (site_id, site_name, number) rows from one scan CSV."""
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        required = {"站点ID", "学校/校区", "车辆编号"}
        missing = required - set(reader.fieldnames or [])
        if missing:
            raise SystemExit(
                f"{path}: missing column(s) {sorted(missing)}; "
                f"found {reader.fieldnames}"
            )
        for row in reader:
            raw_site = (row.get("站点ID") or "").strip()
            if not raw_site.isdigit():
                continue
            number = clean_number(row.get("车辆编号") or "")
            if len(number) != NUMBER_LENGTH or number == "0" * NUMBER_LENGTH:
                continue
            yield int(raw_site), (row.get("学校/校区") or "").strip(), number


def build_catalog(inputs, updated_at: str):
    site_names: dict[int, str] = {}
    site_series: dict[int, set[str]] = {}
    numbers: dict[str, tuple[int, str]] = {}

    for path in inputs:
        for site_id, csv_name, number in read_scan(path):
            display = SITE_NAME_OVERRIDES.get(site_id) or csv_name or f"站点 {site_id}"
            site_names.setdefault(site_id, display)
            site_series.setdefault(site_id, set())
            series = number[:SERIES_LENGTH]
            site_series[site_id].add(series)
            existing = numbers.get(number)
            if existing is not None and existing[0] != site_id:
                print(
                    f"warning: {number} appears for site {existing[0]} and "
                    f"{site_id}; keeping the later scan",
                    file=sys.stderr,
                )
            numbers[number] = (site_id, series)

    if not numbers:
        raise SystemExit("no vehicle numbers found in the given inputs")

    sites = []
    for site_id in sorted(site_series):
        sites.append(
            {
                "id": site_id,
                "name": site_names[site_id],
                "series": sorted(site_series[site_id]),
            }
        )

    # series -> site ids.  A series that maps to a single site is an
    # unambiguous fallback for a full number that the exact index does not
    # contain yet.
    seed: dict[str, list[int]] = {}
    for site in sites:
        for series in site["series"]:
            seed.setdefault(series, []).append(site["id"])
    for series in seed:
        seed[series].sort()

    return {
        "schema": SCHEMA_VERSION,
        "version": SCHEMA_VERSION,
        "updatedAt": updated_at,
        "sites": sites,
        "seed": {series: seed[series] for series in sorted(seed)},
        "numbers": {number: list(numbers[number]) for number in sorted(numbers)},
    }


def render(catalog) -> str:
    return json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--input",
        action="append",
        type=Path,
        required=True,
        help="scan detail CSV; repeatable, merged into one catalog",
    )
    parser.add_argument("--output", type=Path, default=None, help="catalog JSON path")
    parser.add_argument(
        "--updated-at",
        default=None,
        help="catalog date, e.g. 2026-10-08; required unless --check",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="compare against the existing output instead of writing",
    )
    args = parser.parse_args(argv)

    for path in args.input:
        if not path.is_file():
            raise SystemExit(f"input not found: {path}")

    if args.output is None:
        raise SystemExit("--output is required")

    if args.check:
        if not args.output.is_file():
            raise SystemExit(f"output not found: {args.output}")
        current = json.loads(args.output.read_text(encoding="utf-8"))
        updated_at = current.get("updatedAt") or args.updated_at
    else:
        updated_at = args.updated_at
    if not updated_at:
        raise SystemExit("--updated-at is required when generating")

    catalog = build_catalog(args.input, updated_at)
    text = render(catalog)

    if args.check:
        if args.output.read_text(encoding="utf-8") == text:
            print(f"up to date: {args.output}")
            return 0
        print(f"out of date: {args.output}", file=sys.stderr)
        return 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print(
        f"wrote {args.output}: {len(catalog['numbers'])} numbers, "
        f"{len(catalog['sites'])} sites, updated {catalog['updatedAt']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
