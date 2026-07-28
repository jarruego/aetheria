# Misiones, prestigio y jugabilidad para jugadores reales (ideas de diseño)

> **La sección 1 (misiones + prestigio + alcaldía) ya está IMPLEMENTADA** (migración
> `0009_prestige_quests.sql`, `services/world-state/.../quests.py`, `QuestModule.java` y el
> ranking de `SettlementModule`). Lo que se hizo y con qué números está resumido en
> `CLAUDE.md`; para jugarlo, `docs/guia-jugador.md`. Aquí se conserva el diseño original —
> con dos matices sobre lo que se acabó construyendo: el prestigio del aldeano **reutiliza su
> peculio** en vez de un stat nuevo, y el acceso al granero es para el **top 3** y **solo al
> excedente** (no un escalón de rango). El resto del documento (sección 2 y los desbloqueos
> 1.4/1.5 más allá de la alcaldía) sigue **sin implementar**.

Ideas pendientes de estudio (el resto, **sin implementar**). Objetivo: que un jugador real que entra al
server tenga **cosas que hacer**, una **progresión** (dinero → prestigio → poder) y que el mundo se
sienta **más vivo**. Todo se apoya en lo que YA existe: economía AET, arca de aportaciones, granero,
mercado, colonos con oficios, alcalde, parcelas (F9), sucesos (festivales/penurias), crónica.

---

## 1. Sistema de MISIONES + PRESTIGIO (idea del dueño)

### 1.1. El dador de misiones (NPC)
Un NPC en la plaza al que **haces clic derecho** (como al mercader) y te ofrece 1–3 misiones. Dos
candidatos:
- El **alcalde** (ya existe: el vecino más veterano) — encaja narrativamente ("el pueblo necesita…").
- Un **pregonero/escribano** dedicado (nuevo NPC del ayuntamiento), para no cargar al alcalde.

Las misiones se generan por **código** (nunca el LLM decide recompensas), a partir del estado real
del pueblo (qué falta, qué evento acaba de pasar).

### 1.2. Tipos de misión (ligadas al mundo vivo, no genéricas)
- **Económicas:** "lleva 32 de trigo/hierro/madera al granero", "vende X en el mercado", "aporta Y
  AET al arca del pueblo".
- **Sociales:** "habla con N vecinos", "lleva un recado de un colono a otro", "ayuda a la boda de X".
- **Construcción:** "reclama una parcela y levanta una casa", "encarga algo al arquitecto".
- **Defensa/mantenimiento:** "limpia de monstruos el pueblo esta noche", "ayuda a reparar tras una
  penuria" (engancha con los sucesos de la simulación).
- **Exploración/expansión:** "explora un sitio para fundar una aldea nueva", "lleva un paquete a otra
  aldea por la carretera".

### 1.3. Recompensas: dinero **y PRESTIGIO**
- **AET** (inmediato).
- **Prestigio** (nuevo stat persistente por jugador, en la DB): reputación en el pueblo. No se
  compra: se **gana** cumpliendo misiones y aportando al común (el arca ya da la base económica).

### 1.4. El prestigio DESBLOQUEA (progresión hacia alcalde)
Escalones con título y ventajas:
1. **Vecino** — de serie.
2. **Notable** — parcelas más grandes / descuento en servicios (arquitecto, decorador).
3. **Consejero** — puede **fundar** una aldea, proponer una obra (prioriza el siguiente edificio).
4. **Alcalde** — el rango máximo (ver abajo).

El prestigio puede **decaer** si abandonas el pueblo mucho tiempo o si **esquilmas** el granero
(engancha con el contrapeso del arca): así el poder se mantiene, no se regala para siempre.

### 1.5. Ser ALCALDE (jugador) — el "endgame" social
Hoy el alcalde es el colono más veterano (automático). Un **jugador** con prestigio suficiente puede
**disputar/ganar** la alcaldía (por umbral de prestigio, o una "elección": quien más ha aportado).
Siendo alcalde:
- Tu **nombre en el cartel** de la plaza (ya hay cartel de alcalde).
- Un **porcentaje de la prosperidad** del pueblo entra a tu bolsillo (tesorería → alcalde).
- Puedes **convocar un festival** (dispara el evento de bonanza), **priorizar un edificio**, o
  **fijar una política** simple (más natalidad / más ahorro).
