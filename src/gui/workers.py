"""
Background worker plumbing for the OpenUSBIP GUI.

Every usbip call shells out to a subprocess and every reachability check opens
a socket. Run on the Qt main thread those block the event loop -- an
unreachable server froze the whole UI for the full 15 second command timeout,
and the 5 second refresh timer made it recur. Everything blocking goes through
a Worker on the shared QThreadPool instead, and results come back as signals.
"""

import traceback

from PySide6.QtCore import QObject, QRunnable, QThreadPool, Signal, Slot

# Strong references to in-flight workers. Without this a Worker can be garbage
# collected while its pool thread is still running, taking its signals object
# with it and dropping the result on the floor.
_INFLIGHT = set()


class WorkerSignals(QObject):
    """
    Signals emitted by a Worker.

    Declared on a QObject because QRunnable is not one and therefore cannot
    carry signals itself.
    """

    result = Signal(object)   # the callable's return value
    error = Signal(str)       # human-readable failure message
    finished = Signal()       # always emitted, success or failure


class Worker(QRunnable):
    """Runs a callable on the thread pool and reports back via signals."""

    def __init__(self, fn, *args, **kwargs):
        super().__init__()
        self.fn = fn
        self.args = args
        self.kwargs = kwargs
        self.signals = WorkerSignals()

        # Python owns this object's lifetime, not Qt. With Qt's default
        # auto-delete the C++ half is freed the moment run() returns, leaving
        # the Python wrapper pointing at freed memory.
        self.setAutoDelete(False)

    def _emit(self, signal, *args):
        """
        Emit defensively.

        A worker can still be running when the application shuts down, by
        which point Qt has destroyed the underlying C++ object and emitting
        raises "Signal source has been deleted". That is not an error worth
        propagating out of a pool thread.
        """
        try:
            signal.emit(*args)
        except RuntimeError:
            pass

    @Slot()
    def run(self):
        """
        Execute the callable on a pool thread.

        Nothing here may drop the last reference to this Worker. The emits
        below are cross-thread and therefore queued: the slots have not run
        yet when run() returns. Releasing the Worker at that point lets Python
        destroy its WorkerSignals from the pool thread while the main thread
        still holds queued events referencing it, which segfaults. Cleanup is
        instead driven by the finished signal, on the main thread -- see
        run_async().
        """
        try:
            output = self.fn(*self.args, **self.kwargs)
        except Exception as exc:  # noqa: BLE001 - must not kill the pool thread
            traceback.print_exc()
            self._emit(self.signals.error, str(exc))
        else:
            self._emit(self.signals.result, output)
        finally:
            self._emit(self.signals.finished)


def run_async(fn, *args, on_result=None, on_error=None, on_finished=None, **kwargs):
    """
    Dispatch `fn` to the global thread pool.

    Returns the Worker so callers can keep a reference if they need one; the
    pool owns its lifetime otherwise.
    """
    worker = Worker(fn, *args, **kwargs)
    _INFLIGHT.add(worker)

    if on_result is not None:
        worker.signals.result.connect(on_result)
    if on_error is not None:
        worker.signals.error.connect(on_error)
    if on_finished is not None:
        worker.signals.finished.connect(on_finished)

    # Release the worker only once `finished` has actually been delivered.
    # This slot runs on the thread that owns the signals object (the caller,
    # i.e. the GUI thread), so the WorkerSignals QObject is destroyed there
    # rather than on a pool thread with deliveries still queued.
    worker.signals.finished.connect(lambda w=worker: _INFLIGHT.discard(w))

    QThreadPool.globalInstance().start(worker)
    return worker


def wait_for_workers(timeout_ms: int = 3000) -> bool:
    """
    Block until queued work drains. Called on shutdown so pool threads are not
    still touching Qt objects while the application tears itself down.
    """
    return QThreadPool.globalInstance().waitForDone(timeout_ms)
