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

### Arquitecto guiado — `/arquitecto` (o `/arq`)
Se abre un **menú de inventario** con iconos (ya no hay que clicar texto en el chat). Lo
primero que te pregunta es **qué clase de casa** quieres:

- **Casa a medida**: la diseña el arquitecto. Eliges **tamaño** (pequeña/mediana/grande),
  **material** (rústica/piedra/noble/lujo), **estilo** (casona, aldeana o torre) y si la
  quieres **amueblada**. Cada casa sale distinta (ancho, altura, tejado y ventanas se tiran
  a dados). Precio ≈ base(tamaño) × material + mobiliario.
- **Casa tipo Minecraft**: una casa de aldea **REAL del juego**. Solo eliges el tamaño y se
  sortea una plantilla de ese grupo (8 pequeñas, 2 medianas, 1 grande). Tarifa fija:
  **60 / 140 / 260 AET**.

Al final ves el **presupuesto** y confirmas. Después, **haz clic derecho en el suelo** donde
quieras la puerta, o escribe **`/arq ok`** para ponerla delante de ti.

- **Para construir necesitas ser dueño de la parcela**: ponte sobre tu terreno y usa
  `/claim` primero. Si no cabe justo ahí, prueba unos bloques al lado antes de rechazarlo.
- Si no te gusta, **`/deshacer`** la retira y te devuelve el dinero.

### Decorador guiado — `/decorador` (o `/dec`)
Embellece **tu parcela** con pequeñas estructuras eligiendo en un **menú de inventario**:
**jardín** con flores, **farola**, **estatua** o una **gran fuente**. Se cobra según la pieza
(15–60 AET) y solo construye si eres dueño del terreno. Se levanta frente a ti.

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
- **Los vecinos trabajan de verdad**: el granjero siega una espiga madura y la **replanta**,
  el arquero tala (y deja un brote) y con esa madera hace **flechas**, el cantero pica en su
  **cantera**, el herrero **funde lo que el cantero saca**, el pastor esquila ovejas de verdad,
  el pescador echa el sedal y el tabernero cocina. Todo lo que producen acaba en el **granero**
  de su aldea, y de ese trabajo vive la economía: si el pueblo deja de trabajar, decae.
- **El albañil repara**: si una casa se daña (un creeper, un incendio, un boquete), el cantero
  del pueblo la **reconstruye** igual que estaba. Si no hay cantero vivo, se queda rota.
- **La economía evoluciona sola**, haya o no gente conectada: los negocios producen,
  gastan, viven festivales y penurias. El pueblo puede estar **en apuros, estable,
  próspero o floreciente** (lo ves en el marcador, junto al nº de **Habitantes**).
- `/aetheria cronica` — la **crónica del mundo**: un **libro** con lo que ha ido pasando
  (nacimientos, bodas, muertes, fundaciones de aldeas, festivales...), de lo más reciente a lo
  más antiguo.

## Cómo crece una aldea (y cómo puedes ayudar)

Cada aldea **ahorra** en un fondo común lo que producen sus vecinos, y de ahí sale también lo
que cuesta mantenerlos. Cuando el fondo cubre el **coste del siguiente vecino**, llega uno:
**nace** de una pareja de esa aldea o, si no hay ninguna fértil, **se instala un forastero**.
Lo ves en el marcador como **`Próximo vecino: 62%`**, con la barra y el `160/240 AET` debajo.

**Cada vecino cuesta el doble que el anterior** (30, 60, 120, 240, 480, 960 AET...), así que
un pueblo llega solo hasta unos 6-7 vecinos y ahí se atasca. Y al revés: si el fondo se queda
**en números rojos**, la aldea no da de comer a todos y **pierde un vecino** (emigra).

Ahí entras tú. Puedes **aportar dinero al fondo de la aldea que quieras**, de tres formas:

- **El ARCA de la plaza**: un cofre rotulado junto al pozo. Haz clic y elige **25, 100 o 500
  AET**; la ventana te dice cuánto lleva ahorrado el pueblo y cuánto le falta.
