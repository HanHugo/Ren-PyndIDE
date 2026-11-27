"""Drop-in replacements for the two mcp.types containers renpy-mcp uses.

Only `Tool` (name/description/inputSchema) and `TextContent` (type/text) are
referenced across the tool modules. These dataclasses preserve constructor
keyword names and attribute access, so the original tool code runs unchanged.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class Tool:
    name: str
    description: str = ""
    inputSchema: dict[str, Any] = field(default_factory=dict)


@dataclass
class TextContent:
    type: str = "text"
    text: str = ""
    # tolerate any extra fields the SDK might pass
    annotations: Any = None
