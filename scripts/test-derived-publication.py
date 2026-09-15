#!/usr/bin/env python3
"""Retain a fresh PostgreSQL/Milvus component namespace for staged-publication verification."""
import argparse
from pathlib import Path
from postgres_component_probe import run

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--mode',choices=['before','after'],default='after')
    parser.add_argument('--jar',type=Path,help='Use a retained immutable artifact for before/after reproduction')
    args=parser.parse_args()
    raise SystemExit(run(args.output,source_name='DerivedPublicationProbe.java',prefix='evokb_publish_',
        extra_sql='CREATE TABLE publication_probe(marker VARCHAR(128) PRIMARY KEY);\n',
        runtime_args=(args.mode,),namespace_arg=True,timeout=300,jar_path=args.jar))