- **El ALCALDE**: **agáchate y haz clic derecho** sobre él (de pie, el clic es para hablar).
  Él mismo se acerca de vez en cuando a decirte cuánto falta.
- **`/donar <cantidad>`** (o `/don`), a la aldea en la que estés.

No compra ventajas: es una **inversión**. El pueblo crece antes, más vecinos trabajan y su
excedente acaba en el granero.

## Encargos del pueblo y PRESTIGIO

En la plaza de cada aldea hay un **pregonero**. Haz clic derecho sobre él y se abre su **tablón
de encargos**: hasta **tres a la vez**, y no son genéricos — salen de lo que a *esa* aldea le
pasa ahora mismo:

- **Abastecer el granero** con lo que de verdad escasea allí.
- **Aportar al arca** lo que le falte para el próximo vecino.
- **Vender género** en el mercado.
- **Hablar con los vecinos** (vale tanto prestigio como los encargos de dinero: no gana siempre
  quien más AET mueve).
- **Reclamar una parcela** y echar raíces.
- **Llevar un paquete** a la aldea de al lado (se entrega al pregonero de destino).
- **Echar una mano** cuando el pueblo está en apuros.

Los que son de entregar género se cumplen **haciendo clic en el encargo** con el material
encima; el resto el pregonero se entera solo. Cada encargo paga **AET** y, sobre todo,
**prestigio en esa aldea**. Los encargos sin cumplir caducan a los **3 días** y entran otros.

Tu prestigio en una aldea = **puntos de misión + raíz cuadrada de lo que hayas donado** a su
arca. Lo de la raíz es a propósito: donar 4 veces más **no** da 4 veces más prestigio, así que
la alcaldía **no se compra**. Y si desapareces de una aldea más de dos semanas, tus puntos de
misión se desinflan un 10% por semana (lo donado no se toca).

## El tablón de la plaza y la alcaldía

Sobre la plaza flota un **tablón grande de prestigio** con los **ocho primeros** de esa aldea,
**vecinos y jugadores en la misma tabla**: los vecinos puntúan por lo que han ahorrado
trabajando (más un pellizco por veteranía) y tú por tu prestigio. **El primero de la lista es
el alcalde**, sea aldeano o jugador, y su nombre aparece en el panel de la plaza y en la
crónica cuando hay relevo.

`/prestigio` (o `/ranking`) te dice tu puesto en la aldea donde estés, o en cuáles tienes
prestigio si estás a campo abierto.

## El granero

Cada aldea tiene un **granero** donde sus vecinos van dejando lo que producen: trigo, pescado,
lana, piedra, lingotes, libros, flechas, pan... Cada género tiene su barril, para que un oficio
muy rápido no lo copie entero y deje sin materia prima a los demás (el herrero necesita la
piedra del cantero; el arquero, la madera).

**El granero es la despensa del pueblo, no un cofre público.** Solo los **tres primeros del
tablón de prestigio** de esa aldea pueden sacar de él, y aun así **solo el excedente**: lo que
pase de **dos pilas** de ese género. Si no llega a eso, el pregonero te dirá que eso lo
necesita el pueblo. Al hacer clic en un barril con excedente te lo llevas en mano y la reserva
se queda intacta.

Cuando algo **no cabe**, no se pierde: el pueblo **vende el excedente** con una pequeña prima
y ese dinero va al fondo de la aldea. Un pueblo muy productivo crece más rápido justo por eso.

## Otros comandos útiles

- `/aetheria ask <mensaje>` — hablar con la IA.
- `/aetheria plan <objetivo>` — pedir un plan a la IA (lo ejecuta si el validador lo aprueba).
- `/aetheria cronica` — el **libro** con la historia del pueblo (nacimientos, bodas, muertes,
  fundaciones de aldeas...).
- `/aetheria schem <list|paste|save>` — catálogo de esquemáticos (si el servidor tiene FAWE):
  lista, pega uno donde estás o guarda tu selección de WorldEdit.
- `/deshacer` — revierte la última construcción del arquitecto/decorador (con reembolso).
- `/prestigio` (o `/ranking`) — tu puesto en el tablón de la aldea (el primero es el alcalde).
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
