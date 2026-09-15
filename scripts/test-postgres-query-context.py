#!/usr/bin/env python3
"""Verify immutable context receipts with the actual adapter in a fresh retained PostgreSQL schema."""
import argparse
from pathlib import Path
from postgres_component_probe import run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(run(args.output, source_name='QueryContextReceiptProbe.java', prefix='evokb_context_receipt_'))


if __name__ == '__main__':
    main()
