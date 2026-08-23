"""
Tests for the background worker plumbing.

These exist because of a segfault, not a wrong answer. The pool thread used to
drop the last reference to a Worker inside run(), which let Python destroy its
WorkerSignals from that thread while the GUI thread still had queued
deliveries referencing it. The process died in native code with no Python
traceback, and it took selecting a server in the real GUI to surface it.

A crash fails the whole test session rather than one assertion, which is
exactly the signal wanted here.
"""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QDeadlineTimer, QThreadPool  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from src.gui import workers  # noqa: E402
from src.gui.workers import Worker, run_async, wait_for_workers  # noqa: E402


@pytest.fixture(scope="module")
def qapp():
    app = QApplication.instance() or QApplication([])
    yield app


def _pump(app, predicate, timeout_ms=10000):
    """Spin the event loop until predicate() or timeout."""
    deadline = QDeadlineTimer(timeout_ms)
    while not predicate() and not deadline.hasExpired():
        app.processEvents()
        QThreadPool.globalInstance().waitForDone(20)
    app.processEvents()
    return predicate()


def test_result_is_delivered(qapp):
    got = []
    run_async(lambda: 21 * 2, on_result=got.append)

    assert _pump(qapp, lambda: bool(got))
    assert got == [42]


def test_exception_becomes_an_error_signal(qapp):
    errors = []

    def boom():
        raise ValueError("nope")

    run_async(boom, on_error=errors.append)

    assert _pump(qapp, lambda: bool(errors))
    assert "nope" in errors[0]


def test_finished_fires_on_success_and_failure(qapp):
    done = []
    run_async(lambda: None, on_finished=lambda: done.append("ok"))
    run_async(lambda: 1 / 0, on_finished=lambda: done.append("boom"))

    assert _pump(qapp, lambda: len(done) == 2)
    assert sorted(done) == ["boom", "ok"]


def test_workers_do_not_auto_delete(qapp):
    """Qt's default auto-delete frees the C++ half when run() returns, leaving
    the Python wrapper dangling."""
    w = Worker(lambda: None)
    assert w.autoDelete() is False


def test_inflight_set_drains_after_delivery(qapp):
    """The registry must not leak, and must be cleared by the signal-driven
    cleanup rather than from inside run()."""
    results = []
    for i in range(25):
        run_async(lambda i=i: i, on_result=results.append)

    assert _pump(qapp, lambda: len(results) == 25)
    assert _pump(qapp, lambda: len(workers._INFLIGHT) == 0)
    assert workers._INFLIGHT == set()


def test_many_cycles_do_not_crash(qapp):
    """Stress the create/run/deliver/release cycle. If the lifetime handling
    regresses this segfaults and takes the whole session with it."""
    seen = []
    for round_ in range(5):
        batch = []
        for i in range(20):
            run_async(lambda i=i: i * 2, on_result=batch.append)
        assert _pump(qapp, lambda b=batch: len(b) == 20), f"round {round_}"
        seen.extend(batch)

    assert len(seen) == 100
    assert wait_for_workers(3000)
    assert workers._INFLIGHT == set()
