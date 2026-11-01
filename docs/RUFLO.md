# Ruflo (antes claude-flow)

[Ruflo](https://github.com/ruvnet/ruflo) es una capa para orquestar varios agentes de Claude Code (enjambres, memoria, hooks). Se probó la versión `ruflo@3.69.0` el 1 de noviembre de 2026.

## Qué se hizo

1. Se instaló aparte del repo (`npm install -g ruflo@3.69.0`). Se leyó su `postinstall` antes: solo parcha el paquete `agentdb` dentro de `node_modules`.
2. Se corrió `ruflo init --minimal` en una **copia vacía**, no en el repo, para ver qué escribe.
3. Se corrieron `ruflo security scan` y `ruflo security secrets` sobre una copia del repo.

## Por qué no quedó instalado dentro del repo

`ruflo init` escribe 18 archivos, y varios chocan con las reglas de este proyecto o con la seguridad:

- **`CLAUDE.md` en inglés** con reglas que contradicen `AGENTS.md`: «archivos de menos de 500 líneas» (`MainActivity.kt` tiene más de 5000) y «no agregar Co-Authored-By».
- **`.claude/settings.json`** activa 3 plugins de terceros (`ruflo-mods`, `ruflo-swarm`, `ruflo-console`) con *function hooks* que pueden correr comandos en tu computador. El mismo init avisa que quedan «enabled unscanned».
- **`.mcp.json`** corre `npx -y ruflo@latest`: baja la última versión cada vez, sin fijarla. Si alguien publica una versión maliciosa, entra sola.
- Sus escáneres de seguridad no leen Kotlin: revisaron 9 archivos y no encontraron nada. La revisión real está en `docs/SEGURIDAD.md`.

`docs/00_SINTESIS.md` ya avisaba de lo mismo: hay skills que reescriben la configuración de MCP o traen puertas traseras, así que hay que leerlas antes de instalarlas.

## Si igual lo quieres usar en tu computador

Úsalo con la versión fija y sin los plugins:

```bash
npm install -g ruflo@3.69.0
```

```bash
ruflo init --minimal --no-mods --no-global --no-signup --no-skills-sh
```

Después:

- en `.mcp.json`, cambia `ruflo@latest` por `ruflo@3.69.0`;
- borra el `CLAUDE.md` que crea, porque las reglas de este proyecto están en `AGENTS.md`;
- no hagas commit de `.claude-flow/` ni de `.swarm/`.
