"""Conversacion de NPC: personalidad humana + memoria en dos capas (Fase 5).

- Nivel 1: intents deterministas (despedidas, gracias). Coste cero.
- Nivel 2/3: LLM con personalidad y memoria.

Memoria como la humana:
  * CORTO PLAZO: los ultimos ~10 turnos, verbatim -> al LLM (nunca se satura).
  * LARGO PLAZO: una FICHA del jugador que se re-condensa; al acumularse charla vieja se
    funde en la ficha y se BORRA (se difumina/olvida). No se guardan todas las preguntas.

La consolidacion (resumir lo viejo + podar) corre en segundo plano tras responder, para no
demorar la respuesta.
"""

from __future__ import annotations

import asyncio

from aetheria_ai.config import settings
from aetheria_ai.llm.base import LLMMessage
from aetheria_ai.llm.factory import get_local_provider, get_provider
from aetheria_ai.models.plan import ConversationRequest, ConversationResponse
from aetheria_ai import world_state_client as ws

# Intents deterministas de Nivel 1 (sin IA).
_LEVEL1_INTENTS: dict[str, str] = {
    "adios": "Cuidate, nos vemos por el pueblo.",
    "gracias": "De nada, hombre. Para eso estamos.",
}

_LEVEL3_HINTS = (
    "planifica", "planificar", "disena", "diseña", "construye", "construir",
    "por que", "por qué", "explica", "compara", "estrategia", "optimiza",
)

# Personalidad de cada NPC: (nombre, caracter).
_PERSONAS: dict[str, tuple[str, str]] = {
    "guia-main": ("Bruno", "un herrero robusto, campechano y bromista"),
    "guia-creative": ("Mila", "una arquitecta sonadora y entusiasta"),
    "guia-creativo": ("Mila", "una arquitecta sonadora y entusiasta"),
    "guia-vuelta": ("Tobias", "un cartografo viajero, tranquilo y curioso"),
}
_DEFAULT_PERSONA = ("Aldo", "un aldeano cercano y amable")

_WINDOW = 10          # turnos verbatim que ve el LLM (memoria de trabajo)
_CONSOLIDATE_AT = 20  # al llegar a tantos turnos, se condensan los viejos en la ficha
_MAX_SUMMARY = 700    # tope de la ficha (lo viejo se va difuminando)


def _persona(npc_id: str) -> tuple[str, str]:
    return _PERSONAS.get(npc_id, _DEFAULT_PERSONA)


def _system_prompt(npc_id: str, profile: str) -> str:
    name, trait = _persona(npc_id)
    base = (
        f"Eres {name}, {trait}. Vives en el pueblo de Aetheria, un lugar dentro de Minecraft. "
        "Hablas como una PERSONA de verdad: cercano, natural, con frases cortas y calidas. "
        "NUNCA digas que eres una IA, un modelo, un sistema o un programa; NO hables de "
        "eficiencia, recursos, algoritmos ni simulaciones. "
        f"Si te preguntan como te llamas, te llamas {name}. "
        "Responde SIEMPRE en espanol y en 1 o 2 frases."
    )
    if profile:
        base += f"\n\nLo que recuerdas de este jugador (puede ser difuso): {profile}"
    return base


def _match_level1(message: str) -> str | None:
    text = message.strip().lower()
    for keyword, reply in _LEVEL1_INTENTS.items():
        if keyword in text:
            return reply
    return None


def classify_level(message: str) -> int:
    if _match_level1(message) is not None:
        return 1
    text = message.strip().lower()
    if len(text) > 120 or any(h in text for h in _LEVEL3_HINTS):
        return 3
    return 2


async def handle_conversation(request: ConversationRequest) -> ConversationResponse:
    level = classify_level(request.message)
    if level == 1:
        return ConversationResponse(reply=_match_level1(request.message) or "", level=1)

    provider = get_local_provider() if level == 2 else get_provider()
    model = settings.llm_model_l2 if level == 2 else settings.llm_model_l3

    # Memoria: ficha (largo plazo) + ultimos turnos (corto plazo).
    profile = await ws.get_npc_summary(request.npc_id, request.player_id)
    history = await ws.get_npc_history(request.npc_id, request.player_id, _WINDOW)

    messages = [LLMMessage(role="system", content=_system_prompt(request.npc_id, profile))]
    for turn in history:
        role = "assistant" if turn.get("role") == "npc" else "user"
        messages.append(LLMMessage(role=role, content=turn.get("content", "")))
    messages.append(LLMMessage(role="user", content=request.message))

    reply = await provider.complete(messages, model=model, max_tokens=200, temperature=0.8)

    # Guarda el turno; el recuento decide si toca consolidar.
    await ws.append_npc_message(request.npc_id, request.player_id, "player", request.message)
    count = await ws.append_npc_message(request.npc_id, request.player_id, "npc", reply)

    if count >= _CONSOLIDATE_AT:
        # En segundo plano: no demora la respuesta al jugador.
        asyncio.create_task(_consolidate(request.npc_id, request.player_id))

    return ConversationResponse(reply=reply, level=level)


async def _consolidate(npc_id: str, player_id: str) -> None:
    """Funde los turnos viejos en la ficha del jugador y los borra (se difuminan)."""
    older = await ws.get_older_turns(npc_id, player_id, _WINDOW)
    if len(older) < 4:
        return

    previous = await ws.get_npc_summary(npc_id, player_id)
    lines = "\n".join(
        f"{'Jugador' if t.get('role') == 'player' else 'Yo'}: {t.get('content', '')}" for t in older
    )
    system = (
        "Mantienes una FICHA breve de un jugador, vista por un aldeano. Actualiza la ficha "
        "combinando la anterior con la conversacion nueva: quedate con lo importante y estable "
        "(nombre, gustos, oficio, temas recurrentes, tono) y descarta lo trivial y puntual. "
        "Escribela en 2-3 frases, en tercera persona. Devuelve SOLO la ficha."
    )
    user = f"Ficha anterior: {previous or '(vacia)'}\n\nConversacion reciente:\n{lines}\n\nFicha actualizada:"

    try:
        new_summary = await get_local_provider().complete(
            [LLMMessage(role="system", content=system), LLMMessage(role="user", content=user)],
            model=settings.llm_model_l2,
            max_tokens=220,
            temperature=0.3,
        )
    except Exception:  # noqa: BLE001 - si falla el resumen, no tocamos nada
        return

    new_summary = new_summary.strip()[:_MAX_SUMMARY]
    if not new_summary:
        return

    await ws.put_npc_summary(npc_id, player_id, new_summary)
    await ws.prune_older_turns(npc_id, player_id, _WINDOW)  # olvida lo ya condensado
