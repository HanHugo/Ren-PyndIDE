"""Minimal in-process shim for the `mcp` package.

renpy-mcp only needs two tiny data containers from mcp.types (Tool and
TextContent). The full MCP SDK (mcp.server.*) depends on pydantic-core, which
has no Android/bionic wheel. By shipping this shim we run all of renpy-mcp's
tool logic on the built-in CPython with zero pip installs.
"""
