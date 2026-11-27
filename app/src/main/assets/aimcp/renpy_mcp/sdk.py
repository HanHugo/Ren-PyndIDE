"""Subprocess shim around the Ren'Py SDK launcher (Android port).

We invoke `renpy.sh <project> <command>` for engine operations the scanner
cannot do itself: lint, force-recompile, build, generate-translations.

Android port note: the upstream version used asyncio.create_subprocess_exec,
which hard-requires asyncio at import time. Ren'Py's slim CPython on-device may
not ship asyncio, so we use the standard-library `subprocess` module instead.
The async function signatures are preserved so all callers keep working.
"""
from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path

from .config import sdk_launcher_name


@dataclass(frozen=True)
class SDKResult:
    returncode: int
    stdout: str
    stderr: str


async def run(sdk_root: Path, project_root: Path, *args: str, timeout: float = 120.0) -> SDKResult:
    """Spawn the Ren'Py SDK launcher with the given subcommand argv and capture its output."""
    cmd = [str(sdk_root / sdk_launcher_name()), str(project_root), *args]
    try:
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
        return SDKResult(
            returncode=proc.returncode,
            stdout=proc.stdout.decode("utf-8", errors="replace"),
            stderr=proc.stderr.decode("utf-8", errors="replace"),
        )
    except subprocess.TimeoutExpired:
        return SDKResult(returncode=-1, stdout="", stderr="timeout")
    except FileNotFoundError as e:
        return SDKResult(returncode=-1, stdout="", stderr=f"launcher not found: {e}")
    except Exception as e:
        return SDKResult(returncode=-1, stdout="", stderr=repr(e))


async def run_lint(sdk_root: Path, project_root: Path) -> SDKResult:
    """Invoke Ren'Py's built-in lint over the project."""
    return await run(sdk_root, project_root, "lint")
