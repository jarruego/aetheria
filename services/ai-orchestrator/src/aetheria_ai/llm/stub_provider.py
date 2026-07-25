"""Proveedor LLM de coste cero (stub determinista).

Permite ejercitar TODO el pipeline (conversacion 3 niveles, planner) sin llamar a
ninguna API de pago. No es "inteligente": devuelve una respuesta simulada y clara. Es el
proveedor por defecto en desarrollo (ADR-0007). Para respuestas reales se cambia
`LLM_PROVIDER` a un proveedor real (p.ej. claude) con su API key.
"""

from __future__ import annotations

from aetheria_ai.llm.base import LLMMessage, LLMProvider


class StubProvider(LLMProvider):
    name = "stub"

    async def complete(
        self,
        messages: list[LLMMessage],
        *,
        model: str,
        max_tokens: int = 1024,
        temperature: float = 0.7,
    ) -> str:
        last_user = next(
            (m.content for m in reversed(messages) if m.role == "user"), ""
        )
        snippet = last_user.strip()[:200]
        return (
            f"[stub:{model}] Respuesta simulada (sin coste). "
            f"Recibi: \"{snippet}\". "
            "Configura un proveedor LLM real para obtener respuestas de verdad."
        )