- Una **casa de alcalde** o despacho en el ayuntamiento.
- Responsabilidad: si el pueblo **decae** bajo tu mandato, pierdes prestigio (y la alcaldía).

### 1.6. Encaje técnico (con lo ya construido)
- **Prestigio:** columna nueva en `players` o tabla `player_reputation` (world-state).
- **Misiones activas:** tabla `quests` (jugador, tipo, objetivo, progreso, recompensa, estado). El
  plugin comprueba el progreso (items entregados, AET aportado…) y el gateway persiste.
- **Dador de misiones:** un NPC clicable (patrón del `MarketModule`), abre un **menú de inventario**
  con las misiones (icono + descripción + recompensa).
- **Alcaldía de jugador:** el `SettlementModule` ya elige alcalde; añadir "si hay un jugador con
  prestigio ≥ umbral en esta aldea, es él el alcalde", y engancharlo a la tesorería (como el arca).
- Regla de oro intacta: la **IA no reparte** recompensas ni decide misiones; todo por código +
  validador. La IA solo da **sabor** (el NPC te cuenta la misión con su voz).

---

## 2. Cómo hacerlo MÁS REAL y con MÁS JUGABILIDAD

### 2.1. Más "vida" (realismo) — que el pueblo parezca de verdad
- **Skins humanas** por oficio y sexo (ya en estudio): el mayor salto visual.
- **Agenda diaria más rica:** además de trabajo/taberna/cama, que vayan al **mercado**, que los
  **niños jueguen** en la plaza, que los **jubilados** se sienten al sol, que se **visiten** entre casas.
- **Relaciones entre vecinos:** amistades/rivalidades con nombre; el cotilleo (ya existe) que nazca
  de hechos reales (bodas, muertes, relevos) y module cómo se hablan.
- **Estaciones y clima** que afecten al trabajo y al ánimo (invierno = menos cosecha; fiesta en
  primavera).
- **El pueblo REACCIONA a los sucesos:** guirnaldas/faroles en un festival, luto (grises, campana) en
  una muerte, andamios cuando se está construyendo.
- **Reputación del jugador entre los vecinos:** te saludan por tu nombre y tu rango; recuerdan lo que
  hiciste (la memoria de NPC ya existe).

### 2.2. Más "cosas que hacer" (jugabilidad para jugadores reales)
- **Misiones + prestigio + alcaldía** (sección 1) — el bucle principal.
- **Negocio propio del jugador:** monta tu **granja/tienda** que produce y renta (participar en la
  economía, no solo vender a un sumidero).
- **Contratos jugador↔NPC y jugador↔jugador:** "entrega N de hierro por X AET", commissions al
  arquitecto, encargos con plazo.
- **Defensa del pueblo (PvE):** oleadas/pillagers de noche; defenderlo da prestigio y botín. Encaja
  con que las casas ya resisten explosiones.
- **Cooperativo:** varios jugadores levantando una **ciudad** juntos (F9 ya tiene parcelas/ciudades),
  con metas comunes (llegar a X habitantes, fundar N aldeas).
- **Caravanas y comercio entre aldeas:** ahora que hay **carreteras**, mover mercancía de una aldea a
  otra por dinero (y riesgo de bandidos → engancha con las penurias).
- **Títulos y marcadores:** el más rico, el de más prestigio, el mejor constructor; visible en el HUD.

### 2.3. Priorización (qué da MÁS jugabilidad con MENOS esfuerzo)
1. **Misiones + prestigio** (dador de misiones con menú de inventario, reusando el patrón del
   mercado). Es el bucle que engancha y reusa casi todo lo existente. **Alto valor / esfuerzo medio.**
2. **Alcaldía de jugador** (encima de las misiones; el alcalde ya existe). **Alto valor / esfuerzo bajo.**
3. **Skins humanas** (salto visual). **Alto valor / esfuerzo medio.**
4. **Defensa del pueblo (PvE nocturno).** **Valor medio / esfuerzo medio.**
5. **Negocio propio / contratos.** **Valor alto / esfuerzo alto.**
6. **Estaciones, reacción del pueblo a sucesos, agendas ricas.** **Valor medio / esfuerzo medio.**

**Recomendación:** empezar por **1 → 2** (misiones y alcaldía): con eso, un jugador que entra tiene
objetivos, progresión y un techo aspiracional (gobernar el pueblo), reusando la economía, el arca, el
alcalde y las parcelas que ya están hechas.
