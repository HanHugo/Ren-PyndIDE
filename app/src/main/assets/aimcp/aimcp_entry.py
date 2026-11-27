"""aimcp_entry.py — in-process entry point for the ported renpy-mcp tools.

Invoked by the built-in CPython via nativeRunScript with:
    sys.argv = [script_path, args_path]

args_path contains one JSON object:
    {
      "project_root": "/abs/path/to/project",   # dir that contains game/
      "sdk_root":     "/abs/path/to/sdk",        # dir that contains renpy.py
      "action":       "list" | "call",
      "tool":         "tool_name",               # only for action=call
      "arguments":    { ... }                    # only for action=call
    }

Output: a single line starting with ``AIMCP_RESULT:`` followed by a compact
JSON object. Everything else (logging, warnings) is suppressed so the line is
unambiguous. The native bridge redirects stdout/stderr to the result file.
"""
from __future__ import annotations

import json
import logging
import sys
import traceback
from pathlib import Path


def _run_coroutine(coro):
    """Drive an awaitable without hard-depending on asyncio.

    The device uses Ren'Py's slim CPython 3.12; asyncio may be absent. Most
    tool handlers are declared `async` but contain no real suspension points,
    so a manual `send(None)` drives them to completion. When asyncio *is*
    present we use asyncio.run() so the few tools that really await subprocess
    calls (lint / preview) also work.
    """
    try:
        import asyncio
        return asyncio.run(coro)
    except ImportError:
        pass
    # Manual drive: works for coroutines with no genuine suspension points.
    try:
        coro.send(None)
    except StopIteration as e:
        return e.value
    raise RuntimeError("asyncio unavailable and tool requires a real await suspension")


def _emit(payload: dict) -> None:
    line = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    sys.stdout.write("AIMCP_RESULT:" + line + "\n")
    sys.stdout.flush()


def _build_registry(project_root: str, sdk_root: str):
    from renpy_mcp.config import ServerConfig
    from renpy_mcp.project.scanner import ProjectIndex
    from renpy_mcp.tools import lifecycle, tier1_read, tier2_write, tier3_intents, tier4_escape
    from renpy_mcp.tools.registry import ToolRegistry

    config = ServerConfig(
        project_root=Path(project_root).resolve(),
        sdk_root=Path(sdk_root).resolve(),
        tiers=frozenset({1, 2, 3, 4}),
    )
    index = ProjectIndex(config)
    registry = ToolRegistry()
    tier1_read.register(registry, config, index)
    lifecycle.register(registry, config, index)
    tier2_write.register(registry, config, index)
    tier3_intents.register(registry, config, index)
    tier4_escape.register(registry, config, index)
    return config, index, registry


def _text_of(contents) -> str:
    """Flatten TextContent list into its combined text payload."""
    parts = []
    for c in contents or []:
        text = getattr(c, "text", None)
        if text is None and isinstance(c, dict):
            text = c.get("text", "")
        if text:
            parts.append(str(text))
    return "\n".join(parts)


def _run() -> dict:
    if len(sys.argv) < 2:
        return {"ok": False, "error": "missing args path"}
    args_path = sys.argv[1]
    try:
        with open(args_path, "r", encoding="utf-8") as fh:
            args = json.load(fh)
    except Exception as exc:
        return {"ok": False, "error": f"read args failed: {exc!r}"}

    project_root = args.get("project_root")
    sdk_root = args.get("sdk_root")
    action = args.get("action", "list")

    # `list` action only needs a registry object to enumerate tool schemas;
    # it never touches project files. The Kotlin caller sends an empty
    # project_root for list, so fall back to a harmless placeholder directory
    # (the current package dir) to build an empty-but-valid registry. Without
    # this, listTools() silently returned zero tools and the LLM had no tools.
    if not project_root:
        if action == "list":
            project_root = str(Path(__file__).resolve().parent)
        else:
            return {"ok": False, "error": "project_root is required"}

    # Keep stdout clean: silence all library logging (it goes to stderr which
    # is also redirected into the result file by the native bridge).
    logging.disable(logging.CRITICAL)

    try:
        config, index, registry = _build_registry(project_root, sdk_root or project_root)
    except Exception as exc:
        return {"ok": False, "error": f"init registry failed: {exc!r}",
                "trace": traceback.format_exc(limit=6)}

    if action == "list":
        tools = []
        for t in registry.list():
            tools.append({
                "name": t.name,
                "description": t.description,
                "inputSchema": getattr(t, "inputSchema", None) or getattr(t, "input_schema", {}),
            })
        return {"ok": True, "tools": tools}

    if action == "call":
        tool = args.get("tool")
        arguments = args.get("arguments") or {}
        if not tool:
            return {"ok": False, "error": "tool is required for call"}
        try:
            contents = _run_coroutine(registry.call(tool, arguments))
            raw = _text_of(contents)
            payload = None
            try:
                payload = json.loads(raw)
            except Exception:
                payload = raw
            # Surface the changed-file list for the UI's blur markers.
            changed = None
            if isinstance(payload, dict):
                changed = payload.get("file") or payload.get("files")
            return {"ok": True, "tool": tool, "result": payload, "changed": changed}
        except Exception as exc:
            return {"ok": False, "tool": tool, "error": f"call failed: {exc!r}",
                    "trace": traceback.format_exc(limit=8)}

    return {"ok": False, "error": f"unknown action: {action}"}


def main() -> int:
    try:
        result = _run()
    except BaseException as exc:
        result = {"ok": False, "error": f"fatal: {exc!r}", "trace": traceback.format_exc(limit=10)}
    _emit(result)
    return 0


if __name__ == "__main__":
    main()
