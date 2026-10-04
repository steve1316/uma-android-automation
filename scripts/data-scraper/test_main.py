import requests

import main


class _Boom:
    def start(self):
        raise ValueError("bad row")


class _Offline:
    def start(self):
        raise requests.exceptions.ConnectionError("no route")


def test_non_retryable_failure_is_recorded():
    main._run_failures.clear()
    main.run_scraper_with_retry(_Boom(), retries=0)
    assert main._run_failures == ["_Boom: ValueError: bad row"]


def test_network_failure_after_retries_is_recorded():
    main._run_failures.clear()
    main.run_scraper_with_retry(_Offline(), retries=0, backoff=0)
    assert main._run_failures == ["_Offline: ConnectionError: no route"]
