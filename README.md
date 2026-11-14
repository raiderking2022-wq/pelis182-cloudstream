# Teatro Latino

Complemento experimental de CloudStream: catálogo de películas, 24 géneros, tendencias, búsqueda paginada y extractor HLS con calidades separadas (360p a 1080p).

Probado el 2026-11-14 de extremo a extremo: catálogo, búsqueda, fichas y extracción de enlaces verificadas. El servidor de video exige la cabecera Referer del reproductor y el complemento la envía tanto al leer la lista maestra como al reproducir cada calidad.

## Instalación

1. Entra a Actions > Build Pelis182 > Run workflow (o simplemente espera al push).
2. Añade en CloudStream (Extensiones > Repositorios):

https://raw.githubusercontent.com/raiderking2022-wq/teatro-latino/builds/plugins.json

3. El artefacto compilado se publica automáticamente en la rama `builds` (Pelis182.cs3).

## Notas

- Solo series pendientes: no incluye temporadas ni episodios todavía.
- Repositorio de solo código: no aloja ni distribuye contenido de video. No evade DRM ni controles de acceso.
- Depende de la plantilla pública cloudstream3/plugin-template.
