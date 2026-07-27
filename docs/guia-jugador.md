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

## Servicios: construir con ayuda (de pago)

Primero, **`/servicios`** te muestra todos los servicios y precios.

### Arquitecto guiado — `/arquitecto`
Un asistente te va **preguntando y guiando** para encargar una **casa a medida**: eliges
**tamaño** (pequeña/mediana/grande), **material** (madera/piedra/ladrillo/lujo) y si la
quieres **amueblada**. El arquitecto **calcula el precio** según lo que pides y solo
construye cuando **confirmas** (haciendo clic en las opciones del chat) y **pagas**.

- Precio ≈ base(tamaño) × material + mobiliario. Ej.: mediana de piedra amueblada ≈ 248 AET.
- **Para construir necesitas ser dueño de la parcela**: ponte sobre tu terreno y usa
  `/claim` primero. Así nadie construye donde no debe. La casa se levanta **frente a ti**.

### Decorador guiado — `/decorador`
Embellece **tu parcela** con pequeñas estructuras eligiendo en un menú: **jardín** con
flores, **farola**, **estatua** o una **gran fuente**. Se cobra según la pieza (15–60 AET)
y solo construye si eres dueño del terreno. Se levanta frente a ti.

### Otros encargos a la IA
- `/aetheria servicio decorador <qué quieres>` y `/aetheria servicio urbanista <qué quieres>`:
  la IA propone y **solo cobra por lo que construye** (nunca pagas por un plan que no se
  puede hacer, ni por una simple frase).

Se venden **servicios**, nunca ventajas de combate: nadie compra poder, solo trabajo.

## Tu hogar y tus tierras

- `/sethome` — guarda tu casa aquí.
- `/home` — vuelve a tu casa.
- `/claim` — **reclama la parcela** donde estás. Abre un menú para elegir **tamaño** y **modo**:
  - **Tamaño**: pequeña (1×1 chunk), mediana (2×2) o grande (3×3).
  - **Comprar** (pago único, tuya para siempre): 50 AET la pequeña, 200 la mediana, 450 la
    grande (crece con el área).
  - **Alquilar** (depósito + una **renta** cada periodo): más barata de entrada, pero si no
    puedes pagar la renta, la parcela **se libera** automáticamente.
  Una vez tuya, nadie más puede romper ni poner bloques dentro.
- `/claim info` — de quién es la parcela donde estás.
- `/unclaim` — libera tu parcela.

## Moverte por el mundo — `/warps`

El pueblo es grande: usa `/warps` para ver los destinos y viajar rápido a **plaza**,
**mercado**, **taberna** o **spawn** (`/warp <destino>`, o haz clic en la lista).

## El pueblo está vivo

- **Vecinos de verdad, generados solos**: el pueblo no tiene personajes fijos. Un mundo nuevo
  empieza con **dos fundadores** (un hombre y una mujer) y la población **crece sola**: llegan
  colonos, se casan, tienen **hijos**, envejecen, se jubilan y con los años mueren (y llega un
  relevo). Cada vecino tiene **nombre, edad, oficio y familia**. Habla con cualquiera con
  **clic derecho** (se para a atenderte) y te contará de su vida.
- **Rutina diaria**: de día trabajan en su puesto (huerto, embarcadero, herrería, biblioteca...
  según su oficio), al atardecer se reúnen en la plaza y de noche vuelven a casa. Los solteros
  viven en una **casa pequeña**; al casarse se les construye una **mediana** para la familia.
- **Varias aldeas**: cuando una aldea se llena, una pareja parte a **fundar otra nueva** con su
  propio nombre, lejos. Al **entrar** en una aldea verás **su nombre en pantalla**. Cada aldea
  tiene un **alcalde** (con su cartel en la plaza) y un **granero** donde los oficios van
  dejando lo que producen (trigo, lana, hierro...).
- **Las casas de los vecinos están protegidas**: no puedes romperlas ni ponerles bloques, y
  **aguantan a los creepers y la TNT** (no hace falta reconstruir el pueblo). Eso sí, el
  **terreno natural** (tierra, piedra, arena, minerales) junto a las casas **sí lo puedes
  minar**. Cuando un vecino muere o emigra, su casa se derriba y el solar vuelve a ser hierba.
- **La economía evoluciona sola**, haya o no gente conectada: los negocios producen,
  gastan, viven festivales y penurias. El pueblo puede estar **en apuros, estable,
  próspero o floreciente** (lo ves en el marcador, junto al nº de **Habitantes**).
- `/aetheria cronica` — la **crónica del mundo**: un **libro** con lo que ha ido pasando
  (nacimientos, bodas, muertes, fundaciones de aldeas, festivales...), de lo más reciente a lo
  más antiguo.

## Otros comandos útiles

- `/aetheria ask <mensaje>` — hablar con la IA.
- `/aetheria plan <objetivo>` — pedir un plan a la IA (lo ejecuta si el validador lo aprueba).
- `/aetheria cronica` — el **libro** con la historia del pueblo (nacimientos, bodas, muertes,
  fundaciones de aldeas...).
- `/aetheria schem <list|paste|save>` — catálogo de esquemáticos (si el servidor tiene FAWE):
  lista, pega uno donde estás o guarda tu selección de WorldEdit.
- `/deshacer` — revierte la última construcción del arquitecto/decorador (con reembolso).
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
