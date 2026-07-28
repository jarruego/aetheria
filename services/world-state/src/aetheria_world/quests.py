"""Misiones y PRESTIGIO del jugador (migracion 0009).

Reglas de oro que se aplican aqui:

* **La IA no reparte nada.** El objetivo de cada mision lo compone el plugin con el
  estado real de la aldea (que falta en el granero, cuanto le falta a la hucha...) y
  *este modulo es el que decide lo que se paga*: el baremo `_KINDS` acota AET y
  prestigio por tipo, asi que aunque el plugin pidiera una recompensa disparatada
  (o alguien colara una llamada con el token interno) no puede imprimir dinero.
* **Solo el backend da una mision por cumplida**, comprobando `progress >= target`
  contra la fila persistida. El plugin nunca "declara" que ha terminado.
* **Rendimiento decreciente en las donaciones**: el prestigio por AET aportado va por
  raiz cuadrada. Donar 4x mas NO da 4x mas prestigio: la alcaldia no se compra.
"""

from __future__ import annotations

import decimal
import json
import math
import uuid

from aetheria_world.config import settings

# Cuenta del sistema (banco): de aqui sale la recompensa, como en /internal/reward.
_BANCO = uuid.UUID("00000000-0000-0000-0000-000000000000")
_STARTING_BALANCE = decimal.Decimal("100.00")

# BAREMO: techo de recompensa por tipo de mision (aet, prestigio). Es la autoridad:
# lo que pide el plugin se recorta a esto. Las SOCIALES valen tanto prestigio como las
# economicas a proposito, para que la alcaldia no la gane siempre quien mas AET mueve.
_KINDS: dict[str, tuple[int, int]] = {
    "economica": (80, 15),
    "social": (40, 16),
    "construccion": (60, 16),
    "mantenimiento": (60, 15),
    "exploracion": (90, 18),
}

MAX_ACTIVE_PER_VILLAGE = 3     # misiones a la vez en una misma aldea
QUEST_TTL_DAYS = 3             # sin cumplir en 3 dias -> 'expired' y entra otra


def kinds() -> dict[str, tuple[int, int]]:
    return dict(_KINDS)


async def _account(conn, owner_id: uuid.UUID, owner_type: str = "player"):
    """Cuenta AET del propietario (la crea con el saldo inicial si es su primera vez)."""
    row = await conn.fetchrow(
        "select id, balance from accounts where owner_type = $1 and owner_id = $2 and currency = 'AET'",
        owner_type, owner_id,
    )
    if row is None:
        start = _STARTING_BALANCE if owner_type == "player" else decimal.Decimal(0)
        row = await conn.fetchrow(
            "insert into accounts (owner_type, owner_id, balance, currency) values ($1, $2, $3, 'AET') "
            "returning id, balance",
            owner_type, owner_id, start,
        )
    return row


async def _move(conn, src, dst, amount: decimal.Decimal, reason: str) -> None:
    await conn.execute("update accounts set balance = balance - $1 where id = $2", amount, src["id"])
    await conn.execute("update accounts set balance = balance + $1 where id = $2", amount, dst["id"])
    await conn.execute(
        "insert into transactions (from_account, to_account, amount, reason) values ($1, $2, $3, $4)",
        src["id"], dst["id"], amount, reason,
    )


def score(mission_points: int, donated_total) -> int:
    """Prestigio de un jugador en una aldea: misiones + raiz de lo donado."""
    return int(mission_points) + int(math.isqrt(max(0, int(float(donated_total)))))


async def _expire_stale(conn, player_id, village: str) -> None:
    await conn.execute(
        """
        update quests set status = 'expired'
        where player_id = $1 and village_name = $2 and status = 'active'
          and created_at < now() - ($3 || ' days')::interval
        """,
        player_id, village, str(QUEST_TTL_DAYS),
    )


def _row_to_quest(r) -> dict:
    objective = r["objective"]
    if isinstance(objective, str):
        objective = json.loads(objective)
    return {
        "id": str(r["id"]),
        "village_name": r["village_name"],
        "kind": r["kind"],
        "objective": objective or {},
        "progress": r["progress"],
        "target": r["target"],
        "reward_aet": r["reward_aet"],
        "reward_prestige": r["reward_prestige"],
        "status": r["status"],
    }


