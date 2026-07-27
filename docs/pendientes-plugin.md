# Tweaks del plugin pendientes (esperando "build verde" / coordinación con la otra sesión)

Pequeños arreglos pedidos por el dueño que NO se pueden aplicar mientras la otra sesión tiene
WIP sin commitear en el plugin (`SettlementModule`, `VillageModule` a veces, etc.). Aplicar en
cuanto el build compile y el árbol esté limpio. Cada uno lleva el cambio EXACTO.

## 1. Taberna: mesa que tapa la puerta
`VillageModule.buildTavern`, ~línea 512:
```java
tavernTable(cx - 2, cz, floorY);   // MAL: la mesa cae en el eje de la puerta (z=cz)
```
La puerta está en el muro oeste en `z=cz`; esa mesa (sillas en cx-3 y cx-1) tapa el pasillo de
entrada. **Fix:** moverla fuera del eje, p. ej.:
```java
tavernTable(cx, cz + 2, floorY);
```
(Lo introdujo el cambio del tabernero. `VillageModule` no siempre está en su WIP → se puede
aplicar en cuanto compile.)

## 2. Máximo 2 hijos por pareja
`SettlementModule.bearChild(int vid)`: hoy no hay límite. Tras construir la lista `mothers`,
descartar las madres que ya tengan 2 hijos:
```java
mothers.removeIf(m -> childCount(m.name) >= 2);   // cada pareja: maximo 2 hijos
if (mothers.isEmpty()) {
    return false;   // parejas fertiles pero ya con 2 hijos -> que venga un inmigrante
}
```
Con el ayudante (cuenta hijos vivos: bebés + ya adultos, por el nombre de la madre):
```java
private int childCount(String motherName) {
    int n = 0;
    for (final Child c : children) if (motherName.equals(c.parent)) n++;
    for (final Colono c : colonos) if (motherName.equals(c.parent)) n++;
    return n;
}
```
(`Child.parent` y `Colono.parent` guardan el nombre de la MADRE — ver `bearChild` y
`growAdult`.)

## 3. Arquitecto en DOS VÍAS
Ver `docs/arquitecto.md`. Requiere tocar también el catálogo del creativo (#16), que la otra
sesión acaba de hacer → coordinar antes de aplicar.

---
Hechos ya (no rehacer): casa de matrimonio con 2 habitaciones/2 camas (commit `7c0a95b`),
cortejo (los fundadores no se casan el primer ciclo).
