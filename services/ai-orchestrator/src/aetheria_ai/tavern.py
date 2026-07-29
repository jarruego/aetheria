"""Ambiente de TABERNA generado por IA: frases cortas (chistes malos, brindis, ocurrencias) que
los parroquianos sueltan de noche, ademas de cantar y chismorrear.

Se usa el LLM del NIVEL 2 (barato/local): es SABOR decorativo (bocadillos), nunca una accion. Si no
hay IA (stub) o falla, se devuelve un repertorio fijo, asi que la taberna nunca se queda muda.
"""

from __future__ import annotations

from aetheria_ai.config import settings
from aetheria_ai.llm.base import LLMMessage
from aetheria_ai.llm.factory import get_local_provider
from aetheria_ai.validator.text_safety import sanitize_chat_text

_FALLBACK = [
    "¡Otra ronda, que la noche es joven!",
    "Brindo por el que paga: ¡salud!",
    "Este hidromiel sabe a victoria.",
    "Dicen que el tabernero riega la cerveza...",
    "Cuenta ese chiste otra vez, anda.",
    "Al que no bebe, le salen setas.",
    "¡Por el pueblo y por la jarra!",
    "Yo pago la proxima... manana sin falta.",
    "Un dia de estos me hago rico. Un dia.",
    "La sopa de la posada resucita muertos.",
]

_SYSTEM = (
    "Eres el AMBIENTE de una taberna de pueblo medieval. Devuelve frases MUY CORTAS y variadas que "
    "los parroquianos sueltan entre jarras: chistes malos de taberna, brindis, ocurrencias, quejas "
    "graciosas, dichos populares. UNA por linea, sin numerar, sin comillas, cada una de MENOS de 55 "
    "caracteres, en espanol, tono alegre y campechano. Nada ofensivo, politico ni moderno."
)


async def tavern_lines(n: int = 8) -> list[str]:
    """Genera hasta `n` frases de taberna con el LLM del Nivel 2; si falla, usa el repertorio fijo."""
    n = max(1, min(int(n), 12))
    raw = ""
    try:
        raw = await get_local_provider().complete(
            [LLMMessage(role="system", content=_SYSTEM),
             LLMMessage(role="user", content=f"Dame {n} frases de taberna, una por linea.")],
            model=settings.llm_model_l2,
            max_tokens=220,
            temperature=1.0,
        )
    except Exception:  # noqa: BLE001 - si el LLM falla, tiramos del repertorio fijo
        raw = ""
    out: list[str] = []
    for line in (raw or "").splitlines():
        clean = sanitize_chat_text(line.strip().lstrip("-*•0123456789.)( ").strip())
        if clean and 2 < len(clean) <= 60 and clean not in out:
            out.append(clean)
    if not out:
        out = list(_FALLBACK)
    return out[:n]
