"""Proveedor LLM basado en Claude (Anthropic).

La dependencia `anthropic` es opcional y se importa de forma perezosa para que el
servicio arranque aunque no esté instalada (p.ej. si se usa otro proveedor).
"""

from __future__ import annotations

from aetheria_ai.llm.base import LLMMessage, LLMProvider


class AnthropicProvider(LLMProvider):
    name = "claude"

    def __init__(self, api_key: str | None) -> None:
        if not api_key:
            raise ValueError("ANTHROPIC_API_KEY no configurada para el proveedor claude.")
        self._api_key = api_key
        self._client = None  # inicialización perezosa

    def _get_client(self):
        if self._client is None:
            try:
                import anthropic
            except ImportError as exc:  # pragma: no cover
                raise RuntimeError(
                    "El paquete 'anthropic' no está instalado. "
                    "Instálalo con: pip install '.[anthropic]'"
                ) from exc
            self._client = anthropic.AsyncAnthropic(api_key=self._api_key)
        return self._client

    async def complete(
        self,
        messages: list[LLMMessage],
        *,
        model: str,
        max_tokens: int = 1024,
        temperature: float = 0.7,
    ) -> str:
        client = self._get_client()
        system = "\n".join(m.content for m in messages if m.role == "system") or None
        chat = [
            {"role": m.role, "content": m.content}
            for m in messages
            if m.role in ("user", "assistant")
        ]
        resp = await client.messages.create(
            model=model,
            max_tokens=max_tokens,
            temperature=temperature,
            system=system,
            messages=chat,
        )
        return "".join(block.text for block in resp.content if block.type == "text")
