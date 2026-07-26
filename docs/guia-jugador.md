# Guía del jugador — Aetheria

Aetheria es un servidor de Minecraft con **vida propia**: un pueblo cuyos vecinos tienen
rutina, una economía que evoluciona sola (prospera o decae) y una IA que hace de "sistema
operativo del mundo". Esta guía resume **todo lo que puedes hacer**. En el juego tienes la
misma información en un libro: escribe `/guia`.

## Primeros pasos

1. **Entras por el lobby** (una sala flotante). Ahí te recibe **Aeon, el conserje**: un
   personaje que ronda la sala. Haz **clic derecho** sobre él y pregúntale lo que quieras
   (por ejemplo, "¿cómo gano dinero?"). Lo sabe todo del server y te orienta.
2. **Pisa un portal** para viajar: esmeralda → **mundo principal** (donde está el pueblo y
   la economía), diamante → **mundo creativo** (construir sin límites).
3. En los mundos de juego, **apareces junto al portal de vuelta** al lobby (una zona segura
   y decorada, sin monstruos). Camina hacia el pueblo, al sur.

Al entrar por primera vez recibes la **Guía de Aetheria** (un libro). A la derecha verás
siempre un **marcador** con tu saldo y el estado del pueblo.

## El dinero: AET

La moneda es **AET**. Empiezas con **100 AET**. Lo ves en el marcador o con `/balance`.

- `/balance` — tu saldo.
- `/pay <jugador> <cantidad>` — pagar a otro jugador (transferencia instantánea).

## Ganar dinero

Hay dos formas principales:

### 1) Trabajos (cobras por hacer)
Simplemente **haciendo cosas** ganas AET automáticamente (aparece "+X AET" en pantalla):

- **Minería**: carbón, cobre, hierro, oro, redstone, lapis, **diamante**, esmeralda,
  restos antiguos... (cuanto más raro, más paga).
- **Tala**: talar troncos.
- **Cosecha**: recoger cultivos **maduros** (trigo, zanahoria, patata, remolacha...).
- **Caza**: derrotar monstruos hostiles (zombis, esqueletos, brujas, blazes...).

El pago se acumula y se ingresa cada pocos segundos.

### 2) Mercado (vender recursos)
Vende lo que produces al mercado del pueblo:

- `/sell` — vende lo que llevas en la mano.
- `/sell all` — vende **todo** lo de ese tipo en tu inventario.
- `/worth` — cuánto vale lo que llevas en la mano.
- `/shop` — lista de precios de referencia.

## Servicios de la IA (de pago)

Contrata a los **maestros del pueblo**: la IA diseña y **construye por ti**, y **solo te
cobra si lo consigue** (nunca pagas por un encargo que no se puede hacer):

- `/aetheria servicio arquitecto <qué quieres>` — construcciones.
- `/aetheria servicio decorador <qué quieres>` — decoración.
- `/aetheria servicio urbanista <qué quieres>` — planificación.

Se venden **servicios**, nunca ventajas de combate: nadie compra poder, solo trabajo.

## Tu hogar y tus tierras

- `/sethome` — guarda tu casa aquí.
- `/home` — vuelve a tu casa.
- `/claim` — **reclama la parcela** (el chunk) donde estás. Cuesta AET y pasa a ser tuya:
  nadie más puede romper ni poner bloques dentro.
- `/claim info` — de quién es la parcela donde estás.
- `/unclaim` — libera tu parcela.

## El pueblo está vivo

- **Vecinos con rutina**: Nara (granjera) y Pol (vigilante) viven en sus casas y trabajan
  en su granja / puesto de guardia. De día trabajan, al atardecer se reúnen en la plaza y
  de noche vuelven a casa. Habla con ellos con **clic derecho** (se paran a atenderte).
- **La economía evoluciona sola**, haya o no gente conectada: los negocios producen,
  gastan, viven festivales y penurias. El pueblo puede estar **en apuros, estable,
  próspero o floreciente** (lo ves en el marcador).
- `/aetheria cronica` — la **crónica del mundo**: qué ha pasado mientras no estabas.

## Otros comandos útiles

- `/aetheria ask <mensaje>` — hablar con la IA.
- `/aetheria plan <objetivo>` — pedir un plan a la IA (lo ejecuta si el validador lo aprueba).
- `/guia` — te da otra vez el libro-guía.

## Cómo funciona por dentro (resumen)

- **La IA nunca toca el mundo directamente.** Propone un *plan*; un **validador
  determinista** lo aprueba o rechaza; y solo entonces el servidor ejecuta acciones de una
  **lista blanca**. Por eso no puede "romper" el mundo ni darte ventajas indebidas.
- **La aldea, las rutinas y la economía son código** (deterministas), no improvisación del
  modelo de IA. La IA se usa para **conversar** y para **encargos** que pasan por el validador.
- Todo el estado (saldo, casas, parcelas, memoria de los NPC, crónica) vive en una **base
  de datos**, así que persiste entre sesiones.

¡Bienvenido a Aetheria! El pueblo prospera o decae contigo dentro.