async def list_quests(conn, player_id, village: str) -> list[dict]:
    """Misiones ACTIVAS del jugador en esa aldea (caducando antes las viejas)."""
    await _expire_stale(conn, player_id, village)
    rows = await conn.fetch(
        "select * from quests where player_id = $1 and village_name = $2 and status = 'active' "
        "order by created_at",
        player_id, village,
    )
    return [_row_to_quest(r) for r in rows]


async def create_quest(conn, player_id, village: str, kind: str, objective: dict, target: int,
                       reward_aet: int, reward_prestige: int) -> dict:
    """Registra una mision propuesta por el plugin, con la recompensa RECORTADA al baremo."""
    if kind not in _KINDS:
        return {"status": "rejected", "reason": f"tipo de mision desconocido: {kind}"}
    await _expire_stale(conn, player_id, village)
    active = await conn.fetchval(
        "select count(*) from quests where player_id = $1 and village_name = $2 and status = 'active'",
        player_id, village,
    )
    if active >= MAX_ACTIVE_PER_VILLAGE:
        return {"status": "full", "reason": "ya tienes todas las misiones que da esta aldea"}

    max_aet, max_prestige = _KINDS[kind]
    aet = max(0, min(int(reward_aet), max_aet))
    prestige = max(0, min(int(reward_prestige), max_prestige))
    target = max(1, min(int(target), 10_000))
    row = await conn.fetchrow(
        """
        insert into quests (player_id, village_name, kind, objective, target, reward_aet, reward_prestige)
        values ($1, $2, $3, $4::jsonb, $5, $6, $7)
        returning *
        """,
        player_id, village, kind, json.dumps(objective or {}), target, aet, prestige,
    )
    return {"status": "ok", "quest": _row_to_quest(row)}


async def add_progress(conn, quest_id: str, player_id, delta: int) -> dict:
    """Suma avance a una mision propia. Devuelve si ya esta lista para cobrarse."""
    row = await conn.fetchrow(
        "select * from quests where id = $1 and player_id = $2 for update",
        uuid.UUID(quest_id), player_id,
    )
    if row is None:
        return {"status": "not_found"}
    if row["status"] != "active":
        return {"status": row["status"], "progress": row["progress"], "target": row["target"],
                "ready": False}
    progress = max(0, min(row["target"], row["progress"] + max(0, int(delta))))
    await conn.execute("update quests set progress = $1 where id = $2", progress, row["id"])
    return {
        "status": "ok",
        "progress": progress,
        "target": row["target"],
        "ready": progress >= row["target"],
    }


async def complete_quest(conn, quest_id: str, player_id, java_uuid: str) -> dict:
    """Da por cumplida una mision SI de verdad lo esta, paga en AET y suma prestigio.

    Quien decide que esta completa es esta funcion, mirando la fila persistida: ni el
    plugin ni la IA pueden cobrarla a ciegas.
    """
    row = await conn.fetchrow(
        "select * from quests where id = $1 and player_id = $2 for update",
        uuid.UUID(quest_id), player_id,
    )
    if row is None:
        return {"status": "not_found"}
    if row["status"] != "active":
        return {"status": "already", "detail": row["status"]}
    if row["progress"] < row["target"]:
        return {"status": "incomplete", "progress": row["progress"], "target": row["target"]}

    await conn.execute(
        "update quests set status = 'completed', completed_at = now() where id = $1", row["id"]
    )
    amount = decimal.Decimal(int(row["reward_aet"]))
    if amount > 0:
        acc = await _account(conn, uuid.UUID(java_uuid))
        banco = await _account(conn, _BANCO, "system")
        await _move(conn, banco, acc, amount, f"mision {row['kind']} en {row['village_name']}")

    rep = await conn.fetchrow(
        """
        insert into player_reputation (player_id, village_name, mission_points, last_activity, updated_at)
        values ($1, $2, $3, now(), now())
        on conflict (player_id, village_name) do update set
            mission_points = player_reputation.mission_points + excluded.mission_points,
            last_activity = now(), updated_at = now()
        returning mission_points, donated_total
        """,
        player_id, row["village_name"], int(row["reward_prestige"]),
    )
    return {
        "status": "ok",
        "reward_aet": int(row["reward_aet"]),
        "reward_prestige": int(row["reward_prestige"]),
        "mission_points": rep["mission_points"],
        "score": score(rep["mission_points"], rep["donated_total"]),
    }


