# Instalador de modpack CurseForge

`InstalarModpack.java` lee el ZIP de exportación de CurseForge y prepara una carpeta con `mods` y los archivos de `overrides`. No necesita clave de API ni dependencias externas.

## Uso

Requiere Java 17 o posterior y conexión a Internet. En PowerShell, desde la raíz del proyecto:

```powershell
java InstalarModpack.java
```

Para usar el endpoint público de CurseForge sin API key, añade `--api`:

```powershell
java InstalarModpack.java --api
```

En este modo se construye `https://www.curseforge.com/api/v1/mods/<projectID>/files/<fileID>/download` directamente con los IDs de `manifest.json`. No se necesita `modlist.html` ni una asociación por posición. CurseForge puede restringir o bloquear algunas descargas.

Por defecto utiliza `ServerTest_26.3-0.1.zip` y crea `ServerTest_26.3-0.1`. También se pueden especificar otras rutas, con o sin `--api`:

```powershell
java InstalarModpack.java "otro-modpack.zip" "otra-carpeta"
java InstalarModpack.java --api "otro-modpack.zip" "otra-carpeta"
```

Sin `--api`, el programa construye la URL `https://www.curseforge.com/minecraft/mc-mods/<mod>/download/<fileID>` con los enlaces de `modlist.html` y los IDs de `manifest.json`. Si la respuesta es HTML, también intenta la variante `/file`. Sin `-i`, no sobrescribe archivos diferentes existentes.

## Usar un ZIP remoto

```powershell
java InstalarModpack.java --zipLink "https://ejemplo.com/modpack.zip" --api -i "C:\ruta\a\mi instancia"
```

La URL debe ser HTTP o HTTPS y devolver directamente el ZIP del modpack; se permiten redirecciones. El archivo se descarga en una ubicación temporal y se elimina al finalizar, incluso si ocurre un error controlado. Una terminación forzada del proceso puede impedir la limpieza.

`--zipLink` se puede usar con o sin `--api` y con o sin `-i`. Sin `-i` se utiliza la carpeta predeterminada `ServerTest_26.3-0.1`. No se admiten rutas posicionales junto a `--zipLink`; utiliza `-i` para especificar una instancia existente. Ten en cuenta que `-i` reemplaza por completo su carpeta `mods`, como se explica a continuación.

## Instalar en una instancia existente

Usa `-i` seguido de la carpeta de la instancia, con o sin `--api`:

```powershell
java InstalarModpack.java --api -i "C:\ruta\a\mi instancia"
java InstalarModpack.java -i "C:\ruta\a\mi instancia" "otro-modpack.zip"
```

La instancia debe existir. No se puede combinar `-i` con una carpeta de destino posicional.

**Atención: el contenido completo de `mods` se reemplaza**, incluidos mods y subcarpetas ajenos al modpack. Haz una copia de seguridad antes de ejecutar el comando. Los mods del ZIP y las descargas se preparan en una carpeta temporal; si falla alguna descarga, se conserva la carpeta `mods` anterior. Al reemplazarla, se intenta restaurar la anterior si falla el cambio. El resto de `overrides` se copia a la instancia sin sobrescribir archivos distintos; puede quedar parcialmente copiado si se produce un error.

## Limitaciones

- En el modo predeterminado, los enlaces se asocian con los archivos **por posición**. `modlist.html` no contiene los `projectID`, por lo que no es posible verificar la asociación solo con estos dos archivos; incluso con el mismo número de entradas, podrían estar en distinto orden. El modo `--api` utiliza ambos IDs directamente y evita este problema.
- CurseForge puede devolver páginas intermedias, errores o desafíos de Cloudflare. En la prueba realizada, la URL de ejemplo devolvió HTTP 403. En ese caso el programa informa del fallo y muestra el enlace para descargar manualmente desde el navegador. Sin acceso automático no puede verificar ni reconocer el archivo descargado manualmente en una reejecución.
- El manifiesto no contiene hashes ni nombres de los archivos. El script comprueba que la respuesta tenga estructura ZIP/JAR, pero no puede garantizar su identidad ni integridad criptográfica.
- La carpeta preparada no incluye Minecraft ni el cargador. Hay que instalarlos por separado y configurar el launcher o servidor para usar la carpeta de instancia. El manifiesto declara Minecraft `26.3` y Fabric `0.20.1`; su disponibilidad y compatibilidad no han sido verificadas.
