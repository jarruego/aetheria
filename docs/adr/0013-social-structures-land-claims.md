# ADR-0013: Estructuras sociales — parcelas reclamables (Fase 9)

- **Estado:** Aceptada
- **Fecha:** 2026-07-26

## Contexto

La visión incluye "ciudades, gobiernos y contratos entre jugadores". El primer ladrillo
imprescindible de cualquier sociedad en Minecraft es la **propiedad de la tierra**: poder
reclamar un terreno propio y que otros no puedan destruirlo. Sin eso no hay convivencia ni
construcción segura. La tabla `plots` (con `owner_id`, coordenadas y `city_id`) ya existía
desde la migración `0001`, pero nadie escribía en ella.

## Decisión

**Parcelas alineadas a chunk (16×16).** Reclamar por chunk —en vez de rectángulos
arbitrarios— simplifica la detección de solapes, el borde visual y, sobre todo, la
**protección**: comprobar si un bloque está protegido es un simple desplazamiento
`bloque >> 4` para obtener su chunk. Un jugador reclama el chunk donde está con `/claim`.

- **La verdad vive en la DB** (`plots`, vía gateway → world-state, ADR-0010). El backend
  valida el **solape** (409), cobra el precio en **AET** integrando la economía (ADR-0011;
  400 sin cobrar si no hay fondos) y comprueba la **propiedad** al liberar.
- **Protección con caché en el plugin.** Consultar la DB en cada `BlockBreak`/`BlockPlace`
  sería inviable. El plugin (`ClaimModule`) mantiene un mapa en memoria
  `chunk → propietario`, cargado al arrancar (`GET /v1/claims?world=`) y actualizado al
  reclamar/liberar. Los eventos de bloque se resuelven contra la caché, sin red.
- **Reclamar cuesta AET.** La tierra tiene precio (`CLAIM_PRICE`), así el sistema social se
  apoya en el económico y da un sumidero de dinero al mundo.

## Consecuencias

- (+) Propiedad real y protegida: base para ciudades, gobiernos y contratos (Fase 9+).
- (+) Reutiliza `plots` y la economía sin esquema nuevo.
- (+) Protección O(1) por bloque gracias a la caché; el arranque tolera fallos de red (si
  no puede cargar, avisa y sigue: sin protección hasta la próxima carga, nunca crashea).
- (-) La caché es por instancia del plugin; con varios servidores del mismo mundo habría
  que invalidarla/compartirla. Hoy cada mundo es un servidor, así que basta.
- (-) Reclamos por chunk (no rectángulos finos); es una elección deliberada de simplicidad.
- (-) Sin reembolso al liberar y sin límite de parcelas por jugador todavía: reglas de
  gobierno (impuestos, límites, ciudades) quedan para iteraciones siguientes.
