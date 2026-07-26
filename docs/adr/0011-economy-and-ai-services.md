# ADR-0011: Economía AET y servicios inteligentes de pago (Fase 6)

- **Estado:** Aceptada
- **Fecha:** 2026-07-26

## Contexto

Con el mundo ya persistente (ADR-0010) faltaba un **sistema económico** que diera
sentido al progreso y, sobre todo, un modelo para **vender la IA como servicio** sin
romper el principio del proyecto: la IA propone, un validador determinista aprueba, y el
plugin ejecuta solo acciones de la lista blanca. La pregunta de diseño era *cómo cobrar
por la IA sin vender ventajas de juego* (pay-to-win) ni permitir que un LLM mueva dinero.

## Decisión

**Moneda única `AET`** sobre las tablas `accounts` / `transactions` que ya existían desde
la migración `0001`. No hace falta migración nueva: se reutiliza el esquema.

- **Cuentas:** una por propietario y moneda. Se crean *perezosamente* con saldo inicial
  de `100.00 AET` la primera vez que se consulta el saldo de un jugador. La cuenta del
  sistema (`00000000-…-0000`, `owner_type='system'`) actúa de **sumidero/banco** y nace a 0.
- **Transferencias entre jugadores** (`/v1/pay`): atómicas dentro de una transacción SQL,
  con comprobación de fondos (400 `Fondos insuficientes` si no llega). Cantidades siempre
  positivas.
- **Cobro de servicios** (`/internal/charge`): debita al jugador y acredita al banco.

**Servicios inteligentes de pago** (`/v1/service` → `/internal/service`): un jugador
contrata al *Arquitecto IA*, *Decorador* o *Urbanista*. El orden es la clave de seguridad:

1. La IA **propone** un plan a partir de la descripción del jugador (gratis de calcular).
2. El **validador** determinista lo aprueba o rechaza (lista blanca de acciones).
3. **Solo si se aprueba** se cobra el precio del servicio. Si el jugador no tiene fondos,
   se devuelve `rejected` y **no se cobra ni se ejecuta nada**.
4. El plugin ejecuta las acciones ya validadas y devueltas.

Precios (AET): `arquitecto=50`, `decorador=20`, `urbanista=80`, resto `30` por defecto.

El flujo respeta la arquitectura: **Plugin → API Gateway (auth) → orchestrator →
world-state → Postgres**. El LLM nunca toca dinero ni el mundo; solo produce una
propuesta que pasa por el validador y por el cobro determinista.

## Consecuencias

- (+) Se vende **el servicio de la IA** (construir, decorar, planificar), nunca ventajas
  de combate ni items exclusivos: modelo de negocio alineado con "la IA es el sistema
  operativo del mundo".
- (+) Imposible pagar por un plan que el validador rechaza (se valida antes de cobrar) y
  el dinero nunca lo mueve el LLM (transacciones SQL atómicas en world-state).
- (+) Cero migraciones: reutiliza `accounts`/`transactions` de `0001`.
- (-) El saldo inicial y los precios están *hardcodeados* en código; cuando haga falta
  economía dinámica (inflación, precios por demanda) se moverán a configuración/DB.
- (-) La cuenta "banco" acumula todo lo cobrado sin drenaje (aún no hay sumideros ni
  recompensas que devuelvan AET al mundo); es la base para futuras Fases.
