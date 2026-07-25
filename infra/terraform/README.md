# Terraform - Oracle Cloud Always Free

Infraestructura como código (recomendación del brief). Todo el entorno debe poder
recrearse desde cero sin pasos manuales.

## Estado

**Placeholder de Fase 4.** Aquí vivirá la definición de:

- Instancia(s) de cómputo Always Free (ARM Ampere)
- Red virtual (VCN), subredes y **security lists** (firewall: solo Velocity + Geyser)
- Volúmenes de bloque para datos persistentes
- DNS / dominio y certificados (SSL)

## Principio

Abrir un segundo continente o mover el backend a otra máquina = aplicar la misma
definición en un nuevo entorno (`terraform workspace` / variables), sin reconstruir nada.

El `.gitignore` ya excluye `*.tfstate` y `*.tfvars` (secretos). Se versionarán
`*.tfvars.example`.
