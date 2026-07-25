"""Interfaz de proveedor LLM (ADR-0004).

Ningún otro servicio conoce qué proveedor está activo. Cambiar Claude por OpenAI o por
un modelo local = implementar esta interfaz y cambiar `LLM_PROVIDER` en el entorno.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class LLMMessage:
    role: str  # "system" | "user" | "assistant"
    content: str


class LLMProvider(ABC):
    """Contrato mínimo que todo proveedor debe cumplir."""

    name: str

    @abstractmethod
    async def complete(
        self,
        messages: list[LLMMessage],
        *,
        model: str,
        max_tokens: int = 1024,
        temperature: float = 0.7,
    ) -> str:
        """Devuelve el texto de la respuesta del modelo."""
        raise NotImplementedError
