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
from datetime import datetime, timedelta, timezone

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


# Sucesos especiales: modifican la economia y le dan color a la cronica.
_FESTIVALS = [
    "Feria de la cosecha: el pueblo celebra y el comercio se dispara.",
    "Llega una caravana de mercaderes y todos hacen negocio.",
    "Bodas en la plaza: dias de bonanza para los negocios.",
]
_HARDSHIPS = [
    "Una plaga arruina parte de la cosecha: tiempos dificiles.",
    "Bandidos merodean los caminos y el comercio se resiente.",
    "Una tormenta dana los talleres: gastos extra para repararlos.",
]


# Ultimo nivel de prosperidad anotado en la cronica (para apuntar solo los CAMBIOS de rumbo).
_last_level: str | None = None


def _prosperity_line(level: str) -> str:
    return {
        "floreciente": "El pueblo florece: los negocios rebosan y no falta trabajo para nadie.",
        "prospero": "El pueblo prospera y se respira optimismo por las calles.",
        "estable": "El pueblo vive un tiempo de calma, sin grandes cambios.",
        "en apuros": "El pueblo atraviesa una mala racha y las cuentas aprietan.",
    }.get(level, f"El rumbo del pueblo cambia: ahora esta {level}.")


async def run_tick(conn) -> dict:
    """Ejecuta UN tick economico (con festivales, penurias y sustos). Devuelve un resumen."""
    banco = await _account(conn, _BANCO, "system")
    total_income = decimal.Decimal(0)
    total_upkeep = decimal.Decimal(0)

    # Evento global del dia: festival (bonanza) o penuria (perdidas). Poco frecuentes.
    roll = random.random()
    festival = roll < 0.12
    hardship = 0.12 <= roll < 0.24
    boost = decimal.Decimal("1.6") if festival else decimal.Decimal(1)

    for owner_id, _name, sector in _BUSINESSES:
        acc = await _account(conn, owner_id, "company")
        income = _money(decimal.Decimal(str(random.uniform(
            settings.sim_income_min, settings.sim_income_max))) * boost)
        upkeep = _money(income * _UPKEEP_RATIO)
        # 1 de cada 4 ticks un negocio tiene un mal dia (gastos > ingresos): puede perder.
        if random.random() < 0.25 or hardship:
            upkeep += _money(decimal.Decimal(str(random.uniform(0.5, 1.5))) * income)

        if income > 0:
            await _move(conn, banco, acc, income, f"produccion {sector}")
        if upkeep > 0:
            await _move(conn, acc, banco, upkeep, f"gastos {sector}")

        total_income += income
        total_upkeep += upkeep

    net_total = total_income - total_upkeep
    prov = await prosperity(conn)

    # Los balances economicos son lo MENOS importante: no se apunta el detalle cada tick. Solo
    # se deja constancia (bien redactada) cuando CAMBIA el rumbo del pueblo (florece / calma /
    # mala racha), para que la cronica no se llene de numeros ni se alargue.
    global _last_level
    if prov["level"] != _last_level:
        await _event(conn, "prosperity", _prosperity_line(prov["level"]),
                     {"prosperity": prov["level"], "wealth": prov["wealth"]})
        _last_level = prov["level"]

    if festival:
        await _event(conn, "festival", random.choice(_FESTIVALS), {})
    elif hardship:
        await _event(conn, "hardship", random.choice(_HARDSHIPS), {})

    return {"net": str(net_total), "prosperity": prov["level"], "wealth": prov["wealth"]}


async def prosperity(conn) -> dict:
    """Estado del pueblo segun la riqueza acumulada de sus negocios."""
    row = await conn.fetchrow(
        "select coalesce(sum(balance), 0) as total from accounts "
        "where owner_type = 'company' and currency = 'AET'"
    )
    total = float(row["total"])
    if total < 50:
        level = "en apuros"
    elif total < 500:
        level = "estable"
    elif total < 2000:
        level = "prospero"
    else:
        level = "floreciente"
    return {"level": level, "wealth": round(total, 2), "businesses": len(_BUSINESSES)}


async def evolve_population(conn) -> dict:
    """Ajusta la poblacion OBJETIVO del pueblo segun su prosperidad (crece o emigra)."""
    prov = await prosperity(conn)
    row = await conn.fetchrow("select population from settlement where world = 'main'")
    pop = row["population"] if row else settings.sim_min_population
    old = pop
    level = prov["level"]
    r = random.random()
    if level == "floreciente" and pop < settings.sim_max_population and r < 0.35:
        pop += 1
    elif level == "prospero" and pop < settings.sim_max_population and r < 0.15:
        pop += 1
    elif level == "en apuros" and pop > settings.sim_min_population and r < 0.30:
        pop -= 1

    if pop != old:
        await conn.execute(
            "update settlement set population = $1, updated_at = now() where world = 'main'", pop)
        if pop > old:
            await _event(conn, "growth",
                         "El pueblo prospera: llega un nuevo vecino a instalarse.", {"population": pop})
        else:
            await _event(conn, "decline",
                         "Tiempos duros: un vecino hace las maletas y emigra.", {"population": pop})
    return {"population": pop, "level": level}


async def village_state(conn) -> dict:
    """Estado del pueblo: poblacion, prosperidad y riqueza."""
    row = await conn.fetchrow("select population from settlement where world = 'main'")
    prov = await prosperity(conn)
    return {
        "population": row["population"] if row else settings.sim_min_population,
        "level": prov["level"],
        "wealth": prov["wealth"],
    }


async def _event(conn, kind: str, description: str, data: dict) -> None:
    await conn.execute(
        "insert into world_events (kind, description, data) values ($1, $2, $3::jsonb)",
        kind, description, _as_json(data),
    )


async def collect_rent(conn) -> dict:
    """Cobra la renta de las parcelas de alquiler vencidas; libera las que no puedan pagar."""
    charged = 0
    released = 0
    # Ojo: las cuentas se indexan por el UUID de Minecraft (java_uuid), no por players.id.
    due = await conn.fetch(
        """
        select p.id, p.rent, a.id as acc_id, a.balance, pl.username
        from plots p
        join players pl on pl.id = p.owner_id
        join accounts a on a.owner_type = 'player' and a.owner_id = pl.java_uuid and a.currency = 'AET'
        where p.rental and p.rent_due is not null and p.rent_due <= now()
        for update of p
        """
    )
    banco = await _account(conn, _BANCO, "system")
    nxt = datetime.now(timezone.utc) + timedelta(seconds=settings.rent_interval_seconds)
    for r in due:
        rent = r["rent"]
        if r["balance"] >= rent:
            await _move(conn, {"id": r["acc_id"]}, banco, rent, "renta parcela")
            await conn.execute("update plots set rent_due = $1 where id = $2", nxt, r["id"])
            charged += 1
        else:
            await conn.execute("delete from plots where id = $1", r["id"])
            await _event(conn, "social",
                         f"La parcela de {r['username']} se libero por impago del alquiler.", {})
            released += 1
    return {"status": "ok", "charged": charged, "released": released}


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
                    rent = await collect_rent(conn)       # cobra alquileres vencidos
                    village = await evolve_population(conn)  # el pueblo crece o mengua
            logger.info("Tick: %s | renta: %s | pueblo: %s", summary, rent, village)
        except Exception:  # noqa: BLE001 - la simulacion nunca debe tumbar el servicio
            logger.exception("Fallo un tick de simulacion (se continua).")
