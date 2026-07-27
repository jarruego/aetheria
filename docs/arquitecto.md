# El Arquitecto (`/arquitecto`) — combinaciones y rediseño en dos vías

Servicio de pago guiado por chat (clic en las opciones). Módulo:
`minecraft/plugin-aetheria/.../ArchitectModule.java`. Se cobra vía gateway (`pay`) y es
**deshacible** con `/deshacer` (guarda un snapshot antes de construir).

## Qué se puede elegir hoy (4 decisiones)

1. **Tamaño** — ancho y plantas (el arquitecto tira un dado dentro del rango):
   - Pequeña: 7×7–9×9, 1–2 plantas · Mediana: 9×9–11×11, 2–3 · Grande: 11×11–13×13, 3–4.
2. **Material (gama)** — paleta + precio (factor): Rústica ×6 · Piedra ×8 · Noble ×10 · Lujo ×14.
   (Se evitan bloques revendibles: el "dorado" del lujo es `gilded_blackstone`.)
3. **Estilo** — silueta: Casona (equilibrada), Torre (+2 plantas, −1 ancho), Aldeana (compacta),
   Vanilla (casa de aldea REAL de Minecraft).
4. **Amueblada** — interior con muebles (+30 AET/planta) o vacía.

Precio: `(ancho/2)² × plantas × factor  (+30×plantas si amueblada)`.

## Problema: estilos que CHOCAN con las demás decisiones

- **Aldeana** ignora el **material** elegido (usa su propia paleta de pueblo).
- **Vanilla** ignora **todo** (tamaño, material, estilo y amueblada): coloca una plantilla del juego
  ya diseñada y amueblada.

Preguntar por material/estilo/amueblada cuando luego se ignoran es confuso.

## Rediseño acordado: DOS VÍAS (pendiente de implementar con el build en verde)

Al hacer `/arquitecto`, primero se elige el **tipo**:

- **Vía 1 · A medida (la genera la app)** — `/arquitecto tipo procedural`
  Flujo: **tamaño → material → estilo (solo Casona o Torre) → amueblada → confirmar**.
  Se quita **Aldeana** de aquí (era la única que chocaba con el material; y la casa de pueblo real
  la hace mejor la vía 2). Casona y Torre respetan el material.

- **Vía 2 · De aldea (Minecraft)** — `/arquitecto tipo vanilla`
  Flujo: **tamaño → confirmar**. Coloca una casa de aldea REAL (`VanillaStructures`), ya amueblada;
  no pregunta material/estilo/amueblada. Precio fijo por tamaño (p. ej. pequeña/mediana/grande →
  120 / 220 / 400 AET, ajustable).

### Notas de implementación
- Añadir `Order.type` ("procedural" | "vanilla"). `start()` pregunta el tipo.
- Vía procedural: `setStyle` deja solo `casona`/`torre` (fuera `aldeana`/`vanilla`).
- Vía vanilla: tras el tamaño, ir directo a confirmar; en `buildAt` usar
  `VanillaStructures.placeRandomHouse` (idealmente filtrando la plantilla por tamaño:
  `plains_small_house_*` / `plains_medium_house_*` / `plains_big_house_1`).
- Mantener anti-solape (`buildRegistry`) y deshacer en ambas vías.
- Es un cambio **aislado a `ArchitectModule`** (+ opcionalmente una variante de
  `VanillaStructures.placeRandomHouse` que acepte tamaño). No toca el resto del plugin.