async def register_donation(conn, player_id, java_uuid: str, village: str, amount: float) -> dict:
    """Cobra la aportacion al arca Y la anota en la reputacion del jugador, de una vez.

    Un solo viaje de red desde el plugin: mover el dinero y ganar prestigio son la misma
    operacion (o pasan las dos, o no pasa ninguna).
    """
    money = decimal.Decimal(str(amount)).quantize(decimal.Decimal("0.01"))
    if money <= 0:
        return {"status": "error", "detail": "La cantidad debe ser positiva"}
    acc = await _account(conn, uuid.UUID(java_uuid))
    if acc["balance"] < money:
        return {"status": "error", "detail": "Fondos insuficientes"}
    banco = await _account(conn, _BANCO, "system")
    await _move(conn, acc, banco, money, f"aportacion al arca de {village}")
    rep = await conn.fetchrow(
        """
        insert into player_reputation (player_id, village_name, donated_total, last_activity, updated_at)
        values ($1, $2, $3, now(), now())
        on conflict (player_id, village_name) do update set
            donated_total = player_reputation.donated_total + excluded.donated_total,
            last_activity = now(), updated_at = now()
        returning mission_points, donated_total
        """,
        player_id, village, money,
    )
    return {
        "status": "ok",
        "donated_total": float(rep["donated_total"]),
        "mission_points": rep["mission_points"],
        "score": score(rep["mission_points"], rep["donated_total"]),
    }


async def village_reputation(conn, village: str) -> list[dict]:
    """Jugadores con prestigio en esa aldea, con la puntuacion YA calculada aqui.

    La formula vive en el backend (no en el plugin) para no tenerla en dos sitios; el
    plugin solo fusiona esta lista con el peculio de sus aldeanos.
    """
    rows = await conn.fetch(
        """
        select p.java_uuid, p.username, r.mission_points, r.donated_total
        from player_reputation r join players p on p.id = r.player_id
        where r.village_name = $1
        """,
        village,
    )
    out = [
        {
            "player_uuid": str(r["java_uuid"]),
            "username": r["username"],
            "mission_points": r["mission_points"],
            "donated_total": float(r["donated_total"]),
            "score": score(r["mission_points"], r["donated_total"]),
        }
        for r in rows
        if r["java_uuid"] is not None
    ]
    out.sort(key=lambda e: e["score"], reverse=True)
    return out


async def player_reputation(conn, java_uuid: str) -> list[dict]:
    """Prestigio de UN jugador en todas las aldeas donde ha hecho algo."""
    rows = await conn.fetch(
        """
        select r.village_name, r.mission_points, r.donated_total, r.last_activity
        from player_reputation r join players p on p.id = r.player_id
        where p.java_uuid = $1
        """,
        uuid.UUID(java_uuid),
    )
    out = [
        {
            "village_name": r["village_name"],
            "mission_points": r["mission_points"],
            "donated_total": float(r["donated_total"]),
            "score": score(r["mission_points"], r["donated_total"]),
            "last_activity": r["last_activity"].isoformat(),
        }
        for r in rows
    ]
    out.sort(key=lambda e: e["score"], reverse=True)
    return out


async def decay_reputation(conn) -> dict:
    """DECADENCIA por abandono: si dejas de aparecer por una aldea, tu prestigio de
    misiones se desinfla (un X% por semana de ausencia, como mucho una vez por semana).

    Lo donado NO decae: la raiz cuadrada ya lo amortigua, no hace falta castigarlo dos
    veces. Asi el poder se mantiene con presencia, pero al que ayudo de verdad no se le
    borra del todo la huella.
    """
    factor = max(0.0, 1.0 - float(settings.reputation_decay_pct))
    result = await conn.execute(
        """
        update player_reputation
        set mission_points = floor(mission_points * $1::float8)::int, updated_at = now()
        where mission_points > 0
          and last_activity < now() - ($2 || ' days')::interval
          and updated_at < now() - interval '7 days'
        """,
        factor, str(settings.reputation_idle_days),
    )
    decayed = int(result.split()[-1]) if result and result.split()[-1].isdigit() else 0
    return {"status": "ok", "decayed": decayed}
