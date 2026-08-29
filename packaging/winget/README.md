# Publicar en winget

winget instala desde el repositorio comunitario `microsoft/winget-pkgs`, que acepta paquetes por
pull request. **La primera versión se envía a mano; todas las siguientes las envía el workflow de
release** (job `channels`, que usa komac y necesita el secreto `WINGET_TOKEN`).

Por qué este canal importa: winget descarga sin la marca "viene de internet" del navegador, así
que el aviso de SmartScreen que asusta con un instalador sin firmar no aparece. Y como el NSIS es
per-user, tampoco hay diálogo de elevación. No sustituye a firmar — sigue siendo lo correcto —
pero quita el muro de hoy.

## Primera vez (una sola vez, ~10 min)

1. Crea un token de GitHub (classic) con scope `public_repo`: github.com/settings/tokens.
   Guárdalo también como secreto `WINGET_TOKEN` del repo para las versiones siguientes.
2. Instala la herramienta oficial:

       winget install wingetcreate

3. Desde la raíz de este repo, envía los manifests ya escritos:

       wingetcreate submit --token TU_TOKEN packaging/winget/manifests/g/Gergilcan/Concentus/0.1.8

   (Alternativa sin instalar nada: abre un PR a `microsoft/winget-pkgs` copiando el directorio
   `packaging/winget/manifests/g/Gergilcan/Concentus/0.1.8` a la misma ruta de su repo.)

4. El bot de winget-pkgs valida (hash, URL, instalación silenciosa en una VM) y un moderador
   humano lo aprueba. Suele tardar de horas a pocos días. Cuando se mergea:

       winget install concentus

## Las siguientes versiones

Nada. El job `channels` del workflow de release detecta cada tag estable, y komac clona los
manifests de la versión anterior, cambia URL/hash/versión y abre el PR solo. Si el secreto
`WINGET_TOKEN` no existe, el paso se salta con un aviso en el log en vez de romper la release.

## Verificación local antes de enviar

    winget validate packaging/winget/manifests/g/Gergilcan/Concentus/0.1.8
    winget install --manifest packaging/winget/manifests/g/Gergilcan/Concentus/0.1.8
