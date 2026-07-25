"""Enrutador de conversacion en 3 niveles (ADR-0004, ADR-0007) con PERSONA y MEMORIA.

- Nivel 1: intents deterministas (saludos, precios...). Coste cero.
- Nivel 2/3: LLM con personalidad humana y memoria de la conversacion.

Que suenen HUMANOS depende del prompt de personaje (`_system_prompt`): cada NPC tiene un
nombre y un caracter, y se le prohibe expresamente hablar como una IA/sistema. Que te
RECUERDEN depende de la memoria: se recuperan los ultimos turnos y se guardan los nuevos.
"""

from __future__ import annotations

from aetheria_ai.config import settings
from aetheria_ai.llm.base import LLMMessage
from aetheria_ai.llm.factory import get_local_provider, get_provider
from aetheria_ai.models.plan import ConversationRequest, ConversationResponse
from aetheria_ai.world_state_client import append_npc_message, get_npc_history

# Intents deterministas de Nivel 1 (sin IA). Palabra clave -> respuesta.
_LEVEL1_INTENTS: dict[str, str] = {
    "adios": "Cuidate, nos vemos por el pueblo.",
    "gracias": "De nada, hombre. Para eso estamos.",
}

# Pistas de que un mensaje merece el Nivel 3 (razonamiento).
_LEVEL3_HINTS = (
    "planifica", "planificar", "disena", "diseña", "construye", "construir",
    "por que", "por qué", "explica", "compara", "estrategia", "optimiza",
)

# Personalidad de cada NPC: (nombre, caracter). Es lo que los hace sonar como personas.
_PERSONAS: dict[str, tuple[str, str]] = {
    "guia-main": ("Bruno", "un herrero robusto, campechano y bromista"),
    "guia-creative": ("Mila", "una arquitecta sonadora y entusiasta"),
    "guia-creativo": ("Mila", "una arquitecta sonadora y entusiasta"),
    "guia-vuelta": ("Tobias", "un cartografo viajero, tranquilo y curioso"),
}
_DEFAULT_PERSONA = ("Aldo", "un aldeano cercano y amable")

_MAX_HISTORY = 8


def _persona(npc_id: str) -> tuple[str, str]:
    return _PERSONAS.get(npc_id, _DEFAULT_PERSONA)


def _system_prompt(npc_id: str) -> str:
    name, trait = _persona(npc_id)
    return (
        f"Eres {name}, {trait}. Vives en el pueblo de Aetheria, un lugar dentro de Minecraft. "
        "Hablas como una PERSONA de verdad: cercano, natural, con frases cortas y calidas, "
        "como quien charla con un vecino. "
        "NUNCA digas que eres una IA, un modelo, un sistema o un programa; NO hables de "
        "eficiencia, recursos, algoritmos ni simulaciones. "
        f"Si te preguntan como te llamas, te llamas {name}. "
        "Usa lo que el jugador te haya contado antes (su nombre, sus gustos) si viene a cuento. "
        "Responde SIEMPRE en espanol y en 1 o 2 frases."
    )


def _match_level1(message: str) -> str | None:
    text = message.strip().lower()
    for keyword, reply in _LEVEL1_INTENTS.items():
        if keyword in text:
            return reply
    return None


def classify_level(message: str) -> int:
    """Decide el nivel (1/2/3) de forma determinista."""
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

    # Memoria: recupera los ultimos turnos con este jugador para tener contexto.
    history = await get_npc_history(request.npc_id, request.player_id, _MAX_HISTORY)
    messages = [LLMMessage(role="system", content=_system_prompt(request.npc_id))]
    for turn in history:
        role = "assistant" if turn.get("role") == "npc" else "user"
        messages.append(LLMMessage(role=role, content=turn.get("content", "")))
    messages.append(LLMMessage(role="user", content=request.message))

    reply = await provider.complete(messages, model=model, max_tokens=200, temperature=0.8)

    # Guarda el turno (jugador + NPC) para recordarlo en el futuro.
    await append_npc_message(request.npc_id, request.player_id, "player", request.message)
    await append_npc_message(request.npc_id, request.player_id, "npc", reply)

    return ConversationResponse(reply=reply, level=level)
