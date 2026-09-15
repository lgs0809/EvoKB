"""Audit actual reserved query counts, independently of model answer correctness."""


def within_query_budget(reservations):
    if not isinstance(reservations, list) or len(reservations) > 8:
        return False
    rounds = set()
    total = 0
    for reservation in reservations:
        if not isinstance(reservation, dict):
            return False
        number = reservation.get('round_no')
        queries = reservation.get('input_queries')
        if (type(number) is not int or not 1 <= number <= 8 or number in rounds
                or not isinstance(queries, list) or len(queries) > 3
                or any(not isinstance(query, str) or not query.strip() for query in queries)):
            return False
        rounds.add(number)
        total += len(queries)
    return total <= 8
