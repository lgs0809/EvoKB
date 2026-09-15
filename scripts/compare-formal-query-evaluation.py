#!/usr/bin/env python3
"""Compare completed Raw and layered receipts without dropping failed questions."""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
from statistics import mean


def compare(raw, layered):
    for report, expected in ((raw, "baseline"), (layered, "layered")):
        if (report["condition"] != expected or report["status"] != "COMPLETED_ACTUAL_FORMAL_QUERIES"
                or report["unstartedAttempts"] or len(report["attempts"]) != report["plannedAttempts"]
                or any(not row.get("outcome") for row in report["attempts"])):
            raise ValueError(f"Complete {expected} receipt required")
    if datetime.fromisoformat(raw['finishedAt']) > datetime.fromisoformat(layered['startedAt']):
        raise ValueError('Complete the Raw arm before starting the layered arm')
    if any(raw['sourceSnapshot'][key] for key in ('summaries', 'derived', 'graph')):
        raise ValueError('Raw arm contains derived publications')
    if not layered['sourceSnapshot']['derived']:
        raise ValueError('Layered arm contains no published derived revisions')
    for key in ("manifestSha256", "runnerSha256", "process", "runtimeSnapshot", "declaredCases", "plannedAttempts",
                "scoringHelpers", "forbiddenClaimPolicy", "factTextPolicy", "answerDeliveryPolicy",
                "workerCount"):
        if raw[key] != layered[key]:
            raise ValueError(f"Cannot compare different {key}")
    if raw["sourceSnapshot"]["sources"] != layered["sourceSnapshot"]["sources"]:
        raise ValueError("Source content, version or authority changed")
    declared = set(raw['declaredCases'])
    if not declared or len(declared) != len(raw['declaredCases']) or raw['plannedAttempts'] % len(declared):
        raise ValueError('Invalid declared evaluation plan')
    repeats = raw['plannedAttempts'] // len(declared)
    expected = {(case, repeat) for case in declared for repeat in range(1, repeats + 1)}
    attempts = [{(row["caseId"], row["repeat"]): row for row in report["attempts"]}
                for report in (raw, layered)]
    if not expected or any(rows.keys() != expected for rows in attempts):
        raise ValueError("Attempt identities do not match one-to-one")

    pairs = []
    for case, repeat in sorted(attempts[0]):
        first, second = (arm[case, repeat] for arm in attempts)
        if first.get('expandedDerivedRevisions', 0):
            raise ValueError(f'Raw arm expanded a derived revision for {case}')
        pairs.append(dict(caseId=case, repeat=repeat, rawOutcome=first["outcome"],
                          layeredOutcome=second["outcome"], rawRounds=first.get("actualConsumedRounds"),
                          layeredRounds=second.get("actualConsumedRounds"),
                          layeredEligibleDerivedRevisions=second.get('eligibleDerivedRevisions'),
                          layeredExpandedDerivedRevisions=second.get('expandedDerivedRevisions'),
                          rawSeconds=first.get('elapsedSeconds'), layeredSeconds=second.get('elapsedSeconds'),
                          rawSession=first.get("sessionId"), layeredSession=second.get("sessionId")))
    complete_rounds = [row for row in pairs if row["rawRounds"] is not None and row["layeredRounds"] is not None]
    return dict(pairCount=len(pairs), pairsWithNativeRoundCounts=len(complete_rounds),
                rawOutcomes=Counter(row["rawOutcome"] for row in pairs),
                layeredOutcomes=Counter(row["layeredOutcome"] for row in pairs),
                layeredQueriesWithNativeEntryExpansion=sum(
                    bool(row['layeredExpandedDerivedRevisions']) for row in pairs),
                meanRawRounds=mean(row["rawRounds"] for row in complete_rounds) if complete_rounds else None,
                meanLayeredRounds=mean(row["layeredRounds"] for row in complete_rounds) if complete_rounds else None,
                pairs=pairs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", required=True, type=Path)
    parser.add_argument("--layered", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = compare(json.loads(args.raw.read_text()), json.loads(args.layered.read_text()))
    with args.output.open("x") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write("\n")


if __name__ == "__main__":
    main()
