"""Admission and resume validation for frozen real-query campaigns; never resubmits known sessions."""
import copy


def resume_attempts(prior, frozen):
    for key in ('manifestSha256', 'runnerSha256', 'condition', 'process', 'sourceSnapshot', 'runtimeSnapshot',
                'declaredCases', 'plannedAttempts', 'workerCount', 'forbiddenClaimPolicy', 'factTextPolicy',
                'answerDeliveryPolicy', 'scoringHelpers'):
        if prior.get(key) != frozen.get(key):
            raise ValueError('Cannot resume a different frozen campaign: ' + key)
    declared = set(frozen['declaredCases'])
    attempts = {}
    for row in prior.get('attempts', []):
        key = (row.get('caseId'), row.get('repeat'))
        if key[0] not in declared or not isinstance(key[1], int) or key in attempts:
            raise ValueError('Unknown or duplicate prior attempt identity')
        if key[1] < 1 or key[1] > frozen['plannedAttempts'] // len(declared):
            raise ValueError('Prior repeat outside frozen plan')
        if not row.get('outcome') and not row.get('sessionId'):
            raise ValueError('Prior admission is unconfirmed; reconcile before resubmitting')
        attempts[key] = copy.deepcopy(row)
    return attempts


def requires_execution(attempt):
    # A recorded failure stays a first-attempt failure. Retry campaigns use a fresh output.
    return not attempt or not attempt.get('outcome')
