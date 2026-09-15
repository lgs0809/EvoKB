#!/usr/bin/env python3
"""Freeze independent knowledge-evolution mechanism cases; no business or model writes."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    cases = []

    def add(scenario, title, **expected):
        index = len(cases) + 1
        cases.append(dict(caseId=f'EVOLUTION-{index:02d}',
                          split='development' if index <= 19 else 'holdout',
                          scenario=scenario, title=title, expected=expected))

    add('SOURCE_ZERO', 'No completed transfer does not qualify a source', sourceContributions=0, summaryCandidates=0)
    add('SOURCE_TWO', 'Two independent answers remain below the source threshold', sourceContributions=2, summaryCandidates=0)
    add('SOURCE_THREE', 'Three independent completed transfers qualify exactly one source', sourceContributions=3, summaryCandidates=1)
    add('SOURCE_FOUR', 'An above-threshold source remains one candidate', sourceContributions=4, summaryCandidates=1)
    add('SOURCE_REPLAY', 'Eight replays of one immutable package remain one contribution', sourceContributions=1, summaryCandidates=0)
    add('MANIFEST_ONLY', 'Creating an authorized package does not count a download', sourceContributions=0, receipts=0, summaryCandidates=0)
    add('SHORT_TRANSFER', 'Short byte transfer is rejected without credit', sourceContributions=0, receipts=0, summaryCandidates=0, rejected=True)
    add('ROLLED_BACK_TRANSFER', 'A native transaction rollback leaves no receipt or credit', sourceContributions=0, receipts=0, summaryCandidates=0)
    add('ENTRY_FOUR', 'Four actual entry uses do not qualify Wiki', entryContributions=4, wikiCandidates=0)
    add('ENTRY_FIVE', 'Five actual entry uses qualify one Wiki', entryContributions=5, wikiCandidates=1)
    add('ENTRY_SOURCE_ONLY', 'Source-only downloads do not broadcast entry heat', entryContributions=0, wikiCandidates=0)
    add('ENTRY_MIXED', 'Eight additional source downloads do not turn three entry uses into five', entryContributions=3, wikiCandidates=0)
    add('ENTRY_EXPIRED_HEAT', 'Entry heat older than thirty days does not qualify', entryContributions=5, wikiCandidates=0)
    add('ENTRY_ACL_CHANGED', 'A changed source ACL blocks Wiki qualification immediately', entryContributions=5, wikiCandidates=0)
    add('ENTRY_DELETED_SOURCE', 'A deleted input blocks Wiki qualification', entryContributions=5, wikiCandidates=0)
    add('GRAPH_TWO', 'Two source downloads do not qualify Graph', graphCandidates=0)
    add('GRAPH_THREE', 'Three source downloads qualify a grounded contract Graph', graphCandidates=1)
    add('GRAPH_SOURCE_SCOPED', 'An ungrounded source-scoped topic does not qualify Graph', graphCandidates=0)
    add('GRAPH_TOPIC_TYPE', 'A general topic is not an eligible business Graph target', graphCandidates=0)
    add('GRAPH_ACL_CHANGED', 'A changed build input blocks grounded Graph qualification', graphCandidates=0)
    add('SUMMARY_ALREADY_ACTIVE', 'Already processed current source is not rebuilt from old heat', summaryCandidates=0)
    add('SOURCE_NEW_VERSION', 'A new source version does not inherit its old version heat', newVersionContributions=0, summaryCandidates=0)
    add('ENTRY_ALREADY_WIKI', 'A current Wiki is not promoted again for every five uses', wikiCandidates=0)
    add('ENTRY_STALE', 'A stale entry is not eligible for new Wiki or Graph selection', wikiCandidates=0, graphCandidates=0)
    add('ACL_REPAIR_PRIORITY', 'Source repair bypasses the new-work quota and gets priority', repairJobs=1, repairPriority=100, newDailySelections=0)
    add('SUMMARY_QUOTA_20', 'Exactly twenty sources fit the daily source budget', selectedSummary=20, remainingSummary=0)
    add('SUMMARY_QUOTA_21', 'A twenty-first source remains unselected', selectedSummary=20, remainingSummary=1)
    add('WIKI_QUOTA_11', 'An eleventh Wiki remains unselected', selectedWiki=10, remainingWiki=1)
    add('GRAPH_QUOTA_11', 'An eleventh Graph remains unselected', selectedGraph=10, remainingGraph=1)
    add('CONCURRENT_DAILY_QUOTAS', 'Concurrent selectors and restart spend one daily allocation', selectedSummary=20, selectedWiki=10, selectedGraph=10, selectedTotal=40, restartSelections=0)
    assert len(cases) == 30 and Counter(case['split'] for case in cases) == {'development': 19, 'holdout': 11}
    report = dict(formatVersion=1, status='FROZEN_CASES_ONLY_NOT_EXECUTED', cases=cases,
                  scope='Native PostgreSQL mechanism matrix in separate retained schemas. Production repositories, '
                        'completed-transfer callbacks and schedulers are exercised. Query, source and derived '
                        'payloads are explicitly synthetic component fixtures. This is not model content quality, '
                        'real browser transfer, elapsed thirty days, actual midnight or complete KB acceptance. '
                        'No public business heat, status or task completion is manufactured. Gold is never sent to a model.')
    raw = (json.dumps(report, ensure_ascii=False, indent=2) + '\n').encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists() and args.output.read_bytes() != raw:
        raise ValueError('Frozen cases differ; retain the original Gold and create a separate development erratum')
    if not args.output.exists():
        with args.output.open('xb') as stream:
            stream.write(raw)
    print(json.dumps(dict(status=report['status'], cases=30, development=19, holdout=11,
                         sha256=hashlib.sha256(raw).hexdigest(), output=str(args.output)), ensure_ascii=False))


if __name__ == '__main__':
    main()
