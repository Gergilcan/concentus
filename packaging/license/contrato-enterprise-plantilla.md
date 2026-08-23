# Contrato de licencia enterprise — PLANTILLA

> Plantilla de trabajo para la venta manual ("contact us"). NO es asesoramiento jurídico:
> antes del primer contrato real, que la revise un abogado. Los huecos van entre ⟦corchetes⟧.
> La cláusula 6 (verificación de asientos) es el motivo de que esta plantilla exista: con venta
> por asientos, el contrato es el mecanismo de detección real de discrepancias — la renovación
> es el momento natural de contraste. Ver el spec: docs/superpowers/specs/2026-08-22-licensing-design.md.

**Licenciante:** Gerard Gilabert Canal (⟦NIF⟧, ⟦dirección⟧) — "el Autor".
**Licenciatario:** ⟦Razón social⟧ (⟦CIF⟧, ⟦dirección⟧) — "el Cliente".
**Fecha:** ⟦fecha⟧

## 1. Objeto

El Autor concede al Cliente una licencia de uso comercial de Concentus (publicado bajo PolyForm
Noncommercial 1.0.0, que por sí sola no permite uso comercial), incluidas las funciones
enterprise: base de datos compartida / modo servidor, múltiples miembros y roles, e inicio de
sesión SSO. La licencia se materializa en una clave firmada ("licencia técnica") emitida a
nombre del Cliente con los asientos y la vigencia contratados.

## 2. Asientos

Licencia por asientos: hasta ⟦N⟧ cuentas de usuario activas en el conjunto de despliegues del
Cliente. La aplicación cuenta y limita los asientos; el límite contractual es este, no el técnico.

## 3. Precio y duración

⟦Mensual: X € por asiento y mes⟧ / ⟦Anual: X × 12 × 0,90 € por asiento y año (10% de descuento)⟧.
Renovación por períodos iguales salvo preaviso de ⟦30⟧ días. El impago extingue la licencia al
final del período pagado; la aplicación aplica además un período de gracia técnico de 14 días.

## 4. Lo que la licencia no permite

Ceder, sublicenciar o compartir la licencia técnica con terceros; usarla en despliegues que no
sean del Cliente; eliminar o alterar los mecanismos de licencia de la aplicación. El código es
abierto: modificar esos mecanismos es técnicamente trivial y contractualmente una infracción.

## 5. Soporte y actualizaciones

Durante la vigencia: actualizaciones publicadas y soporte por ⟦canal: email/issues⟧ con
respuesta orientativa en ⟦N⟧ días laborables. Sin SLA salvo anexo.

## 6. Verificación de asientos

El Cliente certificará a solicitud del Autor — como máximo ⟦una vez por período de renovación⟧ —
el número de cuentas de usuario activas en sus despliegues (la pantalla de miembros de la
aplicación basta como evidencia). Si el recuento supera los asientos contratados, el Cliente
regularizará la diferencia desde la fecha en que se superó, al precio vigente, sin penalización
si la certificación fue espontánea. La negativa a certificar durante ⟦30⟧ días es causa de
resolución.

## 7. Terminación

Extinguida la licencia, el Cliente puede seguir usando Concentus en los términos de PolyForm
Noncommercial (uso no comercial) y conserva acceso de exportación a sus datos; las funciones
enterprise dejan de estar licenciadas.

## 8. Ley y fuero

Legislación española; juzgados de ⟦ciudad⟧.

---
Firmas: ⟦Autor⟧ · ⟦Cliente⟧
