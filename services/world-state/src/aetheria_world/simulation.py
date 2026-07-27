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

# Sectores economicos del pueblo (cuentas 'company'). UUID fijos: se crean de forma perezosa.
# Nombres GENERICOS (no de un aldeano concreto): los colonos nacen y mueren, pero los oficios
# del pueblo permanecen. Antes ponia "La Granja de Nara"/"La Herreria de Bruno" -> datos fantasma
# de personajes que ya no existen.
_BUSINESSES = [
    (uuid.UUID("a0000000-0000-0000-0000-000000000001"), "Los huertos y granjas", "agricultura"),
    (uuid.UUID("a0000000-0000-0000-0000-000000000002"), "Los talleres y la fragua", "artesania"),
    (uuid.UUID("a0000000-0000-0000-0000-000000000003"), "El mercado y los tratos", "comercio"),
]

# Efecto en la tesoreria del pueblo de cada SUCESO DE VIDA (asi la sociedad mueve la economia):
# negativo = gasto del comun; positivo = ingreso. Ocasionales y modestos (se autocompensan:
# las herencias equilibran bodas y nacimientos).
_LIFE_EVENT_AET = {
    "boda": -40,          # el pueblo costea la casa nueva y la celebracion
    "nacimiento": -15,    # subsidio al recien nacido
    "obituario": 50,      # el patrimonio del difunto pasa al comun del pueblo
    "fundacion": -70,     # capital que se llevan los colonos a fundar otra aldea
}

_UPKEEP_RATIO = decimal.Decimal("0.6")    # los gastos se comen buena parte del ingreso
# (mas alto = la economia NO sube siempre: fluctua y se estanca en vez de crecer sin fin)
_CENT = decimal.Decimal("0.01")

# #11 - La economia vive del TRABAJO FISICO de los aldeanos, no de un numero aleatorio.
# El ingreso "de oficio" (aleatorio) queda como RESIDUAL (rentas, trueques menores); el grueso
# entra por `record_production`, que abona lo que los colonos han cosechado/talado/picado/fundido
# de verdad en el mundo. Si el pueblo deja de trabajar, la economia decae sola.
_BASELINE_RATIO = decimal.Decimal("0.35")
# Cada habitante CUESTA (comida, techo, mantenimiento). Es el contrapeso de la produccion:
# un pueblo grande que no trabaja se arruina y emigra gente.
_UPKEEP_PER_CAPITA = decimal.Decimal("4.0")
# Techo por peticion de produccion: el plugin nunca puede inyectar dinero sin limite.
_MAX_PRODUCTION_PER_CALL = decimal.Decimal("150")

# Sector economico de cada apunte de produccion (nombre -> cuenta de negocio).
_SECTOR_ACCOUNTS = {
    "agricultura": _BUSINESSES[0][0],
    "artesania": _BUSINESSES[1][0],
    "comercio": _BUSINESSES[2][0],
}


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

    # La economia depende de la POBLACION REAL: mas vecinos trabajando -> mas produccion (y al
    # reves). Es el vinculo entre la sociedad viva (Fase 7) y la economia (Fase 8).
    pop_row = await conn.fetchrow("select population from settlement where world = 'main'")
    population = pop_row["population"] if pop_row else settings.sim_min_population
    pop_factor = decimal.Decimal(str(max(0.4, min(8.0, population / 5.0))))

    # Gasto fijo del pueblo por HABITANTE (comida, techo, mantenimiento), repartido entre los
    # sectores. Es lo que obliga a que el pueblo TRABAJE de verdad para sostenerse.
    per_capita = _money(decimal.Decimal(population) * _UPKEEP_PER_CAPITA / len(_BUSINESSES))

    for owner_id, _name, sector in _BUSINESSES:
        acc = await _account(conn, owner_id, "company")
        # Ingreso RESIDUAL (el grueso lo aporta la produccion fisica de los colonos).
        income = _money(decimal.Decimal(str(random.uniform(
            settings.sim_income_min, settings.sim_income_max)))
            * boost * pop_factor * _BASELINE_RATIO)
        upkeep = _money(income * _UPKEEP_RATIO) + per_capita
        # ~4 de cada 10 ticks un negocio tiene un mal dia (gastos > ingresos): puede perder.
        if random.random() < 0.4 or hardship:
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


