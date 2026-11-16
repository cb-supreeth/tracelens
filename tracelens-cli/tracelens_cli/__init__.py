"""tracelens-cli: talk to a TraceLens-instrumented service's /tracelens/* debug API."""

from importlib import metadata as _metadata

try:
    __version__ = _metadata.version("tracelens-cli")
except _metadata.PackageNotFoundError:
    # A source checkout can run without installed distribution metadata.
    __version__ = "0.1.1"
