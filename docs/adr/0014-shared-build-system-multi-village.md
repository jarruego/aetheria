# ADR-0014: Sistema de construcción física compartido y mundo multi-aldea

- **Estado:** Aceptada
- **Fecha:** 2026-07-27

## Contexto

El "servidor vivo" acumuló **varios caminos que construyen bloques** de forma independiente:
la aldea autónoma (`SettlementModule`), el arquitecto de pago (`ArchitectModule`), el
decorador (`DecoratorModule`), los blueprints por chat (`Blueprint`/`PlanExecutor`) y los
esquemáticos FAWE (`SchematicModule`). Cada uno tenía su propia lógica de terreno y de
"dónde puedo poner esto", con dos problemas crónicos:

1. **Terreno.** Solo `SettlementModule` sabía nivelar bien (altura real del suelo ignorando
   hojas/troncos, no construir sobre agua/hielo). El resto plantaba estructuras flotando,
   hundidas o sobre agua.
2. **Solapes.** Nada impedía que un camino pisara lo que otro ya había construido (una casa
   del arquitecto sobre una casa de colono, un esquemático sobre la plaza...). La lista
   `placed` que evitaba esto era privada de `SettlementModule`.

Además, un único pueblo que solo "engordaba" no cumplía la visión de "ciudades y varias
comunidades": al llenarse, la población se apiñaba en el mismo sitio.

## Decisión

**Extraer dos sistemas compartidos por TODOS los caminos de construcción:**

- **`TerrainPlanner`** (utilidad sin estado): valida y prepara el terreno de una huella.
  Nivela **columna a columna** rellenando con material coherente (el propio subsuelo) o
  tallando lo que sobresale, y sobre agua/hielo **clava pilotes** hasta el lecho real
  dejando el agua visible (en vez de un tapón de tierra o de rechazar el sitio). Modelo
  tomado del generador de estructuras de Minecraft (heightmap + "beard"). Lo usan
  `SettlementModule`, `ArchitectModule`, `DecoratorModule`, `Blueprint` y `SchematicModule`.
- **`BuildRegistry`** (registro persistente en `regions.txt`): cajas 3D de todo lo ya
  construido, con `overlaps()` (¿choca esta caja con alguna?) y `removeAt()` (al demoler una
  casa, su solar vuelve a estar libre). Cualquier camino comprueba solape **antes** de
  construir; el arquitecto prueba 2-3 huecos al lado y solo entonces avisa/rechaza.

**Mundo multi-aldea** (`SettlementModule`): las aldeas son entidades con **nombre propio**
(`village.txt`). Cuando una llega a `PER_TOWN=8` vecinos, una pareja parte a **fundar una
aldea nueva** a 220-400 bloques sobre tierra firme (evento *fundacion*); al entrar en su
radio, un título de bienvenida anuncia el nombre. `findBuildSpot` busca el sitio válido
**más cercano a la plaza** (anillos hacia fuera) para que cada aldea crezca compacta.

## Consecuencias

- (+) **Un solo lugar** para el terreno y el anti-solape: arreglar un bug (p. ej. construir
  sobre agua) beneficia a los cinco caminos a la vez.
- (+) Ningún camino pisa lo ya construido, ni la aldea autónoma ni el jugador de pago.
- (+) El pueblo deja de ser un solo núcleo: nacen comunidades separadas con identidad
  (nombre, alcalde, granero), base para gobiernos y ciudades futuras.
- (+) `regions.txt`/`village.txt`/`buildings.txt` persisten en la carpeta del plugin; el
  estado sobrevive a reinicios y se libera al demoler.
- (-) `TerrainPlanner` y `BuildRegistry` viven en el **plugin**, no en el backend: son
  detalle de ejecución física, no lógica de mundo (el backend solo conoce población y
  prosperidad). Consciente: encaja con "el plugin es el único que ejecuta".
- (-) El registro es por instancia del plugin (como la caché de claims, ADR-0013); con
  varios servidores del mismo mundo habría que compartirlo. Hoy cada mundo es un servidor.
