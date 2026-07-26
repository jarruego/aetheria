"""Fase 8 - El mundo evoluciona solo.

Una simulacion economica que corre en el backend por TICKS, aunque no haya ningun
jugador conectado. Cada tick, los negocios del pueblo producen ingresos y pagan sus
gastos; el resultado se persiste en la DB (cuentas/transacciones) y se resume en la
cronica (`world_events`). Asi la economia respira sola y, al volver, el jugador ve que
el mundo ha seguido su curso.

Es deterministica en su estructura y aleatoria en su magnitud (variedad de juego); NUNCA
la mueve el LLM: es simulacion por codigo, igual que las rutinas de la Fase 7.
"""

from __future__ import annotations

import asyncio
import decimal
import logging
import random
import uuid

from aetheria_world.config import settings
from aetheria_world.db import is_ready, pool

logger = logging.getLogger("aetheria_world.simulation")

# Cuenta del sistema (banco/mundo): fuente y sumidero de la masa monetaria.
_BANCO = uuid.UUID("00000000-0000-0000-0000-000000000000")

# Negocios del pueblo (cuentas 'company'). UUID fijos: se crean de forma perezosa.
_BUSINESSES = [
    (uuid.UUID("a0000000-0000-0000-0000-000000000001"), "La Granja de Nara", "agricultura"),
    (uuid.UUID("a0000000-0000-0000-0000-000000000002"), "La Herreria de Bruno", "metalurgia"),
    (uuid.UUID("a0000000-0000-0000-0000-000000000003"), "El Mercado del Pueblo", "comercio"),
]

_UPKEEP_RATIO = decimal.Decimal("0.35")   # los gastos se comen parte del ingreso
_CENT = decimal.Decimal("0.01")


def _money(value: float | decimal.Decimal) -> decimal.Decimal:
    return decimal.Decimal(str(value)).quantize(_CENT)


async def _account(conn, owner_id: uuid.UUID, owner_type: str):
    """Cuenta AET del propietario; la crea con saldo 0 si no existe."""
    row = await conn.fetchrow(
        "select id, balance from accounts where owner_type = $1 and owner_id = $2 and currency = 'AET'",
        owner_type, owner_id,
    )
    if row is None:
        row = await conn.fetchrow(
            "insert into accounts (owner_type, owner_id, balance, currency) values ($1, $2, 0, 'AET') "
            "returning id, balance",
            owner_type, owner_id,
        )
    return row


async def _move(conn, src, dst, amount: decimal.Decimal, reason: str) -> None:
    """Mueve AET de una cuenta a otra y lo registra (permite saldo negativo en el banco)."""
    await conn.execute("update accounts set balance = balance - $1 where id = $2", amount, src["id"])
    await conn.execute("update accounts set balance = balance + $1 where id = $2", amount, dst["id"])
    await conn.execute(
        "insert into transactions (from_account, to_account, amount, reason) values ($1, $2, $3, $4)",
        src["id"], dst["id"], amount, reason,
    )


async def run_tick(conn) -> dict:
    """Ejecuta UN tick economico. Devuelve un resumen de lo ocurrido."""
    banco = await _account(conn, _BANCO, "system")
    total_income = decimal.Decimal(0)
    total_upkeep = decimal.Decimal(0)
    lines: list[str] = []

    for owner_id, name, sector in _BUSINESSES:
        acc = await _account(conn, owner_id, "company")
        income = _money(random.uniform(settings.sim_income_min, settings.sim_income_max))
        upkeep = _money(income * _UPKEEP_RATIO)

        # El banco (mundo) paga el ingreso al negocio; el negocio paga sus gastos al banco.
        if income > 0:
            await _move(conn, banco, acc, income, f"produccion {sector}")
        if upkeep > 0:
            await _move(conn, acc, banco, upkeep, f"gastos {sector}")

        net = income - upkeep
        total_income += income
        total_upkeep += upkeep
        lines.append(f"{name} gano {net} AET ({sector})")

    net_total = total_income - total_upkeep
    description = "El pueblo trabajo: " + "; ".join(lines) + f". Balance neto {net_total} AET."
    await conn.execute(
        "insert into world_events (kind, description, data) values ($1, $2, $3::jsonb)",
        "economy",
        description,
        _as_json({"income": str(total_income), "upkeep": str(total_upkeep), "net": str(net_total)}),
    )

    # De vez en cuando, un vaiven de mercado (color, sin efecto sobre saldos por ahora).
    if random.random() < 0.25:
        pct = random.randint(-8, 8)
        verb = "subieron" if pct >= 0 else "bajaron"
        await conn.execute(
            "insert into world_events (kind, description, data) values ($1, $2, $3::jsonb)",
            "market",
            f"Los precios del mercado {verb} un {abs(pct)}%.",
            _as_json({"pct": pct}),
        )

    return {"income": str(total_income), "upkeep": str(total_upkeep), "net": str(net_total)}


def _as_json(obj: dict) -> str:
    import json

    return json.dumps(obj)


async def simulation_loop() -> None:
    """Bucle de fondo: corre un tick cada `sim_tick_seconds`. Degrada sin caer."""
    interval = max(10, settings.sim_tick_seconds)
    logger.info("Simulacion del mundo activa: un tick cada %ss.", interval)
    while True:
        await asyncio.sleep(interval)
        if not is_ready():
            continue
        try:
            async with pool().acquire() as conn:
                async with conn.transaction():
                    summary = await run_tick(conn)
            logger.info("Tick economico: %s", summary)
        except Exception:  # noqa: BLE001 - la simulacion nunca debe tumbar el servicio
            logger.exception("Fallo un tick de simulacion (se continua).")
