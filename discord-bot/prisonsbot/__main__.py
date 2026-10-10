import sys

from .cli import main

for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

sys.exit(main(sys.argv[1:]))
