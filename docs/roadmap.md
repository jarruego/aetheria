# Roadmap por fases

Cada fase deja algo funcionando y verificable.

## Fase 0 - Fundacion  (COMPLETA)
- [x] Monorepo + Git + estructura de carpetas
- [x] ADRs iniciales
- [x] Contrato de API (OpenAPI) plugin a backend
- [x] Esqueletos FastAPI (api-gateway, ai-orchestrator) con endpoint de salud
- [x] docker-compose base
- [x] CI (lint + tests) en GitHub Actions
- [x] Repositorio remoto en GitHub
- [x] Verificacion end-to-end en contenedores (health, auth, plan validado)

## Fase 1 - Red Minecraft  (COMPLETA salvo prueba con cliente real)
- [x] Velocity como unico punto de entrada (modern forwarding), escucha en :25565
- [x] Lobby (Paper) + Main (Paper) independientes, solo accesibles via Velocity
- [x] Geyser (UDP 19132) + Floodgate para Bedrock (movil, consola)
- [x] Secreto de forwarding materializado por script (fuera de git)
- [x] Verificacion: contenedores arrancan sin crash-loop y Velocity responde al ping
- [ ] Pendiente (usuario): login real Java + Bedrock y salto lobby<->main

## Fase 2 - Backend + Base de datos  (COMPLETA)
- [x] Postgres local (equivalente a Supabase) en docker-compose
- [x] Migraciones SQL versionadas con runner idempotente (db/migrate.sh, schema_migrations)
- [x] world-state: resumenes estructurados (no bloques) sobre la DB
- [x] API Gateway conectado a la DB via world-state (proxy /v1/worlds, /v1/world/{key}/summary)
- [x] Verificacion: migracion aplicada, seed, resumenes correctos end-to-end, tests

## Fase 3 - IA + Validador  (backend COMPLETO; ejecucion en plugin pendiente)
- [x] Adaptador LLM intercambiable (stub por defecto = coste cero; claude listo con key)
- [x] Conversacion en 3 niveles (L1 codigo, L2 local/stub, L3 LLM), a prueba de gasto
- [x] Planner usa contexto del world-state -> Plan JSON -> Validador (aprobado/rechazado)
- [x] Verificacion end-to-end sin coste (3 niveles + plan con contexto real del mundo)
- [x] Plugin Java (Paper): /aetheria ask|plan|npc, ejecuta plan aprobado por lista blanca
- [x] Acciones fisicas reales: SAY, MOVE_TO (pathfinding), GIVE_ITEM, PLACE_BLUEPRINT
      (catalogo acotado), OPEN_TRADE (mercader); NPC como entidad real (Villager)

## Fase 4 - Cloud (IaC)
- Terraform sobre Oracle Cloud Always Free -> definido en `infra/terraform/` (ADR-0008), pendiente de `apply`
- SSL, dominio, firewall, backups, monitorizacion

## Jugabilidad Minecraft (extras, HECHOS)
- [x] Dos perfiles: lean (cloud/local minimo) y full (lobby+main+creative+End)
- [x] Lobby = hub void (sala con faro y cristaleras), aventura/invulnerable/sin mobs
- [x] Portales del lobby a main (esmeralda) y creativo (diamante); vuelta al lobby en cada mundo
- [x] Comandos /sethome y /home (persistencia local v1)
- [x] Servidor creativo (superflat) como backend de Velocity

## Fase 5 - El mundo recuerda (camino de escritura a la DB)  (COMPLETA)
- [x] Camino de escritura: plugin -> gateway -> world-state -> Postgres (ADR-0010)
- [x] Registrar jugadores al entrar (tabla players deja de ser solo la semilla)
- [x] Casas (/home) migradas a la DB (migracion 0002, una por servidor)
- [x] Memoria de conversacion de NPC (migracion 0003): los NPC recuerdan lo que les cuentas
- [x] Personalidad humana por NPC (nombre + caracter; ya no hablan como robots)
- [x] Auditoria de planes (plan_audit): cada plan de la IA queda registrado
- (La economia/encargos se traslada a la Fase 6: es un sistema grande por si mismo)

## Fase 6 - Economia y servicios IA  (COMPLETA en su nucleo)
- [x] Moneda AET, cuentas y transacciones sobre accounts/transactions (ADR-0011, sin
      migracion nueva). Cuentas perezosas con saldo inicial 100 AET; cuenta "banco" del
      sistema como sumidero.
- [x] Saldo y pagos entre jugadores: /balance y /pay (comandos in-game + /v1/balance,
      /v1/pay). Transferencias atomicas con control de fondos insuficientes.
- [x] Servicios inteligentes de PAGO: Arquitecto (50), Decorador (20), Urbanista (80).
      /aetheria servicio <tipo> <que quieres>. Orden seguro: la IA propone -> validador
      aprueba -> SOLO entonces se cobra (nunca se paga por un plan rechazado ni sin fondos).
      El modelo de negocio de la vision: vender servicios, nunca ventajas.
- [ ] Profesiones y empresas (futuro)
- [ ] Encargos/contratos (contracts) entre jugadores y NPC (futuro)

## Fase 7 - NPC vivos (rutinas)  (PENDIENTE)
- [ ] Horarios, rutinas y pathfinding por codigo (hoy los guias son estaticos)
- [ ] NPC que se mueven, trabajan y tienen agenda

## Fase 8 - El mundo evoluciona solo  (PENDIENTE)
- [ ] Simulacion en el backend (cron/ticks) que evoluciona economia y ciudades aunque no
      haya nadie conectado

## Fase 9 - Estructuras sociales  (PENDIENTE)
- [ ] Parcelas con propietarios, ciudades, gobiernos, contratos entre jugadores

## Mejoras transversales  (PENDIENTE)
- [ ] Seguridad: filtro de contenido en respuestas de NPC + rate-limit por jugador
- [ ] NPC con aspecto HUMANO real (skins) via Citizens o packets (hoy son aldeanos)
- [x] Memoria a largo plazo (migracion 0004): ficha evolutiva del jugador que condensa lo
      viejo (concentra muchas charlas en un perfil), poda los turnos ya resumidos; corto
      plazo verbatim (~10 turnos) + largo plazo difuso. La IA nunca se satura.
- [ ] Backups automaticos (DB + mundos), monitorizacion/logs, CI que compile el plugin Java
- [ ] Prueba de login Bedrock (Geyser) y salto entre mundos con cliente real