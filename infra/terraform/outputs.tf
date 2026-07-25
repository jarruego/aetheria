# ============================================================
# Aetheria AI - Salidas
# Lo que el dueno necesita justo despues del apply, listo para copiar.
# ============================================================

output "public_ip" {
  description = "IP publica reservada de la maquina (estable entre reinicios)."
  value       = oci_core_public_ip.this.ip_address
}

output "ssh_command" {
  description = "Comando SSH listo para copiar y pegar."
  value       = "ssh ubuntu@${oci_core_public_ip.this.ip_address}"
}

output "minecraft_java_address" {
  description = "Direccion a poner en el cliente Java (Multijugador > Anadir servidor)."
  value       = "${oci_core_public_ip.this.ip_address}:${var.minecraft_java_port}"
}

output "minecraft_bedrock_address" {
  description = "Direccion y puerto para el cliente Bedrock (movil, consola)."
  value       = "${oci_core_public_ip.this.ip_address} (puerto UDP ${var.bedrock_port})"
}

output "instance_id" {
  description = "OCID de la instancia (por si hay que abrir un ticket o mirarla en la consola)."
  value       = oci_core_instance.this.id
}

output "availability_domain" {
  description = "Dominio de disponibilidad donde se creo la instancia."
  value       = local.availability_domain
}

output "image_used" {
  description = "Imagen de SO resuelta automaticamente por el data source."
  value       = local.ubuntu_images[0].display_name
}

output "bootstrap_log_command" {
  description = "Como seguir el arranque automatico (cloud-init tarda 5-15 min la primera vez)."
  value       = "ssh ubuntu@${oci_core_public_ip.this.ip_address} 'sudo tail -f /var/log/aetheria-bootstrap.log'"
}

output "tunnel_command" {
  description = "Tunel SSH para llegar al backend, que NO esta expuesto a internet."
  value       = "ssh -N -L 8080:localhost:8080 -L 8090:localhost:8090 -L 8070:localhost:8070 ubuntu@${oci_core_public_ip.this.ip_address}"
}