async def record_production(conn, entries: list) -> dict:
    """Abona a los sectores la PRODUCCION FISICA que los aldeanos han hecho de verdad en el
    mundo (cosechar, talar, picar piedra, fundir metal). Lo envia el plugin por lotes.

    El dinero sale de la cuenta del sistema, como cualquier otro ingreso. El importe total
    esta ACOTADO por peticion: aunque el plugin se descontrolara (o alguien colara una
    llamada con el token interno), no puede inyectar dinero sin limite en la economia.
    """
    banco = await _account(conn, _BANCO, "system")
    credited = decimal.Decimal(0)
    for entry in entries:
        sector = str(getattr(entry, "sector", "") or "")
        owner = _SECTOR_ACCOUNTS.get(sector)
        if owner is None:
            continue
        value = _money(max(0.0, float(getattr(entry, "value", 0) or 0)))
        if value <= 0:
            continue
        if credited + value > _MAX_PRODUCTION_PER_CALL:
            value = _MAX_PRODUCTION_PER_CALL - credited
            if value <= 0:
                break
        acc = await _account(conn, owner, "company")
        goods = (getattr(entry, "goods", None) or "").strip()[:80]
        reason = f"trabajo de los aldeanos ({sector})" + (f": {goods}" if goods else "")
        await _move(conn, banco, acc, value, reason)
        credited += value
    return {"status": "ok", "credited": float(credited)}


async def prosperity(conn) -> dict:
    """Estado del pueblo segun la riqueza acumulada de sus negocios."""
    row = await conn.fetchrow(
        "select coalesce(sum(balance), 0) as total from accounts "
        "where owner_type = 'company' and currency = 'AET'"
    )
    total = float(row["total"])
    if total < 50:
        level, low, high = "en apuros", 0.0, 50.0
    elif total < 500:
        level, low, high = "estable", 50.0, 500.0
    elif total < 2000:
        level, low, high = "prospero", 500.0, 2000.0
    else:
        level, low, high = "floreciente", 2000.0, 2000.0
    # Cuanto le falta al pueblo para el SIGUIENTE escalon de prosperidad (0-100). Es la barra
    # de progreso hacia "mas vecinos": la poblacion objetivo sube cuando el pueblo prospera.
    progress = 100.0 if high <= low else max(0.0, min(100.0, (total - low) * 100.0 / (high - low)))
    # Probabilidad, por tick, de que llegue un vecino nuevo (o se marche uno) con este nivel.
    chance = {"floreciente": 12.0, "prospero": 5.0, "estable": 0.0, "en apuros": -25.0}[level]
    return {
        "level": level,
        "wealth": round(total, 2),
        "businesses": len(_BUSINESSES),
        "progress": round(progress, 1),
        "next_level": _NEXT_LEVEL[level],
        "growth_chance": chance,
    }


_NEXT_LEVEL = {
    "en apuros": "estable",
    "estable": "prospero",
    "prospero": "floreciente",
    "floreciente": "floreciente",
}


async def evolve_population(conn) -> dict:
    """Ajusta la poblacion OBJETIVO del pueblo segun su prosperidad (crece o emigra)."""
    prov = await prosperity(conn)
    row = await conn.fetchrow("select population from settlement where world = 'main'")
    pop = row["population"] if row else settings.sim_min_population
    old = pop
    level = prov["level"]
    # Crecimiento EXPONENCIAL (#14): cuanta mas gente hay, mas nace y mas rapido crece el pueblo
    # (la natalidad es proporcional a la poblacion, como en la realidad). Un caserio de dos
    # tarda en arrancar; un mundo de cuarenta vecinos se llena solo.
    factor = 1.0 + pop / 8.0            # x1.25 con 2 vecinos, x5 con 32
    step = max(1, pop // 12)            # y no llegan de uno en uno cuando el mundo ya es grande
    r = random.random()
    if level == "floreciente" and pop < settings.sim_max_population and r < 0.12 * factor:
        pop = min(settings.sim_max_population, pop + step)
    elif level == "prospero" and pop < settings.sim_max_population and r < 0.05 * factor:
        pop = min(settings.sim_max_population, pop + step)
    elif level == "en apuros" and pop > settings.sim_min_population and r < 0.25:
        pop = max(settings.sim_min_population, pop - step)

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
        "progress": prov["progress"],
        "next_level": prov["next_level"],
        "growth_chance": prov["growth_chance"],
    }


async def _event(conn, kind: str, description: str, data: dict) -> None:
    await conn.execute(
        "insert into world_events (kind, description, data) values ($1, $2, $3::jsonb)",
        kind, description, _as_json(data),
    )


async def apply_life_event(conn, kind: str) -> None:
    """Mueve AET en la tesoreria del pueblo segun el suceso de vida (boda, nacimiento, muerte,
    fundacion): asi lo que pasa en la sociedad se nota en la economia y en la prosperidad. Sin
    efecto para sucesos sin coste (mejoras, relevos, cargos)."""
    delta = _LIFE_EVENT_AET.get(kind)
    if not delta:
        return
    banco = await _account(conn, _BANCO, "system")
    treasury = await _account(conn, _BUSINESSES[0][0], "company")  # tesoreria = comun del pueblo
    amount = _money(abs(delta))
    if delta < 0:
        await _move(conn, treasury, banco, amount, f"gasto del comun ({kind})")
    else:
        await _move(conn, banco, treasury, amount, f"ingreso al comun ({kind})")


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
